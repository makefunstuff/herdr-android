package com.makefunstuff.herdr.ui.common

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** The voice message playing (or paused) in a chat, by its key, and how far along. */
data class VoicePlaying(val key: String, val playing: Boolean, val positionMs: Long, val durationMs: Long)

/** Plays voice messages, one at a time: another one started stops the first. */
class VoicePlayer internal constructor() {
    private val _state = MutableStateFlow<VoicePlaying?>(null)
    val state: StateFlow<VoicePlaying?> = _state.asStateFlow()
    private var player: MediaPlayer? = null

    /** Plays [file] as [key], or pauses it (and goes on) when it is the one playing. */
    fun toggle(key: String, file: File) {
        val now = _state.value
        val p = player
        if (now != null && now.key == key && p != null) {
            if (now.playing) p.pause() else p.start()
            _state.value = now.copy(playing = !now.playing)
            return
        }
        release()
        val m = MediaPlayer()
        try {
            m.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            m.setDataSource(file.path)
            m.prepare()
            m.setOnCompletionListener { _state.value = null; release() }
            m.start()
            player = m
            _state.value = VoicePlaying(key, true, 0, m.duration.toLong())
        } catch (t: Throwable) {
            Log.w("HerdrVoice", "couldn't play ${file.name}", t)
            m.release()
            throw t
        }
    }

    /** Called a few times a second while it plays: how far along. */
    fun tick() {
        val p = player ?: return
        val now = _state.value ?: return
        _state.value = now.copy(positionMs = runCatching { p.currentPosition.toLong() }.getOrDefault(now.positionMs))
    }

    fun release() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        _state.value = null
    }
}

/** A [VoicePlayer] for a screen: stopped when the screen goes. */
@Composable
fun rememberVoicePlayer(): VoicePlayer {
    val player = remember { VoicePlayer() }
    val playing = player.state.collectAsState().value?.playing == true
    LaunchedEffect(player, playing) {
        while (playing) {
            player.tick()
            delay(100)
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    return player
}
