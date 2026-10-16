package com.makefunstuff.herdr.core.api

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** An error herdr answered with. [message] is herdr's own text as a sentence; [code] is for code to match on. */
class HerdrApiException(val code: String, val detail: String) : IOException(plain(code, detail)) {
    companion object {
        fun plain(code: String, detail: String): String {
            val text = detail.trim().ifEmpty { code.replace('_', ' ') }
            val sentence = text.replaceFirstChar { it.uppercaseChar() }
            return if (sentence.last() in ".!?") sentence else "$sentence."
        }
    }
}

/** An error as a person should read it: herdr's own message as a sentence, without its code. */
fun Throwable.plainMessage(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

/** Something that can make one herdr API request. */
interface ApiCaller {
    suspend fun call(method: String, params: JsonObject = JsonObject(emptyMap()), timeoutMs: Long = 20_000): JsonObject
}

/**
 * One herdr socket API connection: newline-delimited JSON requests and responses over a
 * byte stream (here, the stdio of `herdr remote-api-bridge` on an SSH channel).
 *
 * herdr answers one request per connection, except `events.subscribe`, which keeps the
 * connection open and pushes events. Lines that answer no pending request are emitted on [events].
 */
class ApiConnection(
    input: InputStream,
    private val output: OutputStream,
    scope: CoroutineScope,
    private val onClose: () -> Unit = {},
) : Closeable, ApiCaller {
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val writeLock = Any()

    private val eventChannel = Channel<JsonObject>(capacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Pushed lines that answer no request. Buffered, for a single collector. */
    val events: Flow<JsonObject> = eventChannel.receiveAsFlow()

    /** Completes when the stream ends or fails. */
    val closed = CompletableDeferred<Throwable?>()

    private val reader: Job = scope.launch(Dispatchers.IO) {
        var failure: Throwable? = null
        try {
            input.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    val obj = runCatching { HerdrJson.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                    dispatch(obj)
                }
            }
        } catch (t: Throwable) {
            failure = t
        } finally {
            val error = failure ?: IOException("herdr connection closed")
            pending.values.forEach { it.completeExceptionally(error) }
            pending.clear()
            eventChannel.close()
            closed.complete(failure)
        }
    }

    private fun dispatch(obj: JsonObject) {
        val id = (obj["id"] as? JsonPrimitive)?.contentOrNull
        val waiter = id?.let { pending.remove(it) }
        if (waiter != null) {
            waiter.complete(obj)
        } else {
            eventChannel.trySend(obj)
        }
    }

    /** Sends a request and returns its `result` object, or throws [HerdrApiException]. */
    override suspend fun call(method: String, params: JsonObject, timeoutMs: Long): JsonObject {
        val id = "a${nextId.getAndIncrement()}"
        val waiter = CompletableDeferred<JsonObject>()
        pending[id] = waiter
        send(id, method, params)
        val response = try {
            withTimeout(timeoutMs) { waiter.await() }
        } finally {
            pending.remove(id)
        }
        return unwrap(response)
    }

    /**
     * Sends a request whose response is followed by pushed events on the same connection
     * (events.subscribe). Returns the first response; later lines arrive on [events].
     */
    suspend fun subscribe(params: JsonObject): JsonObject = call("events.subscribe", params)

    private suspend fun send(id: String, method: String, params: JsonObject) {
        val line = buildJsonObject {
            put("id", JsonPrimitive(id))
            put("method", JsonPrimitive(method))
            put("params", params)
        }.toString() + "\n"
        withContext(Dispatchers.IO) {
            synchronized(writeLock) {
                output.write(line.toByteArray(Charsets.UTF_8))
                output.flush()
            }
        }
    }

    override fun close() {
        runCatching { output.close() }
        reader.cancel()
        onClose()
    }

    companion object {
        fun unwrap(response: JsonObject): JsonObject {
            val error = response["error"]
            if (error is JsonObject) {
                val code = error["code"]?.jsonPrimitive?.contentOrNull ?: "error"
                val message = error["message"]?.jsonPrimitive?.contentOrNull ?: "herdr returned an error"
                throw HerdrApiException(code, message)
            }
            return (response["result"] as? JsonObject) ?: JsonObject(emptyMap())
        }
    }
}

internal fun params(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    for ((key, value) in pairs) {
        when (value) {
            null -> {}
            is JsonElement -> put(key, value)
            is String -> put(key, JsonPrimitive(value))
            is Number -> put(key, JsonPrimitive(value))
            is Boolean -> put(key, JsonPrimitive(value))
            else -> error("unsupported param type for $key")
        }
    }
}
