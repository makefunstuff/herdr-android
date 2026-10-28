package com.makefunstuff.herdr.core.shepherdr

import com.makefunstuff.herdr.core.ssh.CommandResult
import com.makefunstuff.herdr.core.ssh.Duplex
import com.makefunstuff.herdr.core.ssh.HerdrCommands.quote
import com.makefunstuff.herdr.core.ssh.PortForward
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/** How the app reaches Shepherdr's API on a machine: always through its SSH connection, never an open port. */
interface ShepherdrTransport : Closeable {
    /** Whether it can hold the live link ([webSocket]); curl over exec can't. */
    val canStream: Boolean

    suspend fun http(method: String, path: String, body: String? = null, timeoutMs: Int = 30_000): HttpReply

    /** Opens the live link at [path]; fails where [canStream] is false. */
    suspend fun webSocket(path: String): WebSocketLink
}

/**
 * Through an SSH port forward (`ssh -L`) to the machine's 127.0.0.1:7787: each request is a fresh
 * loopback socket to the forward's port, the live link one more. Closing it closes the forward.
 */
class ForwardTransport(
    private val connect: (timeoutMs: Int) -> Duplex,
    private val onClose: () -> Unit = {},
) : ShepherdrTransport {
    override val canStream: Boolean get() = true

    override suspend fun http(method: String, path: String, body: String?, timeoutMs: Int): HttpReply = withContext(Dispatchers.IO) {
        Wire.exchange(connect(timeoutMs), method, path, body)
    }

    override suspend fun webSocket(path: String): WebSocketLink = withContext(Dispatchers.IO) {
        Wire.webSocket(connect(0), path)
    }

    override fun close() = onClose()

    companion object {
        /** Over [forward], which this transport then owns. */
        fun over(forward: PortForward): ForwardTransport = ForwardTransport({ timeoutMs -> socket(forward.localPort, timeoutMs) }, forward::close)

        /** A loopback socket to [port]; [timeoutMs] bounds every read (0: none, for the live link). */
        fun socket(port: Int, timeoutMs: Int): Duplex {
            val s = Socket()
            try {
                s.connect(InetSocketAddress("127.0.0.1", port), 10_000)
                s.soTimeout = timeoutMs
                s.tcpNoDelay = true
            } catch (t: Throwable) {
                runCatching { s.close() }
                throw t
            }
            return object : Duplex {
                override val input: InputStream = s.getInputStream()
                override val output: OutputStream = s.getOutputStream()
                override fun close() = s.close()
            }
        }
    }
}

/**
 * The fallback where the SSH server forbids forwarding: `curl` on the machine, one process a request.
 * No live link: the screens poll the snapshot instead.
 */
class CurlTransport(
    /** Runs a command on the machine with [stdin] as its input. */
    private val run: suspend (command: String, stdin: String, timeoutSeconds: Long) -> CommandResult,
) : ShepherdrTransport {
    override val canStream: Boolean get() = false

    override suspend fun http(method: String, path: String, body: String?, timeoutMs: Int): HttpReply {
        val seconds = if (timeoutMs <= 0) 600 else (timeoutMs + 999) / 1000
        val r = run(command(method, path, body != null, seconds), body.orEmpty(), seconds + 5L)
        if (r.exitStatus == 127) throw IOException("curl isn't installed on the machine, and its SSH server doesn't forward ports")
        return parse(r)
    }

    override suspend fun webSocket(path: String): WebSocketLink =
        throw IOException("The machine's SSH server doesn't forward ports: no live link, only reads")

    override fun close() {}

    companion object {
        private const val MARK = "@@shepherdr-status@@"

        /** `curl` with the body on its stdin and the status after a mark at the end of its output. */
        fun command(method: String, path: String, hasBody: Boolean, seconds: Int): String = buildList {
            add("curl"); add("-sS"); add("--max-time"); add(seconds.toString())
            add("-X"); add(method)
            if (hasBody) { add("-H"); add("Content-Type: application/json"); add("--data-binary"); add("@-") }
            add("-w"); add("\n$MARK%{http_code}")
            add("http://${Wire.HOST}$path")
        }.joinToString(" ") { quote(it) }

        fun parse(r: CommandResult): HttpReply {
            val at = r.stdout.lastIndexOf("\n$MARK")
            if (at < 0) throw IOException(r.stderr.trim().lines().lastOrNull { it.isNotBlank() } ?: "curl gave no answer")
            val status = r.stdout.substring(at + MARK.length + 1).trim().toIntOrNull() ?: 0
            if (status == 0) throw IOException(r.stderr.trim().lines().lastOrNull { it.isNotBlank() } ?: "Shepherdr didn't answer")
            return HttpReply(status, r.stdout.substring(0, at))
        }

        fun stdin(text: String): InputStream = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))
    }
}
