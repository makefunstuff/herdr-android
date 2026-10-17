// Stand-in for app/…/data/IdentityStore.kt, which keeps the key in the Android Keystore. The preview's
// key is a fixed demo one ([preview.Demo.PUBLIC_KEY]); a scene can start without one ([preview.Scene.key]).
package com.makefunstuff.herdr.data

import android.content.Context
import com.makefunstuff.herdr.core.ssh.SshIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class IdentityStore(context: Context, scope: CoroutineScope) {
    sealed interface State {
        data object Loading : State
        data object Missing : State
        data class Ready(val publicKey: String) : State
        data class Unreadable(val reason: String) : State
    }

    private val _state = MutableStateFlow(initial)
    val state: StateFlow<State> = _state.asStateFlow()
    val publicKey: StateFlow<String?> = state.map { (it as? State.Ready)?.publicKey }
        .stateIn(scope, SharingStarted.Eagerly, (initial as? State.Ready)?.publicKey)

    suspend fun load(): State = _state.value

    suspend fun get(): SshIdentity = when (val s = _state.value) {
        is State.Ready -> SshIdentity("", s.publicKey)
        else -> throw IllegalStateException("No SSH key yet. Set one up in Settings.")
    }

    suspend fun create(replace: Boolean = false): SshIdentity = ready()
    suspend fun import(privateKey: String, passphrase: String?, replace: Boolean = false): SshIdentity = ready()
    suspend fun export(passphrase: String): String = "-----BEGIN OPENSSH PRIVATE KEY-----\n(demo)\n-----END OPENSSH PRIVATE KEY-----\n"

    private fun ready(): SshIdentity {
        _state.value = State.Ready(preview.Demo.PUBLIC_KEY)
        return SshIdentity("", preview.Demo.PUBLIC_KEY)
    }

    companion object {
        /** What the next store starts with; each scene sets it. */
        var initial: State = State.Ready(preview.Demo.PUBLIC_KEY)
    }
}
