// Stand-in for app/…/ui/common/Mic.kt, which hears through Android's SpeechRecognizer and asks for
// the microphone with the system's dialog. It hears what a scene puts in PreviewMic, at once.
package com.makefunstuff.herdr.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.makefunstuff.herdr.core.chat.Dictation
import preview.Demo
import preview.PreviewMic

@Composable
fun rememberDictation(onText: (text: String, millis: Long) -> Unit): Dictation? {
    val latest = rememberUpdatedState(onText)
    return remember {
        val mic = PreviewHearing()
        // The clock stands still at the demo's time; once it hears, as long after as the scene spoke.
        Dictation(mic, { Demo.NOW + mic.elapsed }) { text, millis -> latest.value(text, millis) }.also { mic.dictation = it }
    }
}

private class PreviewHearing : Dictation.Mic {
    lateinit var dictation: Dictation
    var elapsed = 0L
    private var granted = PreviewMic.allowed

    override fun allowed() = granted
    override fun ask() {
        val answer = PreviewMic.answer ?: return
        granted = answer
        dictation.answered(answer, forever = !answer && PreviewMic.forever)
    }
    override fun settings() {}
    override fun listen() {
        elapsed = PreviewMic.spokeFor
        dictation.level(6f)
        if (PreviewMic.heard.isNotEmpty()) dictation.partial(PreviewMic.heard)
    }
    override fun stop() = dictation.segment(PreviewMic.said)
    override fun cancel() {}
}
