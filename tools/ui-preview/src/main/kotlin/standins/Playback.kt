// Stand-in for app/…/ui/common/Playback.kt, which plays with Android's MediaPlayer. Started, a voice
// message stands at PreviewMic.playedTo, playing: the bubble shows how far along.
package com.makefunstuff.herdr.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import preview.PreviewMic
import java.io.File

data class VoicePlaying(val key: String, val playing: Boolean, val positionMs: Long, val durationMs: Long)

class VoicePlayer internal constructor() {
    private val _state = MutableStateFlow<VoicePlaying?>(null)
    val state: StateFlow<VoicePlaying?> = _state.asStateFlow()

    fun toggle(key: String, file: File) {
        val now = _state.value
        _state.value = if (now?.key == key) now.copy(playing = !now.playing) else VoicePlaying(key, true, PreviewMic.playedTo, 0)
    }

    fun tick() {}

    fun release() { _state.value = null }
}

@Composable
fun rememberVoicePlayer(): VoicePlayer = remember { VoicePlayer() }
