package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.board.BoardCommands
import com.makefunstuff.herdr.core.board.BoardReader
import com.makefunstuff.herdr.core.board.ChatLine
import com.makefunstuff.herdr.core.board.CoordinatorChat
import com.makefunstuff.herdr.core.board.CoordinatorView
import com.makefunstuff.herdr.core.board.Said
import com.makefunstuff.herdr.core.ssh.Machine
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoardTest {
    private val machine = Machine("m", "m", "h", user = "u")

    @Test
    fun readsTheCoordinatorFromAListAmidShellNoise() {
        val out = """
            Welcome to the box
            {"not":"ours"}
            {"host":"devbox","version":"1.0.1","tasks":[],"coordinator":{"state":"working","error":"","queue":1,"readable":true,"said":"On it","sent":"hello","talk":[{"you":true,"text":"hi"},{"you":false,"text":"Hello!"},{"you":false,"text":""}]}}
            bye
        """.trimIndent()
        val l = BoardReader.list(out)
        assertEquals("devbox", l.host)
        assertEquals("1.0.1", l.version)
        val c = l.coordinator!!
        assertEquals("working", c.state)
        assertEquals(1, c.queue)
        assertEquals(listOf(Said(true, "hi"), Said(false, "Hello!")), c.talk)
        assertTrue(c.busy)
    }

    @Test
    fun noCoordinatorWhereNoneCanRunOrTheBoardIsOld() {
        val off = BoardReader.list("""{"host":"a","version":"1","tasks":[],"coordinator":{"off":true}}""")
        assertNull(off.coordinator)
        assertEquals(false, off.tooOld)
        val old = BoardReader.list("""{"host":"a","version":"0.2","tasks":[]}""")
        assertNull(old.coordinator)
        assertTrue(old.tooOld)
    }

    @Test
    fun dispatchAnswers() {
        assertEquals("sent to devbox", BoardReader.done("motd\n{\"ok\":true,\"msg\":\"sent to devbox\"}\n"))
        val e = assertFailsWith<java.io.IOException> { BoardReader.done("{\"ok\":false,\"msg\":\"herdr is not running\"}") }
        assertEquals("herdr is not running", e.message)
    }

    @Test
    fun commandsQuoteThePathAndCarryTheSession() {
        val req = BoardCommands.dispatchRequest("it's \"quoted\"\nline two", delegate = true)
        val cmd = BoardCommands.command(machine.copy(session = "work"), "/home/u/my dir/herdr-projects", "dispatch", req)
        assertTrue(cmd.startsWith("env HERDR_SESSION='work' '/home/u/my dir/herdr-projects' remote 'dispatch' "), cmd)
        val b64 = cmd.substringAfterLast(' ')
        assertEquals("""{"text":"it's \"quoted\"\nline two","delegate":true}""", String(Base64.getDecoder().decode(b64)))
        assertEquals("'/p' remote 'list'", BoardCommands.command(machine, "/p", "list"))
        // The board gets the exact executable as well as its directory on PATH.
        assertEquals(
            "env HERDR_BIN_PATH='/home/u/.local/bin/herdr' PATH='/home/u/.local/bin':\"\$PATH\" '/p' remote 'list'",
            BoardCommands.command(machine.copy(herdrPath = "/home/u/.local/bin/herdr"), "/p", "list"),
        )
    }

    @Test
    fun launcherRunsTheSelectedExecutableIncludingVersionedFilenames() {
        val dir = kotlin.io.path.createTempDirectory("board-binary").toFile()
        try {
            fun binary(path: String, version: String) = java.io.File(dir, path).apply {
                parentFile.mkdirs()
                writeText("#!/bin/sh\nprintf 'herdr %s\\n' '$version'\n")
                setExecutable(true)
            }
            val old = binary("old/herdr", "0.8.2")
            // Like the installed board, the launcher prefers HERDR_BIN_PATH over the name herdr.
            val launcher = java.io.File(dir, "probe launcher").apply {
                writeText("#!/bin/sh\nprintf 'session=%s\\n' \"\$HERDR_SESSION\"\n\"\${HERDR_BIN_PATH:-herdr}\" --version\n")
                setExecutable(true)
            }
            val selected = listOf("herdr", "herdr-0.9.5").map { binary("server's binaries/$it", "0.9.5") }
            for (executable in selected) {
                val m = machine.copy(session = "named session", herdrPath = executable.path)
                for (what in listOf("list", "dispatch")) {
                    val request = if (what == "dispatch") BoardCommands.dispatchRequest("hello", true) else null
                    val commands = listOf(
                        BoardCommands.command(m, launcher.path, what, request),
                        BoardCommands.remote(m, launcher.path, what, request),
                    )
                    for (command in commands) {
                        val p = ProcessBuilder("/bin/sh", "-c", command).apply {
                            environment().putAll(mapOf(
                                "PATH" to "${old.parent}:/usr/bin:/bin", "HOME" to dir.path,
                                "SHELL" to "/bin/sh",
                                "HERDR_BIN_PATH" to if (what == "dispatch") old.path else "",
                            ))
                        }.redirectErrorStream(true).start()
                        val output = p.inputStream.readBytes().toString(Charsets.UTF_8)
                        assertEquals(0, p.waitFor(), output)
                        assertEquals("session=named session\nherdr 0.9.5\n", output, command)
                    }
                }
                executable.delete() // The versioned case must have no sibling named herdr.
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun runsTheLauncherInALoginShellWithItsPath() {
        // A login shell's profile adds a folder to PATH; SSH's own PATH (here a bare one) lacks it.
        val dir = kotlin.io.path.createTempDirectory("board").toFile()
        try {
            val bin = java.io.File(dir, "only-in-profile").apply { mkdirs() }
            java.io.File(dir, ".profile").writeText("PATH=\"${bin.path}:\$PATH\"\n")
            java.io.File(dir, "my launcher").apply {
                writeText("#!/bin/sh\necho \"PATH=\$PATH\"\necho \"SESSION=\$HERDR_SESSION\"\nfor a; do echo \"ARG=\$a\"; done\n")
                setExecutable(true)
            }
            val req = BoardCommands.dispatchRequest("it's \"x\"", delegate = false)
            val cmd = BoardCommands.remote(machine.copy(session = "work"), java.io.File(dir, "my launcher").path, "dispatch", req)
            val p = ProcessBuilder("/bin/sh", "-c", cmd).apply {
                environment().clear()
                environment().putAll(mapOf("HOME" to dir.path, "SHELL" to "/bin/sh", "PATH" to "/usr/bin:/bin"))
            }.start()
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8).lines()
            assertEquals(0, p.waitFor(), out.joinToString("\n"))
            assertTrue(out.first().startsWith("PATH=${bin.path}:"), out.joinToString("\n"))
            assertEquals("SESSION=work", out[1])
            assertEquals(listOf("ARG=remote", "ARG=dispatch"), out.subList(2, 4))
            assertEquals(req.toString(), String(Base64.getDecoder().decode(out[4].removePrefix("ARG="))))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun chatEndsWithYourUnansweredWordsOnce() {
        val v = CoordinatorView(state = "working", sent = "do the thing", talk = listOf(Said(false, "Hi"), Said(true, "do the thing")))
        assertEquals(listOf(ChatLine(false, "Hi"), ChatLine(true, "do the thing", pending = true)), CoordinatorChat.lines(v))
        val queued = CoordinatorView(state = "idle", queue = 1, sent = "next", talk = listOf(Said(true, "a"), Said(false, "b")))
        assertEquals(ChatLine(true, "next", pending = true), CoordinatorChat.lines(queued).last())
        assertEquals(3, CoordinatorChat.lines(queued).size)
    }

    @Test
    fun chatShowsTheLatestMessageWhenTheConversationHasNone() {
        val v = CoordinatorView(state = "idle", said = "Ready", talk = listOf(Said(true, "hi")))
        assertEquals(listOf(ChatLine(true, "hi"), ChatLine(false, "Ready")), CoordinatorChat.lines(v))
    }

    @Test
    fun historyGrowsAcrossReads() {
        val a = listOf(Said(true, "1"), Said(false, "2"), Said(true, "3"))
        val b = listOf(Said(false, "2"), Said(true, "3"), Said(false, "4"))
        assertEquals(a + Said(false, "4"), CoordinatorChat.merge(a, b))
        // A reply still being written grows between reads.
        val c = listOf(Said(true, "3"), Said(false, "4 and more"))
        assertEquals(a + Said(false, "4 and more"), CoordinatorChat.merge(a + Said(false, "4"), c))
        // A fresh conversation replaces the old one.
        assertEquals(listOf(Said(true, "new")), CoordinatorChat.merge(a, listOf(Said(true, "new"))))
        assertEquals(a, CoordinatorChat.merge(a, a))
    }
}

/**
 * The commands against a real herdr-projects on this machine, run through a shell as SSH runs them.
 * Only with HERDR_BOARD_LIVE=1. It sends nothing to the Coordinator (a dispatch that finds none
 * files a task instead): a request goes the same way to `remote do` with an action the board refuses.
 */
class LiveBoardTest {
    private val machine = Machine("m", "m", "h", user = "u")

    private fun sh(cmd: String): Pair<Int, String> {
        val p = ProcessBuilder("bash", "-c", cmd).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        return p.waitFor() to out
    }

    @Test
    fun locatesListsAndCarriesARequest() {
        if (System.getenv("HERDR_BOARD_LIVE") != "1") return
        val (code, out) = sh(BoardCommands.locate())
        assertEquals(0, code)
        val path = out.trim().lines().last()
        println("board at $path")
        val list = BoardReader.list(sh(BoardCommands.remote(machine, path, "list")).second)
        println("host ${list.host} ${list.version}: ${list.coordinator?.state}, ${list.coordinator?.talk?.size} said")
        println(CoordinatorChat.lines(list.coordinator!!).joinToString("\n") { (if (it.you) "You: " else "Coordinator: ") + it.text.take(80).replace('\n', ' ') })
        assertTrue(list.host.isNotEmpty())
        assertTrue(!list.tooOld)
        val req = kotlinx.serialization.json.buildJsonObject {
            put("id", kotlinx.serialization.json.JsonPrimitive(System.getenv("HERDR_BOARD_TASK") ?: "herdr-android/1"))
            put("action", kotlinx.serialization.json.JsonPrimitive("it's \"not\" an action"))
        }
        val answer = sh(BoardCommands.remote(machine, path, "do", req)).second
        println("do: $answer")
        val e = assertFailsWith<java.io.IOException> { BoardReader.done(answer) }
        assertEquals("Unknown action it's \"not\" an action", e.message)
    }
}
