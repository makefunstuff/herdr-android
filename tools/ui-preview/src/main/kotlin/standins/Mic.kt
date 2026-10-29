// Stand-in for app/…/ui/common/Mic.kt, which records with Android's MediaRecorder and asks for the
// microphone with the system's dialog. It records what a scene puts in PreviewMic: a small file, at once.
package com.makefunstuff.herdr.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.makefunstuff.herdr.core.chat.VoiceRecorder
import com.makefunstuff.herdr.data.VoiceStore
import preview.Demo
import preview.PreviewMic
import java.io.File

@Composable
fun rememberRecorder(store: VoiceStore, onRecorded: (file: File, millis: Long) -> Unit): VoiceRecorder? {
    val latest = rememberUpdatedState(onRecorded)
    return remember {
        val mic = PreviewRecording(store)
        // The clock stands still at the demo's time; once it records, as long after as the scene spoke.
        VoiceRecorder(mic, { Demo.NOW + mic.elapsed }) { path, millis -> latest.value(File(path), millis) }.also { mic.recorder = it }
    }
}

private class PreviewRecording(private val store: VoiceStore) : VoiceRecorder.Mic {
    lateinit var recorder: VoiceRecorder
    var elapsed = 0L
    private var granted = PreviewMic.allowed

    override fun allowed() = granted
    override fun ask() {
        val answer = PreviewMic.answer ?: return
        granted = answer
        recorder.answered(answer, forever = !answer && PreviewMic.forever)
    }
    override fun settings() {}
    override fun start(): Boolean {
        elapsed = PreviewMic.spokeFor
        return true
    }
    override fun stop(): String = store.newFile().apply { writeBytes(ByteArray(64)) }.path
    override fun cancel() {}
    override fun level() = PreviewMic.level
}
