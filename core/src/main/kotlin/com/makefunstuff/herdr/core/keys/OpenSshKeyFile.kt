package com.makefunstuff.herdr.core.keys

import com.hierynomus.sshj.userauth.keyprovider.bcrypt.BCrypt
import net.schmizz.sshj.common.Buffer
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Thrown when a key is protected and no passphrase (or the wrong one) was given. */
class PassphraseException(message: String) : IllegalArgumentException(message)

/**
 * Reads and writes OpenSSH's own private key format (`openssh-key-v1`), the one `ssh-keygen`
 * writes, with or without a passphrase. Encrypted keys use bcrypt and AES-CTR, like `ssh-keygen -p`.
 */
object OpenSshKeyFile {
    const val BEGIN = "-----BEGIN OPENSSH PRIVATE KEY-----"
    const val END = "-----END OPENSSH PRIVATE KEY-----"
    private val MAGIC = "openssh-key-v1\u0000".toByteArray(Charsets.US_ASCII)
    private const val EXPORT_CIPHER = "aes256-ctr"
    private const val ROUNDS = 16

    /**
     * One decrypted key: the public key blob and the private fields, which are all SSH strings
     * (the key type, the key material, and finally the comment).
     */
    class Key(val publicBlob: ByteArray, val fields: List<ByteArray>) {
        val type: String get() = Buffer.PlainBuffer(publicBlob).readString()
        val comment: String get() = fields.last().toString(Charsets.UTF_8)
        fun withComment(comment: String) = Key(publicBlob, fields.dropLast(1) + comment.toByteArray(Charsets.UTF_8))
        fun publicLine(comment: String) = "$type ${Base64.getEncoder().encodeToString(publicBlob)} $comment".trimEnd()
    }

    fun isOpenSsh(text: String) = text.contains(BEGIN)

    fun isEncrypted(text: String): Boolean = Container.parse(text).cipher != "none"

    /** Decrypts [text]; [passphrase] is only needed when the key has one. */
    fun read(text: String, passphrase: String?): Key {
        val c = Container.parse(text)
        val section = if (c.cipher == "none") {
            c.section
        } else {
            if (passphrase.isNullOrEmpty()) throw PassphraseException("This key is protected. Enter its passphrase.")
            if (c.kdf != "bcrypt") throw IllegalArgumentException("Unsupported key encryption (${c.kdf}).")
            val spec = CIPHERS[c.cipher]
                ?: throw IllegalArgumentException("Unsupported key cipher ${c.cipher}. Re-encrypt it with ssh-keygen -p -Z aes256-ctr.")
            val options = Buffer.PlainBuffer(c.kdfOptions)
            val salt = options.readStringAsBytes()
            val rounds = options.readUInt32AsInt()
            crypt(Cipher.DECRYPT_MODE, spec, passphrase, salt, rounds, c.section)
        }
        val buf = Buffer.PlainBuffer(section)
        if (buf.readUInt32AsInt() != buf.readUInt32AsInt()) throw PassphraseException("Wrong passphrase.")
        // Every field of every OpenSSH key type is a length-prefixed string; what is left is padding.
        val fields = mutableListOf<ByteArray>()
        while (buf.available() >= 4) {
            val mark = buf.rpos()
            val length = buf.readUInt32()
            if (length > buf.available()) {
                buf.rpos(mark)
                break
            }
            buf.rpos(mark)
            fields += buf.readStringAsBytes()
        }
        val padding = ByteArray(buf.available()).also { buf.readRawBytes(it) }
        if (fields.size < 2 || padding.withIndex().any { (i, b) -> b.toInt() != i + 1 }) {
            throw IllegalArgumentException("Couldn't read that key.")
        }
        return Key(c.publicBlob, fields)
    }

    /** Writes [key] in OpenSSH format, encrypted with [passphrase] when one is given. */
    fun write(key: Key, passphrase: String? = null): String {
        val encrypt = !passphrase.isNullOrEmpty()
        val check = SecureRandom().nextInt().toLong() and 0xffffffffL
        val section = Buffer.PlainBuffer().putUInt32(check).putUInt32(check)
        key.fields.forEach { section.putString(it) }
        val blockSize = if (encrypt) 16 else 8
        var pad = 1
        while (section.available() % blockSize != 0) section.putByte((pad++).toByte())
        val plain = section.compactData

        val out = Buffer.PlainBuffer().putRawBytes(MAGIC)
        if (encrypt) {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val options = Buffer.PlainBuffer().putString(salt).putUInt32(ROUNDS.toLong()).compactData
            out.putString(EXPORT_CIPHER).putString("bcrypt").putString(options)
            out.putUInt32(1).putString(key.publicBlob)
            out.putString(crypt(Cipher.ENCRYPT_MODE, CIPHERS.getValue(EXPORT_CIPHER), passphrase!!, salt, ROUNDS, plain))
        } else {
            out.putString("none").putString("none").putString(ByteArray(0))
            out.putUInt32(1).putString(key.publicBlob)
            out.putString(plain)
        }
        val body = Base64.getEncoder().encodeToString(out.compactData).chunked(70).joinToString("\n")
        return "$BEGIN\n$body\n$END\n"
    }

    private class CipherSpec(val transformation: String, val keyLength: Int)

    private val CIPHERS = mapOf(
        "aes128-ctr" to CipherSpec("AES/CTR/NoPadding", 16),
        "aes192-ctr" to CipherSpec("AES/CTR/NoPadding", 24),
        "aes256-ctr" to CipherSpec("AES/CTR/NoPadding", 32),
        "aes128-cbc" to CipherSpec("AES/CBC/NoPadding", 16),
        "aes192-cbc" to CipherSpec("AES/CBC/NoPadding", 24),
        "aes256-cbc" to CipherSpec("AES/CBC/NoPadding", 32),
    )

    private fun crypt(mode: Int, spec: CipherSpec, passphrase: String, salt: ByteArray, rounds: Int, data: ByteArray): ByteArray {
        val derived = ByteArray(spec.keyLength + 16)
        BCrypt().pbkdf(passphrase.toByteArray(Charsets.UTF_8), salt, rounds, derived)
        val cipher = Cipher.getInstance(spec.transformation)
        cipher.init(
            mode,
            SecretKeySpec(derived, 0, spec.keyLength, "AES"),
            IvParameterSpec(derived, spec.keyLength, 16),
        )
        return cipher.doFinal(data)
    }

    private class Container(
        val cipher: String,
        val kdf: String,
        val kdfOptions: ByteArray,
        val publicBlob: ByteArray,
        val section: ByteArray,
    ) {
        companion object {
            fun parse(text: String): Container {
                val start = text.indexOf(BEGIN)
                val end = text.indexOf(END)
                require(start >= 0 && end > start) { "Not an OpenSSH private key." }
                val bytes = try {
                    Base64.getMimeDecoder().decode(text.substring(start + BEGIN.length, end))
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("Couldn't read that key.", e)
                }
                require(bytes.size > MAGIC.size && bytes.copyOf(MAGIC.size).contentEquals(MAGIC)) { "Couldn't read that key." }
                return try {
                    val buf = Buffer.PlainBuffer(bytes.copyOfRange(MAGIC.size, bytes.size))
                    val cipher = buf.readString()
                    val kdf = buf.readString()
                    val options = buf.readStringAsBytes()
                    require(buf.readUInt32AsInt() == 1) { "Files with several keys aren't supported." }
                    Container(cipher, kdf, options, buf.readStringAsBytes(), buf.readStringAsBytes())
                } catch (e: Buffer.BufferException) {
                    throw IllegalArgumentException("Couldn't read that key.", e)
                }
            }
        }
    }
}
