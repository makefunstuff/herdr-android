package com.makefunstuff.herdr.data

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.makefunstuff.herdr.core.keys.KeyExport
import com.makefunstuff.herdr.core.keys.KeyGen
import com.makefunstuff.herdr.core.keys.KeyImport
import com.makefunstuff.herdr.core.ssh.SshIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
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
 * Android Keystore and never leaves it, so it survives app updates but not an uninstall; the
 * human keeps it across those with a passphrase-protected backup ([export] and [import]).
 *
 * A key is never created or replaced behind the human's back: [create] only runs when there is
 * none, and replacing one takes `replace = true`, which the UI only passes after a confirmation.
 */
class IdentityStore(context: Context, scope: CoroutineScope) {
    private val file = File(context.filesDir, "identity.bin")
    private val mutex = Mutex()
    private var cached: SshIdentity? = null

    sealed interface State {
        data object Loading : State
        /** No key yet: offer to restore a backup or import a key before making a new one. */
        data object Missing : State
        data class Ready(val publicKey: String) : State
        /** A key file exists but can't be decrypted (e.g. the Keystore key is gone). It is left untouched. */
        data class Unreadable(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()
    val publicKey: StateFlow<String?> = state.map { (it as? State.Ready)?.publicKey }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** Reads the stored key, if any, and publishes [state]. Never creates one. */
    suspend fun load(): State = mutex.withLock { loadLocked() }

    private suspend fun loadLocked(): State {
        cached?.let { return State.Ready(it.publicKeyLine) }
        val state = withContext(Dispatchers.IO) {
            if (!file.exists()) return@withContext State.Missing
            runCatching { read() }.fold(
                onSuccess = {
                    cached = it
                    State.Ready(it.publicKeyLine)
                },
                onFailure = { State.Unreadable(it.message ?: it.javaClass.simpleName) },
            )
        }
        _state.value = state
        return state
    }

    /** The key to sign in with. Fails, without making one, when there is no usable key. */
    suspend fun get(): SshIdentity = mutex.withLock {
        cached ?: when (val s = loadLocked()) {
            is State.Ready -> cached!!
            State.Missing -> throw IllegalStateException("No SSH key yet. Set one up in Settings.")
            is State.Unreadable -> throw IllegalStateException("The app's SSH key can't be read (${s.reason}). Restore it in Settings.")
            State.Loading -> error("unreachable")
        }
    }

    /** Makes a new key. Without [replace] it refuses to overwrite one that exists, readable or not. */
    suspend fun create(replace: Boolean = false): SshIdentity = save(replace) { KeyGen.ed25519(comment()) }

    /** Imports a private key or a backup made by [export]. Without [replace] it refuses to overwrite a key. */
    suspend fun import(privateKey: String, passphrase: String?, replace: Boolean = false): SshIdentity =
        save(replace) { KeyImport.fromPrivateKey(privateKey, comment(), passphrase) }

    /** The key in OpenSSH format, encrypted with [passphrase]. */
    suspend fun export(passphrase: String): String {
        val identity = get()
        return withContext(Dispatchers.Default) { KeyExport.encrypted(identity, passphrase) }
    }

    private suspend fun save(replace: Boolean, make: () -> SshIdentity): SshIdentity = mutex.withLock {
        if (!replace && withContext(Dispatchers.IO) { file.exists() }) {
            throw IllegalStateException("The app already has an SSH key.")
        }
        val identity = withContext(Dispatchers.Default) { make() }
        withContext(Dispatchers.IO) { write(identity) }
        cached = identity
        _state.value = State.Ready(identity.publicKeyLine)
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
        check(tmp.renameTo(file)) { "Couldn't save the key." }
    }

    private fun read(): SshIdentity {
        val bytes = file.readBytes()
        val ivLength = bytes[0].toInt()
        val iv = bytes.copyOfRange(1, 1 + ivLength)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, existingSecretKey() ?: error("its Keystore key is missing"), GCMParameterSpec(128, iv))
        val plain = cipher.doFinal(bytes, 1 + ivLength, bytes.size - 1 - ivLength).toString(Charsets.UTF_8)
        val stored = Json.decodeFromString(Stored.serializer(), plain)
        return SshIdentity(stored.privateKey, stored.publicKey)
    }

    @Serializable
    private data class Stored(val privateKey: String, val publicKey: String)

    private fun existingSecretKey(): SecretKey? {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        return (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    private fun secretKey(): SecretKey {
        existingSecretKey()?.let { return it }
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
