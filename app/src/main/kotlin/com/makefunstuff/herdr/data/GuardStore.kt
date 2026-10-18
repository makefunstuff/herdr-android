package com.makefunstuff.herdr.data

import com.makefunstuff.herdr.core.guard.Guarded
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Which actions ask for the fingerprint (or the phone's PIN) first, and the app lock. */
@Serializable
data class GuardState(
    /** The [Guarded] actions that don't ask, by key: every one asks until the person turns it off. */
    val off: Set<String> = emptySet(),
    /** Opening the app asks too. */
    val appLock: Boolean = false,
    /** Minutes the app may be away before opening it asks again; 0 asks every time. */
    val lockAfter: Int = 1,
) {
    fun asks(action: Guarded): Boolean = action.key !in off
}

/**
 * The phone's side of it: whether it can ask (a fingerprint, a face or a screen lock is set up), and
 * asking. Android's is `AndroidUnlock` (BiometricPrompt); the screen previews have their own.
 */
interface Unlock {
    fun canAsk(): Boolean

    /** Asks with [title] (and [subtitle]); true once the fingerprint, face or PIN was given, false when cancelled. */
    suspend fun ask(title: String, subtitle: String?): Boolean

    /** Android's settings for the screen lock, to set one up. */
    fun openSettings()
}

/**
 * [GuardState], kept as a small JSON file in app storage, and the app lock's state: locked when the
 * app starts with the lock on, and again when it comes back after [GuardState.lockAfter] minutes away.
 */
class GuardStore(private val file: File, val unlock: Unlock) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _state = MutableStateFlow(load())
    val state: StateFlow<GuardState> = _state.asStateFlow()

    private val _locked = MutableStateFlow(_state.value.appLock && unlock.canAsk())
    /** The app lock is up: the screens wait behind it until the fingerprint is given. */
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    /** When the app last left the screen; -1 while it hasn't since it started. */
    @Volatile private var leftAt = -1L
    /** A prompt is up: Android's PIN screen takes the app off the screen, which is no leaving. */
    private val asking = AtomicInteger()

    @Synchronized
    fun change(f: (GuardState) -> GuardState) {
        val next = f(_state.value)
        if (next == _state.value) return
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(GuardState.serializer(), next))
        tmp.renameTo(file)
        _state.value = next
    }

    /** Whether [action] asks first now: it is on, and the phone has something to ask with. */
    fun wants(action: Guarded): Boolean = _state.value.asks(action)

    /** Asks for the fingerprint; the app going off the screen meanwhile doesn't lock it. */
    suspend fun ask(title: String, subtitle: String?): Boolean {
        asking.incrementAndGet()
        return try {
            unlock.ask(title, subtitle)
        } finally {
            asking.decrementAndGet()
        }
    }

    /** The app came on the screen: locks it when it was away long enough. */
    fun started(now: Long = System.currentTimeMillis()) {
        val s = _state.value
        val away = leftAt >= 0 && now - leftAt >= s.lockAfter * 60_000L
        if (s.appLock && away && asking.get() == 0 && unlock.canAsk()) _locked.value = true
    }

    /** The app left the screen. */
    fun stopped(now: Long = System.currentTimeMillis()) {
        if (asking.get() == 0) leftAt = now
    }

    /** Asks to open the app; true once it is open. */
    suspend fun open(): Boolean {
        if (!_locked.value) return true
        // With no screen lock left on the phone there is nothing to ask with: the lock has no meaning.
        if (!unlock.canAsk() || ask("Unlock Herdr", "It holds the SSH key to your machines")) _locked.value = false
        return !_locked.value
    }

    /** Turns the app lock on or off; on, it counts as opened now. */
    fun setAppLock(on: Boolean) {
        change { it.copy(appLock = on) }
        if (!on) _locked.value = false
    }

    private fun load(): GuardState =
        runCatching { if (file.exists()) json.decodeFromString(GuardState.serializer(), file.readText()) else null }.getOrNull() ?: GuardState()
}
