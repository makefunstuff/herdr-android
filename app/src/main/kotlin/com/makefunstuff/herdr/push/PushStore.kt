package com.makefunstuff.herdr.push

import android.content.Context
import com.makefunstuff.herdr.core.push.PushSeal
import com.makefunstuff.herdr.core.push.QuietHours
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Base64
import java.util.UUID

/** Notifications while the app is closed: the switch, the push app's address, and each machine's part. */
@Serializable
data class PushState(
    /** Notifications while the app is closed, on or off. */
    val on: Boolean = false,
    /** This phone, as machines know it; made once. */
    val phoneId: String = "",
    /** The push app's address for this phone (UnifiedPush), once it gave one. */
    val endpoint: String? = null,
    /** The push app the address comes from, by package. */
    val distributor: String? = null,
    /** Why there is no address, when the push app said so. */
    val pushProblem: String? = null,
    /** How often the app looks at the machines itself while closed, in minutes; 0 never. */
    val checkMinutes: Int = 15,
    /** Live Updates for running board tasks and agents (herdr-push then also sends what runs). */
    val live: Boolean = true,
    /** When notifications that can wait are held, to come as one summary later. */
    val quiet: QuietHours = QuietHours(),
    val machines: Map<String, MachinePush> = emptyMap(),
) {
    fun machine(id: String): MachinePush = machines[id] ?: MachinePush()
}

/** One machine's part: its key, whether it is left out, and how its herdr-push stands. */
@Serializable
data class MachinePush(
    /** The key its pushes are sealed with (base64); made once, given to the machine over SSH. */
    val key: String = "",
    /** Left out by the person: no herdr-push there, no looks. */
    val off: Boolean = false,
    /** The address the machine sends to now; null when it isn't set up for this phone. */
    val sentTo: String? = null,
    /** herdr-push's version there. */
    val version: String = "",
    /** What went wrong setting it up, or what it says went wrong sending. */
    val problem: String? = null,
    /** A note that isn't a problem, such as "stops when you log out". */
    val note: String? = null,
    /** When a test notification from it last arrived. */
    val testedAt: Long = 0,
    /** The newest push taken from it (its own clock, seconds): an older one is a replay or a straggler. */
    val lastAt: Long = 0,
    /** Whether its subscription asks for what runs (Live Updates); null before one was made with it. */
    val live: Boolean? = null,
) {
    val keyBytes: ByteArray? get() = key.takeIf { it.isNotEmpty() }?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
}

/** [PushState], kept as a small JSON file in app storage. */
class PushStore(context: Context) {
    private val file = File(context.filesDir, "push.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _state = MutableStateFlow(load())
    val state: StateFlow<PushState> = _state.asStateFlow()

    @Synchronized
    fun change(f: (PushState) -> PushState) {
        val next = f(_state.value)
        if (next == _state.value) return
        val tmp = File(file.parentFile, "push.json.tmp")
        tmp.writeText(json.encodeToString(PushState.serializer(), next))
        tmp.renameTo(file)
        _state.value = next
    }

    fun machine(id: String, f: (MachinePush) -> MachinePush) = change { s -> s.copy(machines = s.machines + (id to f(s.machine(id)))) }

    /** The machine's key, made the first time it is asked for. */
    @Synchronized
    fun key(id: String): ByteArray {
        state.value.machine(id).keyBytes?.let { return it }
        val key = PushSeal.newKey()
        machine(id) { it.copy(key = Base64.getEncoder().encodeToString(key)) }
        return key
    }

    /** Every machine's key, to find which one sealed a push. */
    fun keys(): Map<String, ByteArray> = state.value.machines.mapNotNull { (id, m) -> m.keyBytes?.let { id to it } }.toMap()

    private fun load(): PushState {
        val loaded = runCatching { if (file.exists()) json.decodeFromString(PushState.serializer(), file.readText()) else null }.getOrNull()
            ?: PushState()
        return if (loaded.phoneId.isEmpty()) loaded.copy(phoneId = UUID.randomUUID().toString()) else loaded
    }
}
