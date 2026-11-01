package com.makefunstuff.herdr.data

import com.makefunstuff.herdr.core.voice.EdgeVoice
import com.makefunstuff.herdr.core.voice.VoiceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * How the coordinator's voice sounds and where it comes from: the voice server's address and key
 * (an LFM3 server; the phone's own voice when there is none or it can't be reached), whose voice, how fast,
 * and whether it listens hands-free. Kept in the app's own files through restarts.
 */
class VoiceSettingsStore(private val file: File) {
    @Serializable
    data class Settings(
        val server: String = "",
        val key: String = "",
        /** "female" or "male". */
        val voice: String = "female",
        val speed: Float = 1f,
        val handsFree: Boolean = false,
        val muted: Boolean = false,
    ) {
        /** The server can be asked: an address and a key. */
        val usable get() = key.isNotBlank() && EdgeVoice.normalize(server) != null
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _state = MutableStateFlow(load() ?: Settings())
    val state: StateFlow<Settings> = _state.asStateFlow()

    fun setServer(url: String) = change { it.copy(server = url.trim()) }
    fun setKey(key: String) = change { it.copy(key = key.trim()) }
    fun setVoice(voice: String) = change { it.copy(voice = voice) }
    fun setSpeed(speed: Float) = change { it.copy(speed = speed.coerceIn(VoiceState.MIN_SPEED, VoiceState.MAX_SPEED)) }
    fun setHandsFree(on: Boolean) = change { it.copy(handsFree = on) }
    fun setMuted(on: Boolean) = change { it.copy(muted = on) }

    @Synchronized
    private fun change(f: (Settings) -> Settings) {
        val before = _state.value
        _state.update(f)
        if (_state.value != before) save(_state.value)
    }

    private fun save(next: Settings) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(Settings.serializer(), next))
            tmp.renameTo(file)
        }
    }

    private fun load(): Settings? =
        runCatching { if (file.exists()) json.decodeFromString(Settings.serializer(), file.readText()) else null }.getOrNull()
}
