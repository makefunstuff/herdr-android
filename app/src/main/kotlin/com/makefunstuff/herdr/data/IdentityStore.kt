package com.makefunstuff.herdr.data

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.makefunstuff.herdr.core.keys.KeyGen
import com.makefunstuff.herdr.core.keys.KeyImport
import com.makefunstuff.herdr.core.ssh.SshIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The app's SSH key. The private key is stored encrypted with an AES key that lives in the
 * Android Keystore and never leaves it.
 */
class IdentityStore(context: Context) {
    private val file = File(context.filesDir, "identity.bin")
    private val mutex = Mutex()
    private var cached: SshIdentity? = null

    private val _publicKey = MutableStateFlow<String?>(null)
    val publicKey: StateFlow<String?> = _publicKey.asStateFlow()

    @Serializable
    private data class Stored(val privateKey: String, val publicKey: String)

    suspend fun get(): SshIdentity = mutex.withLock {
        cached ?: withContext(Dispatchers.IO) {
            (if (file.exists()) runCatching { read() }.getOrNull() else null)
                ?: KeyGen.ed25519(comment()).also { write(it) }
        }.also {
            cached = it
            _publicKey.value = it.publicKeyLine
        }
    }

    suspend fun regenerate(): SshIdentity = replace(KeyGen.ed25519(comment()))

    suspend fun import(privateKey: String): SshIdentity = replace(KeyImport.fromPrivateKey(privateKey, comment()))

    private suspend fun replace(identity: SshIdentity): SshIdentity = mutex.withLock {
        withContext(Dispatchers.IO) { write(identity) }
        cached = identity
        _publicKey.value = identity.publicKeyLine
        identity
    }

    private fun comment() = "herdr-android@" + Build.MODEL.replace(Regex("[^A-Za-z0-9._-]"), "-")

    private fun write(identity: SshIdentity) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val plain = Json.encodeToString(Stored.serializer(), Stored(identity.privateKeyPem, identity.publicKeyLine))
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val tmp = File(file.parentFile, "identity.bin.tmp")
        tmp.writeBytes(byteArrayOf(iv.size.toByte()) + iv + encrypted)
        tmp.renameTo(file)
    }

    private fun read(): SshIdentity {
        val bytes = file.readBytes()
        val ivLength = bytes[0].toInt()
        val iv = bytes.copyOfRange(1, 1 + ivLength)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        val plain = cipher.doFinal(bytes, 1 + ivLength, bytes.size - 1 - ivLength).toString(Charsets.UTF_8)
        val stored = Json.decodeFromString(Stored.serializer(), plain)
        return SshIdentity(stored.privateKey, stored.publicKey)
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "herdr_identity_wrap"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
