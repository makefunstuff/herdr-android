package com.makefunstuff.herdr.core.hc

import com.makefunstuff.herdr.core.api.ApiCaller
import com.makefunstuff.herdr.core.ssh.Duplex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** An error herdr-core answered with: [kind] is its stable `data.kind` (`not_found`, `blocked`, …). */
class HcException(val kind: String, detail: String, val hint: String? = null) : IOException(sentence(detail.ifBlank { kind.replace('_', ' ') })) {
    private companion object {
        fun sentence(text: String): String {
            val s = text.trim().replaceFirstChar { it.uppercaseChar() }
            return if (s.lastOrNull() in listOf('.', '!', '?')) s else "$s."
        }
    }
}

/** Framed mode's frames: `[channel u32 LE][kind u8][len u32 LE][payload]`. */
object HcFrames {
    const val DATA = 0
    const val CREDIT = 1
    const val CLOSE = 2
    const val RESIZE = 3
    const val FOCUS = 4
    const val FACTS = 5
    const val HEADER = 9
    const val MAX_PAYLOAD = 16 shl 20

    data class Frame(val channel: Int, val kind: Int, val payload: ByteArray)

    fun encode(channel: Int, kind: Int, payload: ByteArray = ByteArray(0)): ByteArray =
        ByteBuffer.allocate(HEADER + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(channel).put(kind.toByte()).putInt(payload.size).put(payload).array()

    fun u32(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    fun size(cols: Int, rows: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putShort(cols.toShort()).putShort(rows.toShort()).array()

    /** The next frame, or null at a clean end of stream between frames. */
    fun read(input: InputStream): Frame? {
        val header = ByteArray(HEADER)
        val first = input.read(header, 0, HEADER)
        if (first < 0) return null
        readFully(input, header, first)
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val channel = b.getInt(0)
        val kind = header[4].toInt() and 0xff
        val len = b.getInt(5)
        if (len < 0 || len > MAX_PAYLOAD) throw IOException("herdr-core sent a frame of $len bytes")
        val payload = ByteArray(len)
        readFully(input, payload, 0)
        return Frame(channel, kind, payload)
    }

    private fun readFully(input: InputStream, into: ByteArray, from: Int) {
        var at = from
        while (at < into.size) {
            val n = input.read(into, at, into.size - at)
            if (n < 0) throw EOFException("herdr-core closed the connection mid-frame")
            at += n
        }
    }
}

/**
 * One connection to a herdr-core, here the stdio of `hc connect` on an SSH exec channel: `hello`
 * in plain mode (one JSON line each way), then framed mode for the rest of its life. Channel 0
 * carries JSON-RPC both ways, calls side by side; the event subscription and each attached
 * terminal get a channel of their own ([HcStream]), so one connection carries everything.
 */
class HcConnection private constructor(
    private val input: InputStream,
    private val output: OutputStream,
    val hello: HcHello,
    private val onClose: () -> Unit,
) : ApiCaller, Closeable {
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    private val streams = ConcurrentHashMap<Int, HcStream>()
    private val writeLock = Any()
    private val ended = AtomicBoolean(false)
    private var reader: Job? = null
    private var scope: CoroutineScope? = null

    /** Completes when the connection ends: null when closed here, else why. */
    val closed = CompletableDeferred<Throwable?>()

    private fun start(scope: CoroutineScope) {
        this.scope = scope
        reader = scope.launch(Dispatchers.IO) {
            var failure: Throwable? = null
            try {
                while (true) {
                    val frame = HcFrames.read(input) ?: break
                    dispatch(frame)
                }
            } catch (t: Throwable) {
                failure = t
            } finally {
                val error = failure ?: IOException("herdr-core closed the connection")
                // Before failing the waiters: a call registering from now on fails itself.
                ended.set(true)
                pending.values.forEach { it.completeExceptionally(error) }
                pending.clear()
                streams.values.forEach { it.ended(null) }
                closed.complete(failure)
            }
        }
    }

    private fun dispatch(frame: HcFrames.Frame) {
        if (frame.channel == 0) {
            if (frame.kind != HcFrames.DATA) return
            val obj = runCatching { HcJson.parseToJsonElement(frame.payload.toString(Charsets.UTF_8)).jsonObject }.getOrNull() ?: return
            val id = (obj["id"] as? JsonPrimitive)?.longOrNull ?: return
            pending.remove(id)?.complete(obj)
            return
        }
        // A stream's first paint can come before its caller has the attach reply: kept until claimed.
        val stream = stream(frame.channel)
        when (frame.kind) {
            HcFrames.DATA -> stream.received(frame.payload)
            HcFrames.CLOSE -> {
                val reason = runCatching { HcJson.parseToJsonElement(frame.payload.toString(Charsets.UTF_8)).jsonObject }.getOrNull()
                stream.ended(reason)
                streams.remove(frame.channel)
            }
        }
    }

    /** The stream on [channel] (from `terminal.attach` or `events.subscribe`). */
    fun stream(channel: Int): HcStream = streams.getOrPut(channel) { HcStream(channel, this) }

    /** Sends a request and returns its `result`, or throws [HcException]. */
    override suspend fun call(method: String, params: JsonObject, timeoutMs: Long): JsonObject {
        val id = nextId.getAndIncrement()
        val waiter = CompletableDeferred<JsonObject>()
        pending[id] = waiter
        if (ended.get() || closed.isCompleted) {
            pending.remove(id)
            throw IOException("herdr-core closed the connection")
        }
        val response = try {
            write(HcFrames.encode(0, HcFrames.DATA, request(id, method, params).toByteArray(Charsets.UTF_8)))
            // A plain failure, not withTimeout's cancellation, which callers would take for their own.
            withTimeoutOrNull(timeoutMs) { waiter.await() } ?: throw IOException("herdr-core didn't answer $method")
        } finally {
            pending.remove(id)
        }
        return unwrap(response)
    }

    internal suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        synchronized(writeLock) {
            output.write(bytes)
            output.flush()
        }
    }

    /** In the background, for a viewer closing on its way out (a screen's dispose runs on the UI thread). */
    internal fun writeLater(bytes: ByteArray) {
        val s = scope ?: return
        s.launch(Dispatchers.IO) { runCatching { write(bytes) } }
    }

    override fun close() {
        // First: the reader then fails on the closed pipe, which is no news.
        if (!closed.isCompleted) closed.complete(null)
        runCatching { output.close() }
        reader?.cancel()
        onClose()
    }

    companion object {
        /** JSON-RPC generation the app speaks. */
        const val GENERATION = 1

        /**
         * Says hello on [duplex] (which the connection then owns) asking for framed mode, and starts
         * reading on [scope]. Fails, closing it, when what answers isn't a herdr-core of [GENERATION].
         */
        suspend fun open(duplex: Duplex, scope: CoroutineScope, timeoutMs: Long = 20_000): HcConnection = withContext(Dispatchers.IO) {
            try {
                val line = request(0, "hello", buildJsonObject {
                    put("generation", JsonPrimitive(GENERATION))
                    put("framed", JsonPrimitive(true))
                })
                duplex.output.write(line.toByteArray(Charsets.UTF_8))
                duplex.output.write('\n'.code)
                duplex.output.flush()
                // Byte by byte: what follows the line is already framed. A blocking read ignores a
                // timeout, so a core that never answers is closed, which ends the read.
                val reply = coroutineScope {
                    val read = async(Dispatchers.IO) { runCatching { readLine(duplex.input) } }
                    withTimeoutOrNull(timeoutMs) { read.await() } ?: run {
                        runCatching { duplex.close() }
                        throw IOException("herdr-core didn't answer")
                    }
                }.getOrThrow() ?: throw IOException("herdr-core didn't answer")
                val obj = runCatching { HcJson.parseToJsonElement(reply).jsonObject }
                    .getOrElse { throw IOException("Not herdr-core: ${reply.take(120)}") }
                val hello = unwrap(obj).decode<HcHello>()
                if (!hello.framed) throw IOException("herdr-core ${hello.version} refused framed mode")
                HcConnection(duplex.input, duplex.output, hello) { duplex.close() }.also { it.start(scope) }
            } catch (t: Throwable) {
                runCatching { duplex.close() }
                throw t
            }
        }

        internal fun request(id: Long, method: String, params: JsonObject): String = buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("id", JsonPrimitive(id))
            put("method", JsonPrimitive(method))
            put("params", params)
        }.toString()

        internal fun unwrap(response: JsonObject): JsonObject {
            val error = response["error"] as? JsonObject
            if (error != null) {
                val data = error["data"] as? JsonObject
                val kind = (data?.get("kind") as? JsonPrimitive)?.contentOrNull ?: "error"
                val message = (error["message"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                throw HcException(kind, message, (data?.get("hint") as? JsonPrimitive)?.contentOrNull)
            }
            return response["result"] as? JsonObject ?: JsonObject(emptyMap())
        }

        private fun readLine(input: InputStream): String? {
            val out = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return if (out.size() == 0) null else out.toString(Charsets.UTF_8)
                if (b == '\n'.code) return out.toString(Charsets.UTF_8)
                out.write(b)
                if (out.size() > HcFrames.MAX_PAYLOAD) throw IOException("herdr-core's hello is too long")
            }
        }
    }
}

/**
 * One stream channel on an [HcConnection]: an attached terminal's bytes, or the event
 * subscription's JSON lines. Bytes are credited back as [read] hands them out, so a reader that
 * stops reading stops the core sending (it repaints a terminal viewer that fell far behind).
 */
class HcStream internal constructor(val channel: Int, private val connection: HcConnection) {
    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)

    /** The core's reason when it closed the stream (`{"reason":"exited","exit_code":0}`), null otherwise. */
    val closeReason = CompletableDeferred<JsonObject?>()

    internal fun received(bytes: ByteArray) {
        inbox.trySend(bytes)
    }

    internal fun ended(reason: JsonObject?) {
        closeReason.complete(reason)
        inbox.close()
    }

    /** The next bytes, or null once the stream is over. */
    suspend fun read(): ByteArray? {
        val bytes = inbox.receiveCatching().getOrNull() ?: return null
        runCatching { connection.write(HcFrames.encode(channel, HcFrames.CREDIT, HcFrames.u32(bytes.size))) }
        return bytes
    }

    /** Keys, as the program's own bytes (the core never encodes them for a viewer). */
    suspend fun write(bytes: ByteArray) = connection.write(HcFrames.encode(channel, HcFrames.DATA, bytes))

    suspend fun resize(cols: Int, rows: Int) = connection.write(HcFrames.encode(channel, HcFrames.RESIZE, HcFrames.size(cols, rows)))

    suspend fun focus(focused: Boolean) = connection.write(HcFrames.encode(channel, HcFrames.FOCUS, byteArrayOf(if (focused) 1 else 0)))

    /** Detaches; the core answers with an empty CLOSE. */
    fun close() {
        if (closeReason.isCompleted) return
        connection.writeLater(HcFrames.encode(channel, HcFrames.CLOSE))
    }
}
