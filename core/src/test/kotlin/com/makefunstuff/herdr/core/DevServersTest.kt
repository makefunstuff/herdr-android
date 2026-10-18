package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.ports.DevServers
import com.makefunstuff.herdr.core.ports.ListeningPort
import com.makefunstuff.herdr.core.ssh.CommandResult
import com.makefunstuff.herdr.core.ssh.Duplex
import com.makefunstuff.herdr.core.ssh.PortForward
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Dev servers: what the ports probe prints and how it reads, which belong to a workspace, and the forward. */
class DevServersTest {
    private val printed = """
        @@herdr-ports 1
        L	5173	127.0.0.1	4101	node	w1	w1:p3	/home/demo/src/site	1	node /home/demo/src/site/node_modules/.bin/vite --port 5173
        L	5173	::1	4101	node	w1	w1:p3	/home/demo/src/site	1	node /home/demo/src/site/node_modules/.bin/vite --port 5173
        L	8000	0.0.0.0	4120	python3			/home/demo/src/api-server	1	/usr/bin/python3 -m uvicorn app:api --reload
        L	6006	*	4133	node	w2		/home/demo/src/ui	1	node /home/demo/src/ui/node_modules/.bin/storybook dev -p 6006
        L	41234	127.0.0.1	4200	java	w1		/home/demo/src/site	0	/usr/lib/jvm/bin/java -cp gradle.jar org.gradle.launcher.daemon.bootstrap.GradleDaemon 8.10
        L	22	0.0.0.0
        L	3000	::	4300	node	w1		/home/demo/src/site		node server.js
    """.trimIndent()

    @Test
    fun readsWhatListens() {
        val ports = DevServers.read(printed)
        assertEquals(listOf(22, 3000, 5173, 6006, 8000, 41234), ports.map { it.port })
        val vite = ports.first { it.port == 5173 }
        assertEquals("127.0.0.1", vite.address, "IPv4 and IPv6 of one port are one")
        assertEquals("vite", vite.name)
        assertEquals("w1", vite.workspaceId)
        assertEquals("w1:p3", vite.paneId)
        assertEquals(true, vite.web)
        assertEquals("uvicorn", ports.first { it.port == 8000 }.name)
        assertEquals("storybook", ports.first { it.port == 6006 }.name)
        assertEquals("gradle", ports.first { it.port == 41234 }.name)
        assertEquals("server", ports.first { it.port == 3000 }.name, "a runtime goes by its script")
        assertEquals(null, ports.first { it.port == 3000 }.web, "not asked")
        assertEquals("::1", ports.first { it.port == 3000 }.connectHost)
        assertEquals("127.0.0.1", ports.first { it.port == 6006 }.connectHost)
        assertEquals(null, ports.first { it.port == 22 }.pid)
    }

    @Test
    fun aWorkspaceHasItsOwn() {
        val ports = DevServers.read(printed)
        assertEquals(listOf(3000, 5173, 41234), DevServers.inWorkspace(ports, "w1").map { it.port }, "web first, what doesn't answer last")
        // A process started outside herdr counts by its folder.
        assertEquals(listOf(8000), DevServers.inWorkspace(ports, "w9", listOf("/home/demo/src/api-server/")).map { it.port })
        assertEquals(listOf(), DevServers.inWorkspace(ports, "w9", listOf("/home/demo/src/api")).map { it.port }, "a folder, not a prefix")
        assertEquals(listOf(3000, 5173, 6006, 8000, 41234), DevServers.own(ports).map { it.port })
    }

    @Test
    fun namesServers() {
        assertEquals("next", DevServers.name("node", "node /x/node_modules/.bin/next dev"))
        assertEquals("django", DevServers.name("python3", "python3 manage.py runserver 0.0.0.0:8000"))
        assertEquals("http.server", DevServers.name("python3", "python3 -m http.server 8080"))
        assertEquals("app", DevServers.name("python3.12", "python3.12 app.py"))
        assertEquals("caddy", DevServers.name("caddy", "caddy run"))
        assertEquals("rails", DevServers.name("ruby", "puma 6.4.2 (tcp://localhost:3000) [shop]"))
    }

    @Test
    fun aFailedProbeSaysWhy() = runBlocking {
        val e = assertFailsWith<java.io.IOException> { DevServers.list { _, _ -> CommandResult(127, "", "sh: not found\n") } }
        assertEquals("sh: not found", e.message)
        assertEquals(listOf(), DevServers.list { _, _ -> CommandResult(0, "@@herdr-ports 1\n", "") })
    }

    /** The probe itself, on this machine: a port the test opens shows, with its process. */
    @Test
    fun probeFindsAListeningPort() = runBlocking {
        val ss = listOf("/usr/bin/ss", "/usr/sbin/ss", "/bin/ss").any { java.io.File(it).canExecute() }
        assumeTrue(ss && java.io.File("/proc/self/environ").exists(), "ss and /proc are needed")
        ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            val ports = DevServers.list { command, timeout ->
                val p = ProcessBuilder("sh", "-c", command).start()
                p.outputStream.close()
                val out = p.inputStream.bufferedReader().readText()
                val err = p.errorStream.bufferedReader().readText()
                p.waitFor(timeout, TimeUnit.SECONDS)
                CommandResult(p.exitValue(), out, err)
            }
            val mine = ports.firstOrNull { it.port == server.localPort }
            assertTrue(mine != null, "port ${server.localPort} in $ports")
            assertEquals(ProcessHandle.current().pid().toInt(), mine.pid)
            assertEquals("127.0.0.1", mine.address)
            assertTrue(mine.args.contains("java"), mine.args)
        }
    }

    /** A port of the phone reaches the machine's: here a little HTTP server, through plain sockets for SSH channels. */
    @Test
    fun forwardsAPort() {
        ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1")).use { web ->
            thread(isDaemon = true) {
                while (true) {
                    val s = runCatching { web.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) {
                        s.use {
                            val reader = it.getInputStream().bufferedReader()
                            val first = reader.readLine()
                            while (reader.readLine()?.isNotEmpty() == true) Unit
                            val body = "you asked for $first"
                            it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
                        }
                    }
                }
            }
            val opened = mutableListOf<String>()
            val opener = { host: String, port: Int ->
                synchronized(opened) { opened += "$host:$port" }
                val s = Socket(host, port)
                object : Duplex {
                    override val input: InputStream = s.getInputStream()
                    override val output: OutputStream = s.getOutputStream()
                    override fun close() = s.close()
                }
            }
            PortForward("127.0.0.1", web.localPort, preferred = 0, open = opener).use { fwd ->
                assertNotEquals(web.localPort, fwd.localPort)
                repeat(3) { i ->
                    val c = URI("http://localhost:${fwd.localPort}/page$i").toURL().openConnection() as HttpURLConnection
                    assertEquals(200, c.responseCode)
                    assertEquals("you asked for GET /page$i HTTP/1.1", c.inputStream.bufferedReader().readText())
                }
                assertEquals(List(3) { "127.0.0.1:${web.localPort}" }, opened, "one channel a connection")
            }
            // The machine's own port number when it is free on the phone.
            val free = ServerSocket(0).use { it.localPort }
            PortForward("127.0.0.1", web.localPort, preferred = free, open = opener).use { assertEquals(free, it.localPort) }
            // Taken: any other.
            ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1")).use { taken ->
                PortForward("127.0.0.1", web.localPort, preferred = taken.localPort, open = opener).use { assertNotEquals(taken.localPort, it.localPort) }
            }
        }
    }

    /** The same through a real sshd (one started for the test): `direct-tcpip` channels of one connection. */
    @Test
    fun forwardsThroughSsh() = runBlocking {
        val sshd = listOf("/usr/sbin/sshd", "/usr/bin/sshd").firstOrNull { java.io.File(it).canExecute() }
        assumeTrue(sshd != null, "sshd is needed")
        val dir = java.nio.file.Files.createTempDirectory("herdr-forward-sshd").toFile()
        var server: Process? = null
        try {
            val hostKey = java.io.File(dir, "ssh_host_ed25519_key")
            assertEquals(0, ProcessBuilder("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", hostKey.path).start().waitFor())
            val phone = com.makefunstuff.herdr.core.keys.KeyGen.ed25519("herdr-android@test")
            val keys = java.io.File(dir, "authorized_keys").apply { writeText(phone.publicKeyLine + "\n") }
            val port = ServerSocket(0).use { it.localPort }
            java.io.File(dir, "sshd_config").writeText(
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
                AllowTcpForwarding yes
                """.trimIndent() + "\n",
            )
            server = ProcessBuilder(sshd, "-D", "-e", "-f", java.io.File(dir, "sshd_config").path).redirectErrorStream(true)
                .redirectOutput(java.io.File(dir, "sshd.log")).start()
            val up = (1..50).any { runCatching { Socket("127.0.0.1", port).close() }.isSuccess || run { Thread.sleep(100); false } }
            assumeTrue(up, "sshd didn't start: " + java.io.File(dir, "sshd.log").readText())
            val machine = com.makefunstuff.herdr.core.ssh.Machine("m1", "devbox", "127.0.0.1", port, System.getProperty("user.name"))
            com.makefunstuff.herdr.core.ssh.SshConnector.connect(machine, phone).use { conn ->
                val web = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
                web.createContext("/") { ex ->
                    val body = "page ${ex.requestURI} for ${ex.requestHeaders.getFirst("Host")}".toByteArray()
                    ex.sendResponseHeaders(200, body.size.toLong())
                    ex.responseBody.use { it.write(body) }
                }
                web.start()
                try {
                    conn.forward("127.0.0.1", web.address.port).use { fwd ->
                        // The machine is this computer here, so its port number is taken: another one.
                        assertNotEquals(web.address.port, fwd.localPort)
                        repeat(2) { i ->
                            val c = URI("http://localhost:${fwd.localPort}/a$i").toURL().openConnection() as HttpURLConnection
                            assertEquals("page /a$i for localhost:${fwd.localPort}", c.inputStream.bufferedReader().readText())
                        }
                    }
                } finally {
                    web.stop(0)
                }
            }
        } finally {
            server?.destroy()
            server?.waitFor(5, TimeUnit.SECONDS)
            dir.deleteRecursively()
        }
    }

    @Test
    fun aRefusedChannelClosesTheConnection() {
        PortForward("127.0.0.1", 9, preferred = 0, open = { _, _ -> throw java.io.IOException("refused") }).use { fwd ->
            Socket("127.0.0.1", fwd.localPort).use { s ->
                s.soTimeout = 5000
                assertEquals(-1, s.getInputStream().read())
            }
        }
    }

    @Suppress("unused")
    private fun port(n: Int) = ListeningPort(n, "127.0.0.1")
}
