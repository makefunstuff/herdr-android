// Stand-in for app/…/ui/voice/Player.kt, which plays through Android's MediaPlayer. It plays no
// sound: what it is told to play shows as playing, PreviewPlayer.position into it.
package com.makefunstuff.herdr.ui.voice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import preview.PreviewPlayer

@Composable
fun rememberClipPlayer(): ClipPlayer = remember { StillPlayer() }

private class StillPlayer : ClipPlayer {
    private val _now = MutableStateFlow<ClipPlayer.Play?>(null)
    override val now: StateFlow<ClipPlayer.Play?> = _now.asStateFlow()

    override fun toggle(path: String) {
        val play = _now.value
        _now.value = if (play?.path == path) play.copy(playing = !play.playing) else ClipPlayer.Play(path, true, PreviewPlayer.position, 0)
    }

    override fun stop() { _now.value = null }
}
