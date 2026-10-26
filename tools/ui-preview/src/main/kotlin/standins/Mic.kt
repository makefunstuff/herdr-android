// Stand-in for app/…/ui/common/Mic.kt, which records through Android's MediaRecorder and asks for
// the microphone with the system's dialog. It records what a scene puts in PreviewMic, at once.
package com.makefunstuff.herdr.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.makefunstuff.herdr.core.chat.Recorder
import com.makefunstuff.herdr.data.VoiceStore
import preview.Demo
import preview.PreviewMic

@Composable
fun rememberRecorder(store: VoiceStore, onClip: (Recorder.Clip) -> Unit): Recorder {
    val latest = rememberUpdatedState(onClip)
    return remember {
        val mic = PreviewRecording(store)
        // The clock stands still at the demo's time; once it records, as long after as the scene spoke.
        Recorder(mic, { Demo.NOW + mic.elapsed }) { latest.value(it) }.also { mic.recorder = it }
    }
}

private class PreviewRecording(private val store: VoiceStore) : Recorder.Mic {
    lateinit var recorder: Recorder
    var elapsed = 0L
    private var granted = PreviewMic.allowed
    private var file: java.io.File? = null

    override fun allowed() = granted
    override fun ask() {
        val answer = PreviewMic.answer ?: return
        granted = answer
        recorder.answered(answer, forever = !answer && PreviewMic.forever)
    }
    override fun settings() {}
    override fun start(): Boolean {
        file = store.newFile("ogg").apply { writeBytes(PreviewMic.RECORDING) }
        // Started (its time already taken): the scene's whole message is said by the time it is let go.
        elapsed = PreviewMic.spokeFor
        return true
    }
    // A voice that rises and falls the same way every run, about as loud as the scene says.
    private var ticks = 0
    override fun level() = (PreviewMic.level * SPEECH[ticks++ % SPEECH.size]).coerceIn(0f, 1f)
    override fun stop(): String? = file?.path.also { file = null }
    override fun cancel() { file?.delete(); file = null }
}

/** How loud a few words are, tick by tick, as fractions of the scene's level. */
private val SPEECH = floatArrayOf(0.3f, 0.9f, 1.4f, 1.1f, 0.6f, 1.5f, 1.2f, 0.4f, 0.1f, 0.7f, 1.3f, 1.6f, 0.9f, 0.5f, 1.0f, 0.2f)
