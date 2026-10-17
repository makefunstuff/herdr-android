package com.makefunstuff.herdr.brief

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import com.makefunstuff.herdr.core.brief.BriefLine
import com.makefunstuff.herdr.push.WorkService
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * [BriefVoice] on Android: the phone's text to speech, one sentence at a time, as an assistant's
 * voice that ducks the music (and plays through the car when the phone is connected to it). It reads
 * in [WorkService], a foreground service, so it goes on with the screen off or the app closed.
 */
class AndroidVoice(private val context: Context) : BriefVoice {
    private val _reading = MutableStateFlow<Reading?>(null)
    override val reading: StateFlow<Reading?> = _reading.asStateFlow()
    @Volatile private var tts: TextToSpeech? = null
    private val speaking = Mutex()
    private val waiting = ConcurrentHashMap<String, CancellableContinuation<Unit>>()
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val audio = context.getSystemService(AudioManager::class.java)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes).build()

    override fun read(lines: List<BriefLine>, briefAt: Long) {
        prepare(lines, briefAt)
        ContextCompat.startForegroundService(context, Intent(context, WorkService::class.java).setAction(WorkService.READ))
    }

    /** Sets what [speak] reads, from its first sentence, without starting the service (it runs already). */
    fun prepare(lines: List<BriefLine>, briefAt: Long) {
        _reading.value = Reading(lines, 0, briefAt)
        tts?.stop()
    }

    override fun next() {
        _reading.update { it?.copy(index = it.index + 1) }
        tts?.stop()
    }

    override fun stop() {
        _reading.value = null
        tts?.stop()
    }

    /**
     * Reads what is set, sentence by sentence, until the end or [stop]; [next] skips, and a new
     * [prepare] starts over with it. The brief read to its end (its time), or null when it was stopped
     * or the phone has no voice.
     */
    suspend fun speak(): Long? = speaking.withLock {
        if (_reading.value == null) return null
        val engine = engine() ?: return null.also { _reading.value = null }
        audio.requestAudioFocus(focus)
        var done: Long? = null
        try {
            while (true) {
                val r = _reading.value ?: break
                val line = r.line
                if (line == null) {
                    done = r.briefAt
                    _reading.value = null
                    break
                }
                say(engine, line.text, "${r.briefAt}:${r.index}")
                // Moved on meanwhile (Next, or read again from the start): carry on from there.
                _reading.update { if (it === r) it.copy(index = it.index + 1) else it }
            }
            done
        } finally {
            audio.abandonAudioFocusRequest(focus)
            tts = null
            engine.shutdown()
        }
    }

    /** One sentence, until it is said or stopped. */
    private suspend fun say(engine: TextToSpeech, text: String, id: String) = suspendCancellableCoroutine<Unit> { cont ->
        waiting[id] = cont
        cont.invokeOnCancellation { waiting.remove(id); engine.stop() }
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, id) != TextToSpeech.SUCCESS) {
            waiting.remove(id)?.resume(Unit)
        }
    }

    private fun finished(id: String?) {
        id?.let { waiting.remove(it)?.resume(Unit) }
    }

    /** The phone's voice, in English (the brief's words are), or null when there is none. */
    private suspend fun engine(): TextToSpeech? {
        var made: TextToSpeech? = null
        val ok = suspendCancellableCoroutine<Boolean> { cont ->
            made = TextToSpeech(context.applicationContext) { status -> if (cont.isActive) cont.resume(status == TextToSpeech.SUCCESS) }
        }
        val engine = made ?: return null
        if (!ok) return null.also { engine.shutdown() }
        engine.setAudioAttributes(attributes)
        val phone = Locale.getDefault()
        val language = listOf(if (phone.language == "en") phone else null, Locale.US, Locale.UK, Locale.ENGLISH)
            .firstOrNull { it != null && engine.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE }
        language?.let { engine.language = it }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finished(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = finished(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
        })
        tts = engine
        return engine
    }
}
