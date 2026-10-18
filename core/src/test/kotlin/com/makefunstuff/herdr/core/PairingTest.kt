package com.makefunstuff.herdr.core

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.LuminanceSource
import com.makefunstuff.herdr.core.keys.KeyGen
import com.makefunstuff.herdr.core.ssh.AddressKind
import com.makefunstuff.herdr.core.ssh.PairError
import com.makefunstuff.herdr.core.ssh.PairOffer
import com.makefunstuff.herdr.core.ssh.Pairing
import com.makefunstuff.herdr.core.ssh.SshConnector
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pairing by QR code: the link `herdr-push pair` puts in its code, the code itself as a scanner
 * reads it, `pair-accept` on the machine, and the whole of it against a real sshd.
 */
class PairingTest {
    private val script = File(System.getProperty("herdrPush") ?: "../host/herdr-push/herdr-push")

    private fun has(vararg command: String): Boolean = runCatching {
        ProcessBuilder(*command).redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS)
    }.getOrDefault(false)

    private fun python() = has("python3", "--version")

    private val seed = ByteArray(32) { it.toByte() }
    private val code = Base64.getUrlEncoder().withoutPadding().encodeToString(seed)
    private val fp = ByteArray(32) { (it * 7).toByte() }
    private val fpUrl = Base64.getUrlEncoder().withoutPadding().encodeToString(fp)

    @Test
    fun readsAPairingLink() {
        val offer = assertNotNull(
            PairOffer.parse("herdr://pair?v=1&n=devbox&u=demo&a=devbox.tail1234.ts.net,100.84.3.12&p=2201&k=$fpUrl&c=$code&x=1792222540&s=work"),
        )
        assertEquals("devbox", offer.name)
        assertEquals("demo", offer.user)
        assertEquals(listOf("devbox.tail1234.ts.net", "100.84.3.12"), offer.addresses)
        assertEquals(2201, offer.port)
        assertEquals(listOf("SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(fp)), offer.hostKeys)
        assertEquals(1792222540L, offer.expires)
        assertEquals("work", offer.session)
        assertEquals("demo@devbox.tail1234.ts.net:2201", offer.target())
        assertTrue(offer.oneTimeKey().publicKeyLine.startsWith("ssh-ed25519 "))

        val plain = assertNotNull(PairOffer.parse("  herdr://pair?v=1&u=me&a=10.0.0.5&k=$fpUrl&c=$code&x=1\n"))
        assertEquals("10", plain.name)
        assertEquals(22, plain.port)
        assertEquals("default", plain.session)
        assertEquals("me@10.0.0.5", plain.target())
    }

    @Test
    fun refusesWhatIsNotAPairingLink() {
        val good = "herdr://pair?v=1&n=devbox&u=demo&a=devbox&p=22&k=$fpUrl&c=$code&x=1"
        assertNotNull(PairOffer.parse(good))
        assertNull(PairOffer.parse("https://example.com/?v=1"))
        assertNull(PairOffer.parse("demo@devbox"))
        assertNull(PairOffer.parse(good.replace("v=1", "v=2")), "a newer herdr-push's code")
        assertNull(PairOffer.parse(good.replace("u=demo&", "")), "no user")
        assertNull(PairOffer.parse(good.replace("a=devbox&", "a=&")), "no address")
        assertNull(PairOffer.parse(good.replace("k=$fpUrl&", "k=abc&")), "no whole fingerprint")
        assertNull(PairOffer.parse(good.replace("c=$code&", "c=${code.drop(4)}&")), "a short code")
        assertNull(PairOffer.parse(good.replace("&x=1", "")), "no expiry")
    }

    @Test
    fun saysHowAnAddressReachesTheMachine() {
        assertEquals(AddressKind.Tailscale, AddressKind.of("devbox.tail1234.ts.net"))
        assertEquals(AddressKind.Tailscale, AddressKind.of("100.84.3.12"))
        assertEquals(AddressKind.Local, AddressKind.of("192.168.1.20"))
        assertEquals(AddressKind.Local, AddressKind.of("10.0.0.5"))
        assertEquals(AddressKind.Local, AddressKind.of("172.20.1.1"))
        assertEquals(AddressKind.Local, AddressKind.of("devbox.lan"))
        assertEquals(AddressKind.Other, AddressKind.of("100.200.3.12"))
        assertEquals(AddressKind.Other, AddressKind.of("example.com"))
    }

    /** The QR code herdr-push draws reads back as the link, at every size a link can take. */
    @Test
    fun theQrCodeReadsBack() {
        assumeTrue(python(), "python3 isn't installed")
        val link = "herdr://pair?v=1&n=devbox&u=demo&a=devbox.tail1234.ts.net,100.84.3.12,192.168.1.20&p=22&k=$fpUrl,$fpUrl,$fpUrl&c=$code&x=1792222540"
        val texts = listOf("x", link) + listOf(17, 40, 77, 120, 190, 260, 340, 420, 500, 600, 700, 800).map { n ->
            (link + link + link).take(n)
        }
        for (text in texts) {
            val p = ProcessBuilder("python3", script.path, "_qr", text).start()
            val rows = p.inputStream.readBytes().toString(Charsets.UTF_8).lines().filter { it.isNotEmpty() }
            assertTrue(p.waitFor(60, TimeUnit.SECONDS))
            assertEquals(0, p.exitValue(), p.errorStream.readBytes().toString(Charsets.UTF_8))
            assertEquals(text, decode(rows), "${text.length} characters, ${rows.size} modules")
        }
    }

    private fun decode(rows: List<String>): String {
        val scale = 4
        val quiet = 4
        val size = (rows.size + 2 * quiet) * scale
        val pixels = ByteArray(size * size) { 0xFF.toByte() }
        rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, c ->
                if (c == '1') {
                    for (dy in 0 until scale) for (dx in 0 until scale) {
                        pixels[((y + quiet) * scale + dy) * size + (x + quiet) * scale + dx] = 0
                    }
                }
            }
        }
        val source = object : LuminanceSource(size, size) {
            override fun getRow(y: Int, row: ByteArray?): ByteArray = pixels.copyOfRange(y * size, (y + 1) * size)
            override fun getMatrix(): ByteArray = pixels
        }
        val hints = mapOf(DecodeHintType.PURE_BARCODE to false, DecodeHintType.TRY_HARDER to true)
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
    }

    private class Run(val exit: Int, val out: String)

    private fun run(env: Map<String, String>, input: String, vararg args: String): Run {
        val pb = ProcessBuilder(listOf("python3", script.path) + args)
        pb.environment().putAll(env)
        val p = pb.start()
        p.outputStream.use { it.write(input.toByteArray()) }
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(p.waitFor(60, TimeUnit.SECONDS))
        return Run(p.exitValue(), out)
    }

    /** `pair-accept`, the one-time key's forced command: takes a key once, and only while the code is valid. */
    @Test
    fun pairAcceptTakesOneKeyOnce() {
        assumeTrue(python(), "python3 isn't installed")
        val dir = Files.createTempDirectory("herdr-pair").toFile()
        try {
            val keys = File(dir, "authorized_keys")
            val state = File(dir, "state").apply { mkdirs() }
            val token = "0123456789abcdef"
            val later = System.currentTimeMillis() / 1000 + 300
            val entry = "restrict,command=\"true\" ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOnetimeOnetimeOnetimeOnetimeOnetimeOne herdr-pair-$token-$later"
            val stale = "restrict ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIStaleStaleStaleStaleStaleStaleStaleStale herdr-pair-fedcba9876543210-1000"
            val mine = KeyGen.ed25519("me@desk").publicKeyLine
            keys.writeText("$mine\n$stale\n$entry\n")
            File(state, "pair-$token.json").writeText("{\"expires\": $later, \"used\": false}")
            val phone = KeyGen.ed25519("herdr-android@Pixel 9")

            val bad = run(emptyMap(), "not a key\n", "pair-accept", token, state.path, keys.path)
            assertEquals(1, bad.exit)
            assertEquals("That isn't an SSH public key.", answer(bad.out)["error"]!!.jsonPrimitive.content)

            val ok = run(emptyMap(), phone.publicKeyLine + "\n", "pair-accept", token, state.path, keys.path)
            assertEquals(0, ok.exit, ok.out)
            assertTrue(answer(ok.out)["added"]!!.jsonPrimitive.boolean)
            assertEquals(listOf(mine, phone.publicKeyLine), keys.readLines(), "the phone's key in, the one-time and the stale one out")

            val again = run(emptyMap(), KeyGen.ed25519("another").publicKeyLine + "\n", "pair-accept", token, state.path, keys.path)
            assertEquals(1, again.exit)
            assertTrue(answer(again.out)["error"]!!.jsonPrimitive.content.startsWith("This code was used already"))
            assertEquals(listOf(mine, phone.publicKeyLine), keys.readLines())

            // A code past its time adds nothing; a key already there isn't added twice.
            val old = "fedcba9876543210"
            File(state, "pair-$old.json").writeText("{\"expires\": 1000, \"used\": false}")
            val expired = run(emptyMap(), KeyGen.ed25519("late").publicKeyLine + "\n", "pair-accept", old, state.path, keys.path)
            assertTrue(answer(expired.out)["error"]!!.jsonPrimitive.content.startsWith("This code expired"))
            val fresh = "aaaaaaaaaaaaaaaa"
            File(state, "pair-$fresh.json").writeText("{\"expires\": $later, \"used\": false}")
            val twice = run(emptyMap(), "$mine\n", "pair-accept", fresh, state.path, keys.path)
            assertEquals(0, twice.exit)
            assertFalse(answer(twice.out)["added"]!!.jsonPrimitive.boolean)
            assertEquals(listOf(mine, phone.publicKeyLine), keys.readLines())
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun answer(out: String) = Json.parseToJsonElement(out.trim().lines().last()).jsonObject

    /**
     * The whole of it, as on a machine: `herdr-push pair` against a real sshd (one started for the
     * test, on a free port, with its own host key and authorized_keys), and the phone's side.
     */
    @Test
    fun pairsWithARealSshd() = runBlocking {
        val sshd = listOf("/usr/sbin/sshd", "/usr/bin/sshd").firstOrNull { File(it).canExecute() }
        assumeTrue(sshd != null && python(), "sshd and python3 are needed")
        val dir = Files.createTempDirectory("herdr-pair-sshd").toFile()
        var server: Process? = null
        var pair: Process? = null
        try {
            val hostKey = File(dir, "ssh_host_ed25519_key")
            assertEquals(0, ProcessBuilder("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", hostKey.path).start().waitFor())
            val keys = File(dir, "authorized_keys")
            val port = ServerSocket(0).use { it.localPort }
            val config = File(dir, "sshd_config")
            config.writeText(
                """
                Port $port
                ListenAddress 127.0.0.1
                HostKey ${hostKey.path}
                PidFile ${dir.path}/sshd.pid
                AuthorizedKeysFile ${keys.path}
                StrictModes no
                PubkeyAuthentication yes
                PasswordAuthentication no
                KbdInteractiveAuthentication no
                UsePAM no
                """.trimIndent() + "\n",
            )
            server = ProcessBuilder(sshd, "-D", "-e", "-f", config.path).redirectErrorStream(true)
                .redirectOutput(File(dir, "sshd.log")).start()
            val up = (1..50).any {
                runCatching { Socket("127.0.0.1", port).close() }.isSuccess || run { Thread.sleep(100); false }
            }
            assumeTrue(up, "sshd didn't start: " + File(dir, "sshd.log").readText())

            val pb = ProcessBuilder("python3", script.path, "pair", "--host", "127.0.0.1", "--port", "$port", "--ttl", "60", "--name", "devbox")
            pb.environment() += mapOf(
                "HERDR_PUSH_HOME" to File(dir, "push").path,
                "HERDR_PAIR_AUTHORIZED_KEYS" to keys.path,
                "HERDR_PAIR_HOST_KEYS" to dir.path,
            )
            pair = pb.redirectErrorStream(true).start()
            val printed = StringBuilder()
            val reader = pair.inputStream.bufferedReader()
            var link: String? = null
            while (link == null) {
                val line = reader.readLine() ?: break
                printed.appendLine(line)
                if (line.startsWith("herdr://pair?")) link = line
            }
            val offer = assertNotNull(link?.let(PairOffer::parse), printed.toString())
            assertEquals("devbox", offer.name)
            assertEquals(System.getProperty("user.name"), offer.user)
            val expected = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(File("${hostKey.path}.pub").readText().split(" ")[1])),
            )
            assertEquals(listOf(expected), offer.hostKeys)
            assertTrue(keys.readText().contains("herdr-pair-"), "the one-time key is in while it waits")

            val reached = Pairing.reach(offer)
            assertEquals("127.0.0.1", reached.address)
            assertEquals(expected, reached.hostKey)
            assertEquals(expected, SshConnector.hostKey("127.0.0.1", port))

            val phone = KeyGen.ed25519("herdr-android@test")
            val machine = Pairing.pair(offer, reached, phone, "m1")
            assertEquals("127.0.0.1", machine.host)
            assertEquals(port, machine.port)
            assertEquals(expected, machine.hostKey)

            assertTrue(pair.waitFor(15, TimeUnit.SECONDS), "pair stops once the phone is in")
            val rest = reader.readText()
            assertEquals(0, pair.exitValue(), printed.toString() + rest)
            assertTrue(rest.contains("Paired: herdr-android@test can log in here"), rest)
            assertEquals(listOf(phone.publicKeyLine), keys.readLines(), "only the phone's key is left")

            // The code is spent: a second phone can't use it.
            val second = assertFailsWith<PairError> { Pairing.pair(offer, reached, KeyGen.ed25519("thief"), "m2") }
            assertTrue(second.message!!.contains("didn't take the code"), second.message)
            // And a code for another machine's host key is refused before anything is sent.
            val elsewhere = PairOffer.parse(link!!.replace(Regex("k=[^&]+"), "k=$fpUrl"))!!
            assertTrue(assertFailsWith<PairError> { Pairing.reach(elsewhere) }.message!!.contains("another host key"))
        } finally {
            pair?.destroy()
            server?.destroy()
            server?.waitFor(5, TimeUnit.SECONDS)
            dir.deleteRecursively()
        }
    }
}
