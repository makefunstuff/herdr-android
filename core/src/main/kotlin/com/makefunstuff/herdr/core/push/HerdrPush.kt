package com.makefunstuff.herdr.core.push

import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.api.HerdrApi
import com.makefunstuff.herdr.core.api.SessionSnapshot
import com.makefunstuff.herdr.core.board.BoardClient
import com.makefunstuff.herdr.core.board.BoardTask
import com.makefunstuff.herdr.core.ssh.HerdrCommands.quote
import com.makefunstuff.herdr.core.ssh.MachineConnection
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.Base64

/**
 * herdr-push (host/herdr-push) on a machine: the app copies it there over SSH, subscribes this phone
 * and starts it, so the machine can tell the phone about its agents and tasks while the app is closed.
 */
object HerdrPush {
    /** Where the app puts it on a machine. */
    const val DIR = "\$HOME/.local/share/herdr-push"
    const val FILE = "$DIR/herdr-push"

    /** The watcher the app carries (core's resource, copied from host/herdr-push/herdr-push). */
    val script: String by lazy {
        HerdrPush::class.java.getResourceAsStream("/herdr-push")?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("herdr-push isn't bundled")
    }

    /** The `# version: N` line near the top of a herdr-push script. */
    fun versionOf(script: String): String =
        Regex("^# version: (\\S+)$", RegexOption.MULTILINE).find(script.lineSequence().take(5).joinToString("\n"))?.groupValues?.get(1).orEmpty()

    val version: String by lazy { versionOf(script) }

    /** Writes stdin to the machine as herdr-push, replacing an older one in one step. */
    fun installCommand(): String = "sh -c " + quote(
        "mkdir -p \"$DIR\" && cat > \"$FILE.new\" && chmod 755 \"$FILE.new\" && mv -f \"$FILE.new\" \"$FILE\"",
    )

    const val NO_PYTHON_EXIT = 127

    /**
     * `herdr-push <args>`, run by python3 (found on the PATH, or by a login shell as the board's
     * launcher is). Exits [NO_PYTHON_EXIT] without python3, [MISSING_EXIT] before herdr-push is there.
     */
    fun command(vararg args: String): String = "sh -c " + quote(
        "[ -f \"$FILE\" ] || exit $MISSING_EXIT; " +
            "P=\"\$(command -v python3 2>/dev/null)\"; " +
            "[ -n \"\$P\" ] || P=\"\$(\"\${SHELL:-/bin/sh}\" -lc 'command -v python3' </dev/null 2>/dev/null | tail -n 1)\"; " +
            "[ -n \"\$P\" ] || { echo 'python3 is not installed' >&2; exit $NO_PYTHON_EXIT; }; " +
            "exec \"\$P\" \"$FILE\" \"\$@\"",
    ) + " sh" + args.joinToString("") { " " + quote(it) }

    const val MISSING_EXIT = 126

    /**
     * This phone's subscription for one machine in the app. Per machine, not per phone: two machines in
     * the app can be one host (two herdr sessions, or the same host by two names), each with its own
     * session and key, and one subscription mustn't replace the other.
     */
    fun subscriberId(phoneId: String, machineId: String): String = "$phoneId:$machineId"

    /** What `herdr-push subscribe` reads: this subscription ([subscriberId]), its push address and its key for this machine. */
    fun subscription(
        id: String,
        phoneName: String,
        endpoint: String,
        key: ByteArray,
        session: String,
        herdrPath: String?,
        boardPath: String?,
        live: Boolean = false,
    ): String = buildJsonObject {
        put("id", id)
        put("name", phoneName)
        put("endpoint", endpoint)
        put("key", Base64.getEncoder().encodeToString(key))
        put("session", session.ifEmpty { "default" })
        herdrPath?.takeIf { it.isNotBlank() }?.let { put("herdr", it) }
        boardPath?.takeIf { it.isNotBlank() }?.let { put("board", it) }
        // Live Updates: also what is running, as it changes.
        if (live) put("live", true)
    }.toString()

    /** herdr-push's answer: its last JSON line with an `ok`. */
    fun answer(stdout: String): JsonObject? = stdout.lines().asReversed().firstNotNullOfOrNull { l ->
        l.trim().takeIf { it.startsWith("{") }?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?.takeIf { it["ok"] != null }
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** What `status` says about this phone and the watcher. */
    data class Status(val version: String, val running: Boolean, val autostart: String, val subscribed: Boolean, val error: String?, val gone: Boolean)

    /** How the watcher stands for subscription [id] ([subscriberId]). */
    fun status(o: JsonObject, id: String): Status {
        val phone = (o["phones"] as? kotlinx.serialization.json.JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }.firstOrNull { it.str("id") == id }
        return Status(
            version = o.str("version"),
            running = (o["running"] as? JsonPrimitive)?.booleanOrNull == true,
            autostart = o.str("autostart"),
            subscribed = phone != null,
            error = phone?.str("error")?.ifEmpty { null },
            gone = (phone?.get("gone") as? JsonPrimitive)?.booleanOrNull == true,
        )
    }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

    /** herdr-push on one machine, over the app's SSH connection. */
    class Client(private val conn: MachineConnection, private val label: String) {
        /** Copies the app's herdr-push to the machine. */
        suspend fun install() {
            val r = conn.runWithInput(installCommand(), script.byteInputStream(), timeoutSeconds = 30)
            if (r.exitStatus != 0) throw IOException(problem(r.stderr, "Couldn't copy herdr-push to $label"))
        }

        /** Runs herdr-push; its JSON answer, or the reason it failed. [MissingException] before it is installed. */
        suspend fun run(vararg args: String, input: String? = null, timeoutSeconds: Long = 60): JsonObject {
            val r = if (input != null) conn.runWithInput(command(*args), input.byteInputStream(), timeoutSeconds)
            else conn.run(command(*args), timeoutSeconds)
            if (r.exitStatus == MISSING_EXIT) throw MissingException()
            if (r.exitStatus == NO_PYTHON_EXIT && answer(r.stdout) == null) {
                throw IOException("herdr-push needs python3 on $label, and it isn't installed")
            }
            val o = answer(r.stdout) ?: throw IOException(problem(r.stderr, "herdr-push gave no answer on $label"))
            if ((o["ok"] as? JsonPrimitive)?.booleanOrNull != true) throw IOException(o.str("msg").ifEmpty { "herdr-push refused on $label" })
            return o
        }

        private fun problem(stderr: String, fallback: String) = stderr.trim().lines().lastOrNull { it.isNotBlank() } ?: fallback
    }

    class MissingException : IOException("herdr-push isn't installed")
}

/** The app's own look at a machine while it is closed: what herdr-push would have sent about it. */
object PushLook {
    /** Every agent and, with [board], every task, as events; the notifications sort out what is new. */
    suspend fun events(api: HerdrApi, snapshot: SessionSnapshot, board: BoardClient?): List<PushEvent> =
        events(api, snapshot, board?.let { tasks(it) })

    /** The board's tasks, or null when it didn't answer. */
    suspend fun tasks(board: BoardClient): List<BoardTask>? =
        runCatching { board.tasks() }.getOrElse { if (it is CancellationException) throw it; null }

    suspend fun events(api: HerdrApi, snapshot: SessionSnapshot, tasks: List<BoardTask>?): List<PushEvent> {
        val agents = snapshot.agents.map { a ->
            val screen = if (a.agentStatus == AgentStatus.Blocked) {
                try {
                    api.readPane(a.paneId, source = "visible", lines = 60).text
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    null
                }
            } else null
            PushEvent.Agent(a, snapshot.location(a.workspaceId, a.tabId), screen)
        }
        return agents + tasks.orEmpty().map { PushEvent.Task(it) }
    }
}
