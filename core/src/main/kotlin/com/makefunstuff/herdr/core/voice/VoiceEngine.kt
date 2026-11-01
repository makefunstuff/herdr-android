package com.makefunstuff.herdr.core.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/**
 * Speech out and in on a server. Today that is the owner's LFM3 server in Edge mode ([EdgeVoice]);
 * the homelab's shared voice service (HOM-11) takes its place behind the same two calls.
 */
interface VoiceEngine {
    /** Sample rate of what [speak] hands over. */
    val rate: Int

    /**
     * Says [text] in [voice] ("female", "male"), streamed: [onAudio] gets mono float PCM as it is made
     * and returns false to stop. False if it was stopped.
     */
    suspend fun speak(text: String, voice: String, onAudio: (FloatArray) -> Boolean): Boolean

    /** The words in [pcm16k] (16 kHz mono float). */
    suspend fun transcribe(pcm16k: FloatArray): String

    /** Answers at all (a quick look, about a second at most): the round trip in ms. */
    suspend fun ping(): Long
}

/**
 * The LFM3 server's speech (`local-ai-mode edge`): Omni's `/tts` streamed as 24 kHz float PCM, and
 * `/v1/audio/transcriptions`. [url] is `http://host:port`, [key] its bearer key.
 */
class EdgeVoice(
    url: String,
    private val key: String,
    private val speechModel: String = OMNI,
    private val hearingModel: String = OMNI,
    private val connectMs: Int = 2_000,
) : VoiceEngine {
    private val base = normalize(url) ?: throw IOException("Write the server as http://address:port")
    override val rate = 24_000

    override suspend fun speak(text: String, voice: String, onAudio: (FloatArray) -> Boolean): Boolean {
        val body = """{"model":${quote(speechModel)},"input":${quote(text)},"voice":${quote(voice)},"response_format":"pcm","stream":true}"""
        val pcm = PcmFloats()
        var stopped = false
        request("/tts", "application/json", body.toByteArray(), readMs = 8_000) { bytes ->
            val f = pcm.feed(bytes)
            (f.isEmpty() || onAudio(f)).also { if (!it) stopped = true }
        }
        return !stopped
    }

    override suspend fun transcribe(pcm16k: FloatArray): String {
        val boundary = "herdr" + System.nanoTime()
        val body = ByteArrayOutputStream()
        fun part(name: String, value: String) =
            body.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
        part("model", hearingModel)
        part("response_format", "json")
        body.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"turn.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
        body.write(wav16(pcm16k, 16_000))
        body.write("\r\n--$boundary--\r\n".toByteArray())
        val out = ByteArrayOutputStream()
        request("/v1/audio/transcriptions", "multipart/form-data; boundary=$boundary", body.toByteArray(), readMs = 30_000) { out.write(it); true }
        return Regex("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(out.toString(Charsets.UTF_8.name()))
            ?.groupValues?.get(1)?.let(::unquote)?.trim().orEmpty()
    }

    override suspend fun ping(): Long {
        val t0 = System.nanoTime()
        request("/v1/models", null, null, readMs = 3_000) { true }
        return (System.nanoTime() - t0) / 1_000_000
    }

    /** One request; [onBytes] gets the body as it comes and returns false to stop reading. */
    private suspend fun request(path: String, type: String?, body: ByteArray?, readMs: Int, onBytes: (ByteArray) -> Boolean) =
        withContext(Dispatchers.IO) {
            val c = URI(base + path).toURL().openConnection() as HttpURLConnection
            try {
                c.connectTimeout = connectMs
                c.readTimeout = readMs
                c.setRequestProperty("Authorization", "Bearer $key")
                if (body != null) {
                    c.requestMethod = "POST"
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", type)
                    c.outputStream.use { it.write(body) }
                }
                val code = c.responseCode
                if (code == 401 || code == 403) throw IOException("The server refused the key")
                if (code !in 200..299) throw IOException("The server answered $code")
                c.inputStream.use { input ->
                    val buf = ByteArray(8192)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n > 0 && !onBytes(buf.copyOf(n))) break
                    }
                }
            } catch (e: java.net.UnknownServiceException) {
                // Android's network rules: plain http only to the addresses the app names.
                throw IOException("This address needs https", e)
            } catch (e: java.net.SocketTimeoutException) {
                throw IOException("The server didn't answer in time", e)
            } catch (e: java.net.ConnectException) {
                throw IOException("Can't reach the server", e)
            } catch (e: java.net.UnknownHostException) {
                throw IOException("Can't find the server", e)
            } finally {
                c.disconnect()
            }
        }

    companion object {
        /** LFM3 Omni: speaks (`/tts`) and hears. */
        const val OMNI = "lfm3-omni-2b"

        /** `http(s)://host[:port]` with no path ("/v1" at the end is dropped), or null if it isn't one. */
        fun normalize(url: String): String? {
            var u = url.trim().trimEnd('/')
            if (u.isEmpty()) return null
            if ("://" !in u) u = "http://$u"
            u = u.removeSuffix("/v1")
            val scheme = u.substringBefore("://").lowercase()
            if (scheme != "http" && scheme != "https") return null
            val rest = u.substringAfter("://")
            if (rest.isEmpty() || '/' in rest || '?' in rest || '@' in rest || ' ' in rest) return null
            val port = if (rest.startsWith("[")) rest.substringAfter("]").removePrefix(":") else rest.substringAfterLast(':', "")
            if (port.isNotEmpty() && port.toIntOrNull()?.takeIf { it in 1..65535 } == null) return null
            return "$scheme://$rest"
        }

        /**
         * The host and port a plain-http [url] is reached at through a machine's SSH connection (`ssh -L`,
         * as the machine sees them): Android lets the app speak plain http only to the phone itself, so a
         * server on the home network (or on the machine itself, `localhost`) is asked through the machine,
         * at home or away. Null for https, or what isn't an address.
         */
        fun throughMachine(url: String): Pair<String, Int>? {
            val u = normalize(url) ?: return null
            if (!u.startsWith("http://")) return null
            val rest = u.removePrefix("http://")
            val host = if (rest.startsWith("[")) rest.substringBefore("]") + "]" else rest.substringBeforeLast(':')
            val port = (if (rest.startsWith("[")) rest.substringAfter("]").removePrefix(":") else rest.substringAfterLast(':', "")).toIntOrNull() ?: 80
            return host.removePrefix("[").removeSuffix("]").lowercase() to port
        }

        /** 16-bit mono WAV from float PCM. */
        fun wav16(pcm: FloatArray, rate: Int): ByteArray {
            val data = pcm.size * 2
            val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
            b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            b.put("data".toByteArray()).putInt(data)
            for (x in pcm) b.putShort((x.coerceIn(-1f, 1f) * 32767).toInt().toShort())
            return b.array()
        }

        private fun quote(s: String): String = buildString {
            append('"')
            for (ch in s) when {
                ch == '"' -> append("\\\"")
                ch == '\\' -> append("\\\\")
                ch == '\n' -> append("\\n")
                ch < ' ' -> append(String.format("\\u%04x", ch.code))
                else -> append(ch)
            }
            append('"')
        }

        private fun unquote(s: String): String =
            runCatching { kotlinx.serialization.json.Json.decodeFromString<String>("\"$s\"") }.getOrDefault(s)
    }
}

/** Little-endian 32-bit floats from bytes cut anywhere. */
class PcmFloats {
    private val carry = ByteArray(4)
    private var n = 0

    fun feed(bytes: ByteArray): FloatArray {
        val total = n + bytes.size
        val out = FloatArray(total / 4)
        val all = ByteArray(total)
        System.arraycopy(carry, 0, all, 0, n)
        System.arraycopy(bytes, 0, all, n, bytes.size)
        ByteBuffer.wrap(all, 0, out.size * 4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
        n = total - out.size * 4
        System.arraycopy(all, out.size * 4, carry, 0, n)
        return out
    }
}
