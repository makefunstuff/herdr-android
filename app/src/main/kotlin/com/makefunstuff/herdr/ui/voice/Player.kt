package com.makefunstuff.herdr.ui.voice

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The screen's one player for voice messages, on Android's [MediaPlayer]; let go when the screen goes. */
@Composable
fun rememberClipPlayer(): ClipPlayer {
    val scope = rememberCoroutineScope()
    val player = remember { AndroidClipPlayer(scope) }
    DisposableEffect(player) { onDispose { player.stop() } }
    return player
}

private class AndroidClipPlayer(private val scope: CoroutineScope) : ClipPlayer {
    private val _now = MutableStateFlow<ClipPlayer.Play?>(null)
    override val now: StateFlow<ClipPlayer.Play?> = _now.asStateFlow()
    private var media: MediaPlayer? = null
    private var path: String? = null
    private var ticking: Job? = null

    override fun toggle(path: String) {
        val m = media
        if (m != null && this.path == path) {
            if (m.isPlaying) m.pause() else m.start()
            show()
            return
        }
        stop()
        val next = MediaPlayer()
        try {
            next.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            next.setDataSource(path)
            next.prepare()
            // Played to its end: back to the start, as it was before.
            next.setOnCompletionListener { stop() }
            next.start()
        } catch (e: Exception) {
            next.release()
            _now.value = ClipPlayer.Play(path, playing = false, position = 0, duration = 0, failed = true)
            return
        }
        media = next
        this.path = path
        show()
        ticking = scope.launch {
            while (true) {
                delay(100)
                show()
            }
        }
    }

    override fun stop() {
        ticking?.cancel()
        ticking = null
        media?.let { runCatching { it.stop() }; it.release() }
        media = null
        path = null
        _now.value = null
    }

    private fun show() {
        val m = media ?: return
        val p = path ?: return
        _now.value = ClipPlayer.Play(p, m.isPlaying, m.currentPosition.toLong(), m.duration.toLong().coerceAtLeast(0))
    }
}
