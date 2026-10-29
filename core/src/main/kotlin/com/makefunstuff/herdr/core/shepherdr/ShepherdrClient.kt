package com.makefunstuff.herdr.core.shepherdr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.Closeable
import java.io.IOException

/** What `/api/cli` answered: the `shepherdr` command's output and exit code. */
@Serializable
data class V2CliResult(val out: String = "", val code: Int = 0) {
    val ok: Boolean get() = code == 0

    /** Its last line: what it said it did, or why it refused. */
    val said: String get() = out.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() }.orEmpty()
}

/** The bodies `POST /api/action` takes (`Action` in `shared/types.ts`), for what the app does. */
object V2Actions {
    /** Words into a chat; [reply]: the id of the message they answer; [space]: sent from a needs-you card in that space. */
    fun send(chat: String, text: String, reply: String? = null, cid: String? = null, space: String? = null): JsonObject = buildJsonObject {
        put("type", "send")
        put("chat", chat)
        put("text", text)
        cid?.let { put("cid", it) }
        reply?.let { put("reply", it) }
        space?.let { put("space", it) }
    }

    /**
     * Answers question card [question] in [chat]: [choice] picked (-1 none), or [choices] on a multi card;
     * [note] goes with it (a plan sent back needs one); [other] is a typed answer.
     */
    fun answer(
        chat: String,
        question: String,
        choice: Int,
        choices: List<Int>? = null,
        note: String? = null,
        other: String? = null,
        space: String? = null,
    ): JsonObject = buildJsonObject {
        put("type", "answer")
        put("chat", chat)
        put("question", question)
        put("choice", choice)
        choices?.let { c -> putJsonArray("choices") { c.forEach { add(JsonPrimitive(it)) } } }
        note?.takeIf { it.isNotEmpty() }?.let { put("note", it) }
        other?.takeIf { it.isNotEmpty() }?.let { put("other", it) }
        space?.let { put("space", it) }
    }

    /** Adds [emoji] to message [message] in [chat], or takes yours away again. */
    fun react(chat: String, message: String, emoji: String): JsonObject = buildJsonObject {
        put("type", "react")
        put("chat", chat)
        put("message", message)
        put("emoji", emoji)
    }

    /** The body of `POST /api/cli`: the `shepherdr` command's words. */
    fun cli(args: List<String>): JsonObject = buildJsonObject {
        putJsonArray("args") { args.forEach { add(JsonPrimitive(it)) } }
    }

    /** What the live link is told: the chats on screen. Sent as not visible, so the machine's alerts keep coming. */
    fun watch(chats: List<String>): JsonObject = buildJsonObject {
        put("type", "watch")
        putJsonArray("chats") { chats.forEach { add(JsonPrimitive(it)) } }
        put("term", JsonNull)
        put("visible", false)
        put("push", JsonNull)
    }
}

/** Shepherdr 2's API on one machine, over [transport]. */
class ShepherdrClient(private val transport: ShepherdrTransport) {
    val canStream: Boolean get() = transport.canStream

    suspend fun version(): V2Version = get("/api/version")
    suspend fun snapshot(): V2Snapshot = get("/api/snapshot")
    suspend fun waiting(): V2Waiting = get("/api/waiting")

    /** Sends [action] ([V2Actions]); its answer, or its own reason in the exception. */
    suspend fun act(action: JsonObject): JsonObject {
        val o = ok(transport.http("POST", "/api/action", action.toString()))
        if ((o["ok"] as? JsonPrimitive)?.booleanOrNull == false || o["error"] is JsonPrimitive) {
            throw IOException((o["error"] as? JsonPrimitive)?.content ?: "Shepherdr refused it")
        }
        return o
    }

    suspend fun send(chat: String, text: String, reply: String? = null): JsonObject = act(V2Actions.send(chat, text, reply))

    suspend fun answer(
        chat: String,
        question: String,
        choice: Int,
        choices: List<Int>? = null,
        note: String? = null,
        other: String? = null,
        space: String? = null,
    ): JsonObject = act(V2Actions.answer(chat, question, choice, choices, note, other, space))

    suspend fun react(chat: String, message: String, emoji: String): JsonObject = act(V2Actions.react(chat, message, emoji))

    /**
     * The `shepherdr` command with [args] (`["merge", "HA-1"]`), as the human would run it. Some wait
     * long (a merge waits for GitHub's checks), so there is no short timeout.
     */
    suspend fun cli(args: List<String>): V2CliResult {
        val r = transport.http("POST", "/api/cli", V2Actions.cli(args).toString(), timeoutMs = 0)
        return ShepherdrJson.decodeFromJsonElement(V2CliResult.serializer(), ok(r))
    }

    /** Opens the live link: a snapshot comes at once, then each change. */
    suspend fun live(): V2Live = V2Live(transport.webSocket("/api/ws"))

    /** One chat as it is now: through the live link, the only way chats come (or the transport's own way without one). */
    suspend fun chat(key: String, timeoutMs: Long = 15_000): V2Chat {
        if (!transport.canStream) {
            val e = V2Events.parse(transport.readChat(key, timeoutMs)) as? V2Event.Chat ?: throw IOException("Shepherdr sent no chat $key")
            return e.chat
        }
        return liveChat(key, timeoutMs)
    }

    private suspend fun liveChat(key: String, timeoutMs: Long): V2Chat = live().use { link ->
        link.watch(listOf(key))
        withTimeoutOrNull(timeoutMs) {
            var chat: V2Chat? = null
            while (chat == null) {
                val e = link.next() ?: throw IOException("Shepherdr closed the live link")
                if (e is V2Event.Chat && e.chat.key == key) chat = e.chat
            }
            chat
        } ?: throw IOException("Shepherdr sent no chat $key")
    }

    private suspend inline fun <reified T> get(path: String): T =
        ShepherdrJson.decodeFromJsonElement(kotlinx.serialization.serializer<T>(), ok(transport.http("GET", path)))

    private fun ok(r: HttpReply): JsonObject {
        val o = runCatching { ShepherdrJson.parseToJsonElement(r.body).jsonObject }.getOrNull()
        if (r.status !in 200..299) {
            throw IOException((o?.get("error") as? JsonPrimitive)?.content ?: r.body.trim().ifEmpty { "Shepherdr answered ${r.status}" })
        }
        return o ?: throw IOException("Shepherdr gave an answer that isn't JSON")
    }
}

/** The live link (`/api/ws`): snapshots and the watched chats, as they change. */
class V2Live internal constructor(private val socket: WebSocketLink) : Closeable {
    /** The chats to be sent ([V2Event.Chat]) now and on every change; a newly named one comes at once. */
    suspend fun watch(chats: List<String>) = withContext(Dispatchers.IO) { socket.send(V2Actions.watch(chats).toString()) }

    /** Asks for a pong: the link still works when one comes. */
    suspend fun ping() = withContext(Dispatchers.IO) { socket.send("""{"type":"ping"}""") }

    /** The next event, or null once the server closed the link. Cancelled while it waits, the link is closed. */
    suspend fun next(): V2Event? = coroutineScope {
        val read = async(Dispatchers.IO) { runCatching { socket.receive() } }
        val text = try {
            read.await().getOrThrow()
        } catch (e: CancellationException) {
            // A socket's read can't be interrupted: closing it ends the wait.
            socket.close()
            throw e
        }
        text?.let(V2Events::parse)
    }

    /** Every event until the link closes; collecting stops when it does, and cancelling closes it. */
    fun events(): Flow<V2Event> = flow {
        try {
            while (true) emit(next() ?: break)
        } finally {
            close()
        }
    }

    override fun close() = socket.close()
}

object V2Events {
    fun parse(text: String): V2Event {
        val o = ShepherdrJson.parseToJsonElement(text).jsonObject
        return when (val type = (o["type"] as? JsonPrimitive)?.content.orEmpty()) {
            "snapshot" -> V2Event.Snapshot(ShepherdrJson.decodeFromJsonElement(V2Snapshot.serializer(), o["snapshot"]!!))
            "chat" -> V2Event.Chat(ShepherdrJson.decodeFromJsonElement(V2Chat.serializer(), o["chat"]!!))
            else -> V2Event.Other(type, o)
        }
    }

    /** The events in a captured list of them (a JSON array), for tests and the previews. */
    fun parseAll(json: String): List<V2Event> =
        (ShepherdrJson.parseToJsonElement(json) as JsonArray).map { parse(it.toString()) }
}
