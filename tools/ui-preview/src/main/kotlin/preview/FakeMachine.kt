// A herdr machine without SSH: the stand-in SshConnector hands the app's real code one of these.
// It answers herdr's socket API, the shell commands the app runs (the herdr probe, session list,
// the work board's CLI) and the streams it opens (an agent's transcript, a terminal), from the
// scene's fixed demo data, so every scene renders the same way each time.
package preview

import com.makefunstuff.herdr.core.ssh.CommandResult
import com.makefunstuff.herdr.core.ssh.ConnectError
import com.makefunstuff.herdr.core.ssh.Duplex
import com.makefunstuff.herdr.core.ssh.Machine
import com.makefunstuff.herdr.core.ssh.RemoteProcess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.util.Base64
import java.util.concurrent.Executors

/** The fake machines of the scene being rendered, by machine id. */
object FakeMachines {
    @Volatile var machines: Map<String, FakeMachine> = emptyMap()

    /** Every stream opened in this scene, closed when it ends so no reader is left blocked. */
    private val open = java.util.Collections.synchronizedSet(HashSet<RemoteProcess>())

    fun track(p: RemoteProcess) = p.also { open += it }

    fun closeAll() {
        val all = synchronized(open) { open.toList().also { open.clear() } }
        all.forEach { runCatching { it.close() } }
    }

    fun connect(machine: Machine): FakeMachine {
        val m = machines[machine.id] ?: throw ConnectError.Unreachable(machine.target, IOException("No route to host"))
        m.refuse?.let { throw it }
        return m
    }
}

/** What one machine has and answers. Scenes change fields, or answer single API methods with [on]. */
class FakeMachine(
    val id: String,
    var snapshot: JsonObject = obj(),
    /** Each pane's screen, with its colours (ANSI). Agents' screens are also what `pane.read` gives. */
    var screens: Map<String, String> = emptyMap(),
    /** Agent session id → transcript (JSON lines). */
    var transcripts: Map<String, String> = emptyMap(),
    /** The work board's data, or null when herdr-projects isn't installed there. */
    var board: FakeBoard? = null,
    /** Integration metadata per workspace (`workspace.get`'s tokens). */
    var workspaceTokens: Map<String, Map<String, String>> = emptyMap(),
    var version: String = "0.9.0",
    /** Set to make connecting fail, e.g. [ConnectError.Auth]. */
    var refuse: ConnectError? = null,
) {
    val fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(id.toByteArray()).padEnd(43, 'A')
    private val overrides = HashMap<String, (JsonObject) -> JsonElement>()

    /** Answers [method] with [answer]'s result (or throw [ApiError] for an error). */
    fun on(method: String, answer: (JsonObject) -> JsonElement) { overrides[method] = answer }

    // ---- shell commands -------------------------------------------------------------------------

    fun run(command: String): CommandResult {
        val w = Shell.words(command)
        return when {
            "pgrep" in command -> ok("server\t/home/demo/.local/bin/herdr\therdr $version\n")
            w.containsAll(listOf("session", "list", "--json")) ->
                ok("""{"sessions":[{"name":"default","default":true,"running":true,"socket_path":"/home/demo/.config/herdr/herdr.sock"}]}""")
            "command -v herdr-projects" in command -> board?.let { ok(BOARD_PATH + "\n") } ?: CommandResult(127, "", "")
            BOARD_PATH in command -> board?.run(command) ?: CommandResult(127, "", "herdr-projects: not found")
            w.containsAll(listOf("machine", "list", "--json")) -> ok("""{"machines":[]}""")
            "--version" in w -> ok("herdr $version\n")
            w.containsAll(listOf("channel", "show")) -> ok("stable\n")
            // An attached file (HostSession.upload): taken, and its path there printed, under a fixed stamp.
            "herdr-android/uploads/" in command -> ok("/home/demo/.cache/herdr-android/uploads/20261017-091204-3f2a/" + Shell.words(command.substringAfterLast("\"/")).first())
            else -> ok("")
        }
    }

    private fun ok(out: String) = CommandResult(0, out, "")

    // ---- streams ----------------------------------------------------------------------------------

    fun start(command: String): RemoteProcess {
        val w = Shell.words(command)
        return when {
            "@@missing" in command -> stream { _, out ->
                // TranscriptFollower: kind, value, agent, bytes after "follow".
                val args = w.drop(w.indexOf("follow") + 1)
                val text = transcripts[args.getOrNull(1)]
                if (text == null) out.println("@@missing") else {
                    out.println("@@file ${text.toByteArray().size} /home/demo/transcript.jsonl")
                    out.print(text.trimEnd() + "\n")
                }
                out.flush()
            }
            w.containsAll(listOf("terminal", "session", "control")) -> stream { _, out ->
                val pane = w[w.indexOf("control") + 1]
                val cols = w.getOrNull(w.indexOf("--cols") + 1)?.toIntOrNull() ?: 80
                val rows = w.getOrNull(w.indexOf("--rows") + 1)?.toIntOrNull() ?: 24
                val screen = "\u001b[H\u001b[2J" + screens[pane].orEmpty().replace("\n", "\r\n")
                out.println(
                    obj(
                        "type" to "terminal.frame", "seq" to "1", "width" to cols, "height" to rows, "full" to true,
                        "bytes" to Base64.getEncoder().encodeToString(screen.toByteArray()),
                    ),
                )
                out.flush()
            }
            else -> stream { _, out -> out.flush() }
        }
    }

    /** herdr's API socket: one request per connection; `events.subscribe` stays open. */
    fun socket(): Duplex = stream { input, out ->
        val line = input.readLine() ?: return@stream
        val request = Json.parseToJsonElement(line).jsonObject
        val rid = request["id"] ?: JsonNull
        val method = request["method"]?.jsonPrimitive?.content.orEmpty()
        val params = request["params"] as? JsonObject ?: obj()
        val reply = try {
            obj("id" to rid, "result" to answer(method, params))
        } catch (e: ApiError) {
            obj("id" to rid, "error" to obj("code" to e.code, "message" to e.message))
        }
        out.println(reply)
        out.flush()
    }

    private fun answer(method: String, p: JsonObject): JsonElement {
        overrides[method]?.let { return it(p) }
        val pane = p["pane_id"]?.jsonPrimitive?.content
        return when (method) {
            "ping" -> obj("version" to version, "protocol" to 4, "capabilities" to obj("live_handoff" to true, "health_check" to true))
            "session.snapshot" -> obj("snapshot" to snapshot)
            "events.subscribe" -> obj()
            "agent.list" -> obj("agents" to snapshot["agents"])
            "pane.read" -> obj("read" to obj("pane_id" to pane, "text" to Shell.plain(screens[pane].orEmpty()), "revision" to 1))
            "pane.get" -> obj("pane" to list("panes").first { it["pane_id"]?.jsonPrimitive?.content == pane })
            "pane.layout" -> {
                val tab = list("panes").first { it["pane_id"]?.jsonPrimitive?.content == pane }["tab_id"]
                obj("layout" to list("layouts").first { it["tab_id"] == tab })
            }
            "workspace.get" -> {
                val ws = p["workspace_id"]?.jsonPrimitive?.content
                val info = list("workspaces").first { it["workspace_id"]?.jsonPrimitive?.content == ws }
                obj("workspace" to JsonObject(info + ("tokens" to obj(*workspaceTokens[ws].orEmpty().toList().toTypedArray()))))
            }
            "plugin.list" -> obj("plugins" to JsonArray(emptyList()))
            "plugin.action.list" -> obj("actions" to JsonArray(emptyList()))
            "plugin.log.list" -> obj("logs" to JsonArray(emptyList()))
            "integration.list" -> obj("integrations" to JsonArray(emptyList()))
            "server.agent_manifests" -> obj("manifests" to JsonArray(emptyList()))
            else -> if (method.startsWith("pane.") || method.startsWith("tab.") || method.startsWith("workspace.") ||
                method.startsWith("agent.")
            ) obj() else throw ApiError("unknown_method", "unknown method $method")
        }
    }

    private fun list(key: String): List<JsonObject> = (snapshot[key] as? JsonArray).orEmpty().map { it.jsonObject }

    companion object {
        const val BOARD_PATH = "/home/demo/.herdr-projects/data/bin/herdr-projects"
    }
}

class ApiError(val code: String, override val message: String) : Exception(message)

/**
 * A process on the fake machine: [body] reads its stdin and writes its stdout on a thread of its
 * own, and the stream then stays open, as a remote tail or terminal does, until the app closes it.
 */
private fun stream(body: (BufferedReader, PrintStream) -> Unit): RemoteProcess {
    val toApp = Pipe()
    val fromApp = Pipe()
    val out = PrintStream(toApp.output, false, Charsets.UTF_8)
    val task = Threads.pool.submit {
        runCatching {
            val reader = fromApp.input.bufferedReader(Charsets.UTF_8)
            body(reader, out)
            out.flush()
            // Keep the stream open while the app holds it.
            while (reader.readLine() != null) Unit
        }
        toApp.close()
    }
    return FakeMachines.track(RemoteProcess(
        stdin = fromApp.output,
        stdout = toApp.input,
        stderr = InputStream.nullInputStream(),
        onClose = {
            fromApp.close()
            toApp.close()
            task.cancel(true)
        },
    ))
}

/** A byte pipe between two threads, without java.io's piped streams' ties to the threads that use them. */
private class Pipe {
    private val lock = Object()
    private val buffer = java.io.ByteArrayOutputStream()
    private var readAt = 0
    private var closed = false

    fun close() = synchronized(lock) { closed = true; lock.notifyAll() }

    val output = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) = synchronized(lock) {
            if (closed) throw IOException("closed")
            buffer.write(b, off, len)
            lock.notifyAll()
        }
        override fun close() = this@Pipe.close()
    }

    val input = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int = synchronized(lock) {
            while (readAt >= buffer.size() && !closed) lock.wait()
            if (readAt >= buffer.size()) return -1
            val bytes = buffer.toByteArray()
            val n = minOf(len, bytes.size - readAt)
            System.arraycopy(bytes, readAt, b, off, n)
            readAt += n
            n
        }

        override fun available(): Int = synchronized(lock) { buffer.size() - readAt }
        override fun close() = this@Pipe.close()
    }
}

private object Threads {
    val pool = Executors.newCachedThreadPool { r -> Thread(r, "fake-machine").apply { isDaemon = true } }
}

/** Just enough of a POSIX shell's word splitting to read the commands the app builds. */
object Shell {
    fun words(command: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var inWord = false
        var i = 0
        while (i < command.length) {
            val c = command[i]
            when {
                c == '\'' -> {
                    val end = command.indexOf('\'', i + 1).let { if (it < 0) command.length else it }
                    cur.append(command, i + 1, end); inWord = true; i = end
                }
                c == '"' -> {
                    i++
                    while (i < command.length && command[i] != '"') {
                        if (command[i] == '\\' && i + 1 < command.length) i++
                        cur.append(command[i]); i++
                    }
                    inWord = true
                }
                c == '\\' && i + 1 < command.length -> { cur.append(command[i + 1]); inWord = true; i++ }
                c == ';' -> { if (inWord) out += cur.toString(); cur.clear(); inWord = false; out += ";" }
                c.isWhitespace() -> { if (inWord) out += cur.toString(); cur.clear(); inWord = false }
                else -> { cur.append(c); inWord = true }
            }
            i++
        }
        if (inWord) out += cur.toString()
        return out
    }

    private val ANSI = Regex("\u001b\\[[0-9;?]*[ -/]*[@-~]")

    /** A screen as `pane.read` gives it: text without colours. */
    fun plain(screen: String): String = screen.replace(ANSI, "")
}

// ---- JSON helpers for the demo data --------------------------------------------------------------

fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.filter { it.second != null }.associate { (k, v) -> k to json(v) })

fun arr(vararg items: Any?): JsonArray = JsonArray(items.map(::json))

fun json(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to json(x) })
    is List<*> -> JsonArray(v.map(::json))
    else -> error("not JSON: $v")
}
