package com.makefunstuff.herdr.core.board

import com.makefunstuff.herdr.core.ssh.HerdrCommands.quote
import com.makefunstuff.herdr.core.ssh.Machine
import com.makefunstuff.herdr.core.ssh.MachineConnection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.Base64

/**
 * herdr-projects (the work board) on a machine, reached the way another machine's board reaches it:
 * `herdr-projects remote list` for where its Coordinator stands and what was said, and
 * `herdr-projects remote dispatch` for a message, which goes into the same queue as the words typed
 * in the board's own box.
 */
object BoardCommands {
    const val NOT_FOUND_EXIT = 127

    /**
     * Where the board's launcher can be. The board writes it at `~/.herdr-projects/data/bin` on every
     * machine; a launcher on the PATH, or one only a login shell finds, also counts.
     */
    private val LOCATE = "for c in \"\$HOME/.herdr-projects/data/bin/herdr-projects\" " +
        "\"\$(command -v herdr-projects 2>/dev/null)\" \"\$HOME/.local/bin/herdr-projects\"; do " +
        "if [ -n \"\$c\" ] && [ -x \"\$c\" ]; then echo \"\$c\"; exit 0; fi; done; " +
        "c=\"\$(\"\${SHELL:-/bin/sh}\" -lc 'command -v herdr-projects' 2>/dev/null </dev/null | tail -n 1)\"; " +
        "if [ -n \"\$c\" ] && [ -x \"\$c\" ]; then echo \"\$c\"; exit 0; fi; exit $NOT_FOUND_EXIT"

    /** Prints the launcher's path, or exits with [NOT_FOUND_EXIT] when the board isn't installed. */
    fun locate(): String = "sh -c ${quote(LOCATE)}"

    /**
     * `herdr-projects remote <what> [base64 JSON]` for the machine's herdr session, through a login
     * shell as another board runs it (so PATH is set): the launcher calls `herdr` by name, and SSH's
     * own PATH may not have it. Started from `sh`, so it works whatever the account's shell is.
     */
    fun remote(machine: Machine, path: String, what: String, request: JsonObject? = null): String =
        "sh -c ${quote(LOGIN)} sh ${quote(command(machine, path, what, request))}"

    /** What the login shell runs. */
    fun command(machine: Machine, path: String, what: String, request: JsonObject? = null): String {
        val session = machine.session.takeIf { it.isNotEmpty() && it != "default" }?.let { "env HERDR_SESSION=${quote(it)} " }.orEmpty()
        val arg = request?.let { " " + Base64.getEncoder().encodeToString(it.toString().toByteArray()) }.orEmpty()
        return "${session}${quote(path)} remote ${quote(what)}$arg"
    }

    private const val LOGIN = "exec \"\${SHELL:-/bin/sh}\" -lc \"\$1\" </dev/null"

    fun dispatchRequest(text: String, delegate: Boolean): JsonObject = buildJsonObject {
        put("text", text)
        put("delegate", delegate)
    }
}

/** One thing said in the Coordinator's conversation. */
data class Said(val you: Boolean, val text: String)

/** The Coordinator as its machine sends it to another board (herdr-projects `coordinator/far.rs`). */
data class CoordinatorView(
    /** `none`, `starting`, `working`, `idle`, `blocked` or `exited`. */
    val state: String = "none",
    val error: String = "",
    /** Messages waiting to go in. */
    val queue: Int = 0,
    /** Whether its program keeps a conversation the board can read. */
    val readable: Boolean = true,
    /** Its latest message. */
    val said: String = "",
    /** Your newest words while they are not answered yet. */
    val sent: String = "",
    val talk: List<Said> = emptyList(),
) {
    val busy: Boolean get() = state == "working" || state == "starting" || queue > 0 || sent.isNotEmpty()
}

/** A bubble in the chat. */
data class ChatLine(val you: Boolean, val text: String, val pending: Boolean = false)

/**
 * What the board answered to `remote list`. [coordinator] is null where none can run there (no agent
 * is set up for it) or where the board is too old to send it ([tooOld]).
 */
data class BoardList(val host: String, val version: String, val coordinator: CoordinatorView?, val tooOld: Boolean = false)

object BoardReader {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The board's one line of JSON, wherever a shell's start-up files printed around it: the last
     * object that is one of its answers (a list, or an `ok`).
     */
    fun answer(stdout: String): JsonObject? {
        var other: JsonObject? = null
        for (line in stdout.lines().asReversed()) {
            val l = line.trim()
            if (!l.startsWith("{")) continue
            val o = runCatching { json.parseToJsonElement(l).jsonObject }.getOrNull() ?: continue
            if (o["tasks"] is JsonArray || (o["ok"] as? JsonPrimitive)?.booleanOrNull != null) return o
            if (other == null) other = o
        }
        return other
    }

    fun list(stdout: String): BoardList {
        val o = answer(stdout) ?: throw IOException("herdr-projects gave no answer")
        if (o["tasks"] !is JsonArray) throw IOException(o.str("msg").ifEmpty { "herdr-projects gave an unexpected answer" })
        val c = o["coordinator"] as? JsonObject
        val coordinator = when {
            c == null -> null
            (c["off"] as? JsonPrimitive)?.booleanOrNull == true -> null
            else -> CoordinatorView(
                state = c.str("state").ifEmpty { "none" },
                error = c.str("error"),
                queue = (c["queue"] as? JsonPrimitive)?.intOrNull ?: 0,
                readable = (c["readable"] as? JsonPrimitive)?.booleanOrNull ?: true,
                said = c.str("said"),
                sent = c.str("sent"),
                talk = (c["talk"] as? JsonArray).orEmpty().mapNotNull { m ->
                    val mo = m as? JsonObject ?: return@mapNotNull null
                    Said((mo["you"] as? JsonPrimitive)?.booleanOrNull == true, mo.str("text")).takeIf { it.text.isNotEmpty() }
                },
            )
        }
        return BoardList(o.str("host"), o.str("version"), coordinator, tooOld = !o.containsKey("coordinator"))
    }

    /** `ok` and its message, from a `dispatch`. */
    fun done(stdout: String): String {
        val o = answer(stdout) ?: throw IOException("herdr-projects gave no answer")
        val msg = o.str("msg")
        if ((o["ok"] as? JsonPrimitive)?.booleanOrNull != true) throw IOException(msg.ifEmpty { "herdr-projects refused the message" })
        return msg
    }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
}

object CoordinatorChat {
    /**
     * The chat as the board draws it (`board/view/talk.rs`): what was said; its latest message where
     * the conversation holds none of its words yet; and your newest unanswered words once, at the end.
     */
    fun lines(view: CoordinatorView, talk: List<Said> = view.talk): List<ChatLine> {
        val out = talk.map { ChatLine(it.you, it.text) }.toMutableList()
        if (out.none { !it.you } && view.said.isNotEmpty()) out += ChatLine(false, view.said)
        val mine = view.sent.trim()
        if (mine.isNotEmpty()) {
            val head = mine.take(200)
            val last = out.lastOrNull()
            if (last != null && last.you && last.text.contains(head)) out.removeAt(out.size - 1)
            out += ChatLine(true, mine, pending = true)
        }
        return out
    }

    /**
     * The board sends the last few things said. Kept across reads, the phone holds more of the
     * conversation than one read: where the new list carries on from the old one, the two are joined.
     * A new list that doesn't (a fresh conversation) replaces it.
     */
    fun merge(old: List<Said>, new: List<Said>): List<Said> {
        if (old.isEmpty() || new.isEmpty()) return new
        // The longest end of `old` that `new` starts with.
        for (n in minOf(old.size, new.size) downTo 1) {
            if (old.subList(old.size - n, old.size) == new.subList(0, n)) return old + new.subList(n, new.size)
        }
        // The newest things said can grow while they're written: the old end may be an earlier copy.
        for (n in minOf(old.size, new.size) downTo 2) {
            val a = old.subList(old.size - n, old.size)
            val b = new.subList(0, n)
            if (a.dropLast(1) == b.dropLast(1) && a.last().you == b.last().you && b.last().text.startsWith(a.last().text)) {
                return old.dropLast(1) + new.subList(n - 1, new.size)
            }
        }
        return new
    }
}

/** The board on one machine, over the app's SSH connection. */
class BoardClient(private val conn: MachineConnection, private val machine: Machine, private val path: String) {
    suspend fun list(): BoardList {
        val r = conn.run(BoardCommands.remote(machine, path, "list"), timeoutSeconds = 20)
        return runCatching { BoardReader.list(r.stdout) }.getOrElse {
            val err = r.stderr.trim().lines().lastOrNull { l -> l.isNotBlank() }
            throw IOException(err ?: it.message, it)
        }
    }

    /** Sends [text] to the Coordinator, as the board's box does; [delegate] is the box's Tab. */
    suspend fun send(text: String, delegate: Boolean = false): String {
        val r = conn.run(BoardCommands.remote(machine, path, "dispatch", BoardCommands.dispatchRequest(text, delegate)), timeoutSeconds = 30)
        return runCatching { BoardReader.done(r.stdout) }.getOrElse {
            val err = r.stderr.trim().lines().lastOrNull { l -> l.isNotBlank() }
            throw IOException(err ?: it.message, it)
        }
    }

    companion object {
        /** The launcher's path on the machine, or null when the board isn't installed there. */
        suspend fun locate(conn: MachineConnection): String? {
            val r = conn.run(BoardCommands.locate(), timeoutSeconds = 15)
            if (r.exitStatus != 0) return null
            return r.stdout.lines().lastOrNull { it.isNotBlank() }?.trim()
        }
    }
}
