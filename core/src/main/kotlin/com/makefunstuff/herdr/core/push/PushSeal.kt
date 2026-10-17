package com.makefunstuff.herdr.core.push

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.Inflater
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * How herdr-push (host/herdr-push) seals what it sends, so the push server reads nothing and the app
 * takes nothing it didn't seal: `hp1.<base64url(nonce ‖ ciphertext ‖ tag)>`.
 *
 * Two keys come from the 32-byte key the app gave the machine over SSH: enc = HMAC(key, "herdr-push enc")
 * and mac = HMAC(key, "herdr-push mac"). The keystream is HMAC-SHA256(enc, nonce ‖ counter), counter
 * 0, 1, … as 32-bit big-endian, XORed with the zlib-compressed JSON; the tag is
 * HMAC-SHA256(mac, "hp1" ‖ nonce ‖ ciphertext), checked before anything is decrypted. Only HMAC-SHA256,
 * because that is all the watcher's Python standard library has.
 */
object PushSeal {
    const val PREFIX = "hp1."
    private const val NONCE = 16
    private const val TAG = 32
    private const val MAX_PLAIN = 256 * 1024

    fun newKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    fun seal(key: ByteArray, plain: ByteArray, nonce: ByteArray = ByteArray(NONCE).also { SecureRandom().nextBytes(it) }): String {
        val (enc, mac) = keys(key)
        val ct = xor(enc, nonce, plain)
        val tag = hmac(mac, "hp1".toByteArray() + nonce + ct)
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(nonce + ct + tag)
    }

    /** The plain bytes, or null when [text] wasn't sealed with [key] (another machine's, or tampered with). */
    fun open(key: ByteArray, text: String): ByteArray? {
        val body = text.trim().takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX) ?: return null
        val raw = runCatching { Base64.getUrlDecoder().decode(body.trimEnd('=')) }.getOrNull() ?: return null
        if (raw.size < NONCE + TAG) return null
        val nonce = raw.copyOfRange(0, NONCE)
        val ct = raw.copyOfRange(NONCE, raw.size - TAG)
        val tag = raw.copyOfRange(raw.size - TAG, raw.size)
        val (enc, mac) = keys(key)
        if (!MessageDigest.isEqual(tag, hmac(mac, "hp1".toByteArray() + nonce + ct))) return null
        return xor(enc, nonce, ct)
    }

    /** [open], then the zlib inside it, as text. */
    fun openText(key: ByteArray, text: String): String? {
        val packed = open(key, text) ?: return null
        val inflater = Inflater()
        return try {
            inflater.setInput(packed)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) return null
                out.write(buf, 0, n)
                if (out.size() > MAX_PLAIN) return null
            }
            out.toString(Charsets.UTF_8)
        } catch (_: Exception) {
            null
        } finally {
            inflater.end()
        }
    }

    /** [text] compressed and sealed, as herdr-push sends it (for tests, and the app's own test notification). */
    fun sealText(key: ByteArray, text: String): String {
        val deflater = java.util.zip.Deflater(9)
        deflater.setInput(text.toByteArray(Charsets.UTF_8))
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        return seal(key, out.toByteArray())
    }

    private fun keys(key: ByteArray) = hmac(key, "herdr-push enc".toByteArray()) to hmac(key, "herdr-push mac".toByteArray())

    private fun xor(enc: ByteArray, nonce: ByteArray, data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        var counter = 0
        var i = 0
        while (i < data.size) {
            val block = hmac(enc, nonce + byteArrayOf((counter ushr 24).toByte(), (counter ushr 16).toByte(), (counter ushr 8).toByte(), counter.toByte()))
            for (b in block) {
                if (i >= data.size) break
                out[i] = (data[i].toInt() xor b.toInt()).toByte()
                i++
            }
            counter++
        }
        return out
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
}
