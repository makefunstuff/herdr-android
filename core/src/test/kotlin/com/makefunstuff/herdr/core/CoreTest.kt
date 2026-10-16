package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.api.ApiConnection
import com.makefunstuff.herdr.core.api.HerdrApi
import com.makefunstuff.herdr.core.api.HerdrApiException
import com.makefunstuff.herdr.core.keys.KeyExport
import com.makefunstuff.herdr.core.keys.KeyGen
import com.makefunstuff.herdr.core.keys.KeyImport
import com.makefunstuff.herdr.core.keys.OpenSshKeyFile
import com.makefunstuff.herdr.core.keys.PassphraseException
import com.makefunstuff.herdr.core.ssh.HerdrCommands
import com.makefunstuff.herdr.core.ssh.Machine
import com.makefunstuff.herdr.core.terminal.Cell
import com.makefunstuff.herdr.core.terminal.CharWidth
import com.makefunstuff.herdr.core.terminal.FrameCodec
import com.makefunstuff.herdr.core.terminal.Keys
import com.makefunstuff.herdr.core.terminal.TerminalEmulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerminalEmulatorTest {
    @Test
    fun herdrBlitFrameRenders() {
        val t = TerminalEmulator(20, 4)
        // Shape of a herdr full frame: sync start, hide cursor, clear, positioned SGR runs, cursor.
        t.feed("\u001b[?2026h\u001b[?25l\u001b]8;;\u001b\\\u001b[2J\u001b[1;1H\u001b[0;39;49mhello \u001b[0;1;38;2;255;128;64;49mworld" +
            "\u001b[2;3H\u001b[0;7;39;49mA\u001b[0m\u001b[?2026l\u001b[2;4H\u001b[?25h")
        assertEquals("hello world", t.lineText(0))
        assertEquals("  A", t.lineText(1))
        assertEquals(Cell.rgb(255, 128, 64), t.cell(6, 0).fg)
        assertTrue(t.cell(6, 0).attrs and Cell.BOLD != 0)
        assertTrue(t.cell(2, 1).attrs and Cell.INVERSE != 0)
        assertEquals(3, t.cursorX)
        assertEquals(1, t.cursorY)
        assertTrue(t.cursorVisible)
    }

    @Test
    fun partialFrameUpdatesOnlyTouchedCells() {
        val t = TerminalEmulator(10, 2)
        t.feed("\u001b[1;1Habcdefghij\u001b[2;1Hxyz")
        t.feed("\u001b[1;3H\u001b[0;31;49mZ")
        assertEquals("abZdefghij", t.lineText(0))
        assertEquals(1, t.cell(2, 0).fg)
        assertEquals("xyz", t.lineText(1))
    }

    @Test
    fun wrapsAndScrolls() {
        val t = TerminalEmulator(5, 2)
        t.feed("12345678\r\nab")
        assertEquals("678", t.lineText(0))
        assertEquals("ab", t.lineText(1))
    }

    @Test
    fun wideCharactersTakeTwoCells() {
        val t = TerminalEmulator(6, 1)
        t.feed("a漢b")
        assertEquals("漢", t.cell(1, 0).text)
        assertTrue(t.cell(2, 0).isContinuation)
        assertEquals("b", t.cell(3, 0).text)
        assertEquals("a漢b", t.lineText(0))
    }

    @Test
    fun utf8SplitAcrossFeeds() {
        val t = TerminalEmulator(6, 1)
        val bytes = "╭─╮".toByteArray()
        t.feed(bytes, 0, 2)
        t.feed(bytes, 2, bytes.size - 2)
        assertEquals("╭─╮", t.lineText(0))
    }

    @Test
    fun eraseAndAltScreen() {
        val t = TerminalEmulator(6, 2)
        t.feed("abcdef\u001b[1;3H\u001b[K")
        assertEquals("ab", t.lineText(0))
        t.feed("\u001b[?1049h")
        assertTrue(t.isAltScreen)
        assertEquals("", t.lineText(0))
        t.feed("alt\u001b[?1049l")
        assertFalse(t.isAltScreen)
        assertEquals("ab", t.lineText(0))
    }

    @Test
    fun kittyGraphicsApcIsSkipped() {
        val t = TerminalEmulator(10, 1)
        t.feed("a\u001b_Gf=100,a=T;QUJD\u001b\\b")
        assertEquals("ab", t.lineText(0))
    }

    @Test
    fun indexedAndColonColors() {
        val t = TerminalEmulator(4, 1)
        t.feed("\u001b[38;5;171;48:2::1:2:3mX\u001b[4:3mY")
        assertEquals(171, t.cell(0, 0).fg)
        assertEquals(Cell.rgb(1, 2, 3), t.cell(0, 0).bg)
        assertTrue(t.cell(1, 0).attrs and Cell.UNDERLINE != 0)
    }

    @Test
    fun widthTablesAreSorted() {
        assertEquals(2, CharWidth.of('漢'.code))
        assertEquals(2, CharWidth.of(0x1F600))
        assertEquals(0, CharWidth.of(0x0301))
        assertEquals(1, CharWidth.of('─'.code))
    }
}

class FrameCodecTest {
    @Test
    fun parsesFrameRecords() {
        val payload = Base64.getEncoder().encodeToString("hi".toByteArray())
        val f = assertNotNull(FrameCodec.parse("""{"type":"terminal.frame","seq":3,"encoding":"ansi","width":80,"height":24,"full":true,"bytes":"$payload"}"""))
        assertEquals(3, f.seq)
        assertEquals(80, f.width)
        assertTrue(f.full)
        assertEquals("hi", String(f.bytes))
        assertNull(FrameCodec.parse("""{"type":"terminal.closed","reason":"pane closed"}"""))
        assertEquals("pane closed", FrameCodec.isClosed("""{"type":"terminal.closed","reason":"pane closed"}"""))
        assertEquals("""{"type":"terminal.resize","cols":40,"rows":20}""", FrameCodec.resize(40, 20))
    }

    @Test
    fun ctrlModifier() {
        assertEquals("\u0003", Keys.withModifiers("c", ctrl = true, alt = false))
        assertEquals("\u001bx", Keys.withModifiers("x", ctrl = false, alt = true))
    }
}

class ApiConnectionTest {
    @Test
    fun matchesResponsesAndEmitsEvents() = runBlocking {
        val toClient = PipedOutputStream()
        val clientIn = PipedInputStream(toClient, 1 shl 16)
        val fromClient = PipedInputStream(1 shl 16)
        val clientOut = PipedOutputStream(fromClient)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val conn = ApiConnection(clientIn, clientOut, scope)
        // Fake server: answer each request, and push one event first.
        scope.launch {
            val reader = fromClient.bufferedReader()
            while (true) {
                val line = reader.readLine() ?: break
                val id = Regex("\"id\":\"([^\"]+)\"").find(line)!!.groupValues[1]
                val method = Regex("\"method\":\"([^\"]+)\"").find(line)!!.groupValues[1]
                val reply = when (method) {
                    "ping" -> """{"id":"$id","result":{"type":"pong","version":"0.9.5","protocol":3,"capabilities":{}}}"""
                    "agent.list" -> """{"event":"pane.agent_status_changed","data":{"pane_id":"w1:p1","agent_status":"blocked"}}""" + "\n" +
                        """{"id":"$id","result":{"type":"agent_list","agents":[{"terminal_id":"t1","agent":"claude","agent_status":"blocked","workspace_id":"w1","tab_id":"w1:t1","pane_id":"w1:p1","focused":true,"brand_new_field":42}]}}"""
                    else -> """{"id":"$id","error":{"code":"not_found","message":"pane not found"}}"""
                }
                toClient.write((reply + "\n").toByteArray())
                toClient.flush()
            }
        }
        val api = HerdrApi(conn)
        withTimeout(5000) {
            assertEquals("0.9.5", api.ping().version)
            val agents = api.agents()
            assertEquals(AgentStatus.Blocked, agents.single().agentStatus)
            assertEquals("claude", agents.single().displayName)
            val event = conn.events.first()
            assertEquals("pane.agent_status_changed", event["event"].toString().trim('"'))
            val e = assertFailsWith<HerdrApiException> { api.readPane("w9:p9") }
            assertEquals("not_found", e.code)
        }
        conn.close()
        scope.cancel()
    }
}

class MachineTest {
    @Test
    fun parsesTargets() {
        assertEquals(Triple("you", "server", 2222), Machine.parseTarget("ssh://you@server:2222"))
        assertEquals(Triple("j", "mac-mini.local", 22), Machine.parseTarget("j@mac-mini.local"))
        assertEquals(Triple(null, "workbox", 22), Machine.parseTarget("workbox"))
        assertEquals(Triple("a", "::1", 2200), Machine.parseTarget("a@[::1]:2200"))
        assertNull(Machine.parseTarget(""))
    }

    @Test
    fun commandFindsHerdrAndQuotesArguments() {
        val dir = createTempDir()
        val fake = File(dir, "herdr")
        fake.writeText("#!/bin/sh\nfor a in \"\$@\"; do printf '[%s]' \"\$a\"; done\n")
        fake.setExecutable(true)
        val m = Machine("id", "box", "h", user = "u", session = "my session", herdrPath = fake.absolutePath)
        val cmd = HerdrCommands.terminalControl(m, "w1:p1", 40, 20)
        val out = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start().inputStream.readBytes().toString(Charsets.UTF_8)
        assertEquals("[--session][my session][terminal][session][control][w1:p1][--takeover][--cols][40][--rows][20]", out)

        val missing = m.copy(herdrPath = "/nonexistent/herdr")
        val pb = ProcessBuilder("sh", "-c", HerdrCommands.apiBridgeCheck(missing))
        pb.environment()["PATH"] = "/usr/bin:/bin"
        pb.environment()["HOME"] = dir.absolutePath
        val p = pb.start()
        val code = p.waitFor()
        assertEquals(HerdrCommands.NOT_FOUND_EXIT, code)
        assertTrue(p.errorStream.readBytes().toString(Charsets.UTF_8).contains(HerdrCommands.NOT_FOUND_MESSAGE))
    }
}

class KeyGenTest {
    @Test
    fun generatedKeyLoadsInSshj() {
        val id = KeyGen.ed25519("herdr-android")
        assertTrue(id.publicKeyLine.startsWith("ssh-ed25519 "))
        val client = SSHClient(DefaultConfig())
        val provider = client.loadKeys(id.privateKeyPem, id.publicKeyLine, null)
        assertEquals("ssh-ed25519", provider.type.toString())
        assertNotNull(provider.private)
    }
}

class KeyImportTest {
    @Test
    fun importsGeneratedKey() {
        val id = KeyGen.ed25519("x")
        val imported = com.makefunstuff.herdr.core.keys.KeyImport.fromPrivateKey(id.privateKeyPem, "x")
        assertEquals(id.publicKeyLine, imported.publicKeyLine)
    }
}

class KeyBackupTest {
    private fun blob(line: String) = line.split(' ')[1]

    @Test
    fun exportedBackupNeedsItsPassphrase() {
        val id = KeyGen.ed25519("herdr-android@Pixel")
        val backup = KeyExport.encrypted(id, "correct horse")
        assertTrue(OpenSshKeyFile.isEncrypted(backup))
        assertFalse(backup.contains(id.privateKeyPem.lines()[1]))
        assertFailsWith<PassphraseException> { KeyImport.fromPrivateKey(backup, "other") }
        assertFailsWith<PassphraseException> { KeyImport.fromPrivateKey(backup, "other", "wrong horse") }
        val restored = KeyImport.fromPrivateKey(backup, "other", "correct horse")
        // Same key and the original comment, so the authorized_keys line is unchanged.
        assertEquals(id.publicKeyLine, restored.publicKeyLine)
        assertFalse(OpenSshKeyFile.isEncrypted(restored.privateKeyPem))
    }

    @Test
    fun keyWithoutCommentGetsTheFallback() {
        val id = KeyGen.ed25519("")
        assertEquals(blob(id.publicKeyLine), blob(KeyImport.fromPrivateKey(id.privateKeyPem, "phone").publicKeyLine))
        assertTrue(KeyImport.fromPrivateKey(id.privateKeyPem, "phone").publicKeyLine.endsWith(" phone"))
    }

    @Test
    fun rejectsGarbage() {
        assertFailsWith<IllegalArgumentException> { KeyImport.fromPrivateKey("hello", "x") }
        assertFailsWith<IllegalArgumentException> {
            KeyImport.fromPrivateKey("${OpenSshKeyFile.BEGIN}\nAAAA\n${OpenSshKeyFile.END}\n", "x")
        }
    }

    // Interop with OpenSSH's own tool, when it is installed.
    private val sshKeygen = listOf("/usr/bin/ssh-keygen", "/usr/local/bin/ssh-keygen").firstOrNull { File(it).canExecute() }

    private fun keygen(vararg args: String): String {
        val p = ProcessBuilder(sshKeygen!!, *args).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        assertEquals(0, p.waitFor(), out)
        return out
    }

    private fun tempKey(): File = File.createTempFile("herdr-key", "").also { it.delete(); it.deleteOnExit() }

    @Test
    fun importsKeysMadeBySshKeygen() {
        if (sshKeygen == null) return
        val cases = listOf(
            listOf("-t", "ed25519"),
            listOf("-t", "ecdsa", "-b", "256"),
            listOf("-t", "rsa", "-b", "2048"),
            listOf("-t", "rsa", "-b", "2048", "-m", "PEM"),
            listOf("-t", "ecdsa", "-b", "384", "-m", "PEM"),
        )
        for (case in cases) for (passphrase in listOf("", "s3cret pass")) {
            val file = tempKey()
            keygen(*case.toTypedArray(), "-N", passphrase, "-C", "me@laptop", "-q", "-f", file.path)
            val expected = File(file.path + ".pub").readText().trim()
            if (passphrase.isNotEmpty()) {
                assertFailsWith<PassphraseException>("$case") { KeyImport.fromPrivateKey(file.readText(), "x") }
            }
            val imported = KeyImport.fromPrivateKey(file.readText(), "x", passphrase.ifEmpty { null })
            assertEquals(blob(expected), blob(imported.publicKeyLine), "$case")
        }
    }

    @Test
    fun sshKeygenReadsTheBackup() {
        if (sshKeygen == null) return
        val id = KeyGen.ed25519("herdr-android@Pixel")
        val file = tempKey()
        file.writeText(KeyExport.encrypted(id, "correct horse"))
        file.setReadable(false, false)
        file.setReadable(true, true)
        val public = keygen("-y", "-P", "correct horse", "-f", file.path).trim()
        assertEquals(blob(id.publicKeyLine), blob(public))
        val plain = tempKey()
        plain.writeText(id.privateKeyPem)
        plain.setReadable(false, false)
        plain.setReadable(true, true)
        assertEquals(id.publicKeyLine, keygen("-y", "-f", plain.path).trim())
    }

    @Test
    fun rsaBackupRoundTrips() {
        if (sshKeygen == null) return
        val file = tempKey()
        keygen("-t", "rsa", "-b", "2048", "-m", "PEM", "-N", "", "-q", "-f", file.path)
        val id = KeyImport.fromPrivateKey(file.readText(), "x")
        val backup = tempKey()
        backup.writeText(KeyExport.encrypted(id, "pass phrase"))
        backup.setReadable(false, false)
        backup.setReadable(true, true)
        assertEquals(blob(id.publicKeyLine), blob(keygen("-y", "-P", "pass phrase", "-f", backup.path).trim()))
        assertEquals(id.publicKeyLine, KeyImport.fromPrivateKey(backup.readText(), "y", "pass phrase").publicKeyLine)
    }
}
