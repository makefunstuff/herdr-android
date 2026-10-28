package com.makefunstuff.herdr.core.shepherdr

import com.makefunstuff.herdr.core.ssh.CommandResult
import com.makefunstuff.herdr.core.ssh.Duplex
import com.makefunstuff.herdr.core.ssh.HerdrCommands.quote
import com.makefunstuff.herdr.core.ssh.PortForward
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

    /** Where there is no live link ([canStream] false): chat [key] as a `chat` event's JSON, read some other way. */
    suspend fun readChat(key: String, timeoutMs: Long): String = throw IOException("No way to read a chat here")
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

    override suspend fun http(method: String, path: String, body: String?, timeoutMs: Int): HttpReply = coroutineScope {
        val stream = withContext(Dispatchers.IO) { connect(timeoutMs) }
        // Its failure comes back as a result: once cancelled, the closed socket's error is no news.
        val answer = async(Dispatchers.IO) { runCatching { Wire.exchange(stream, method, path, body) } }
        try {
            answer.await().getOrThrow()
        } catch (e: CancellationException) {
            // A socket's read can't be interrupted: closing it ends the wait (a merge waiting on checks).
            runCatching { stream.close() }
            throw e
        }
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
        throw IOException("The machine's SSH server doesn't forward ports: no live link, so the app reads again every few seconds")

    /**
     * Shepherdr sends chats only over its live link, which curl can't hold: the machine's `bun` (which
     * the `shepherdr` command itself runs on) opens one there, prints the chat once and quits.
     */
    override suspend fun readChat(key: String, timeoutMs: Long): String {
        val seconds = ((timeoutMs + 999) / 1000).toInt().coerceAtLeast(1)
        val r = run(chatCommand(key, seconds), "", seconds + 10L)
        val line = r.stdout.lines().lastOrNull { it.startsWith(CHAT_MARK) }
            ?: throw IOException(r.stderr.trim().lines().lastOrNull { it.isNotBlank() } ?: "Shepherdr sent no chat $key")
        return line.removePrefix(CHAT_MARK)
    }

    override fun close() {}

    companion object {
        private const val MARK = "@@shepherdr-status@@"
        private const val CHAT_MARK = "@@shepherdr-chat@@"

        /**
         * A few lines of JavaScript for `bun` (or `node`, which has WebSocket too) through a login shell,
         * where PATH finds them: watch [key], print its first chat event after [CHAT_MARK], quit.
         */
        fun chatCommand(key: String, seconds: Int): String {
            val k = kotlinx.serialization.json.JsonPrimitive(key).toString()
            val js = "const k=$k;const ws=new WebSocket('ws://${Wire.HOST}/api/ws');" +
                "setTimeout(()=>{console.error('Shepherdr sent no chat '+k);process.exit(3)},${seconds * 1000});" +
                "ws.onopen=()=>ws.send(JSON.stringify({type:'watch',chats:[k],term:null,visible:false,push:null}));" +
                "ws.onmessage=(e)=>{const m=JSON.parse(String(e.data));" +
                "if(m.type==='chat'&&m.chat&&m.chat.key===k)process.stdout.write('$CHAT_MARK'+JSON.stringify(m)+'\\n',()=>process.exit(0))};" +
                "ws.onerror=()=>{console.error('Shepherdr refused the live link');process.exit(4)}"
            val run = "if command -v bun >/dev/null 2>&1; then exec bun -e ${quote(js)}; else exec node -e ${quote(js)}; fi"
            return "sh -c ${quote("exec \"\${SHELL:-/bin/sh}\" -lc \"\$1\" </dev/null")} sh ${quote(run)}"
        }

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
