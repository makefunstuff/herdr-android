package com.makefunstuff.herdr.voice

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * The phone's own voice and ears, for when the voice server can't be reached: Android's text to
 * speech, and its speech recogniser (which listens on its own and knows when you stopped).
 */
class PhoneSpeech(private val context: Context) {
    @Volatile private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private val main = Handler(Looper.getMainLooper())
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    val canHear get() = SpeechRecognizer.isRecognitionAvailable(context)

    private suspend fun engine(): TextToSpeech? {
        tts?.let { return it }
        val ready = CompletableDeferred<Boolean>()
        val t = withContext(Dispatchers.Main) { TextToSpeech(context) { ready.complete(it == TextToSpeech.SUCCESS) } }
        if (!ready.await()) { t.shutdown(); return null }
        t.setAudioAttributes(attributes)
        t.language = Locale.getDefault()
        tts = t
        return t
    }

    /** Says [text] at [speed]; [onSound] when it is first heard. False if there is no voice or it was stopped. */
    suspend fun say(text: String, speed: Float, onSound: () -> Unit = {}): Boolean {
        val t = engine() ?: return false
        t.setSpeechRate(speed)
        val id = UUID.randomUUID().toString()
        return suspendCancellableCoroutine { cont ->
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) { if (utteranceId == id) onSound() }
                override fun onDone(utteranceId: String) { if (utteranceId == id && cont.isActive) cont.resume(true) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String) { if (utteranceId == id && cont.isActive) cont.resume(false) }
                override fun onStop(utteranceId: String, interrupted: Boolean) { if (utteranceId == id && cont.isActive) cont.resume(false) }
            })
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            cont.invokeOnCancellation { t.stop() }
        }
    }

    fun hush() { tts?.stop() }

    /**
     * Hears one thing you say with the phone's recogniser: the words, or "" when nothing was heard.
     * [level] gets how loud you are, 0..1; [stopHearing] ends it with what was heard.
     */
    suspend fun hear(level: (Float) -> Unit): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
            r.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    val words = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (cont.isActive) cont.resume(words)
                }
                override fun onError(error: Int) { if (cont.isActive) cont.resume("") }
                override fun onRmsChanged(rmsdB: Float) = level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName))
            cont.invokeOnCancellation { main.post { runCatching { r.cancel() } } }
        }
    }

    /** Ends hearing now, with what was heard so far. */
    fun stopHearing() = main.post { runCatching { recognizer?.stopListening() } }

    fun close() {
        tts?.shutdown(); tts = null
        main.post { recognizer?.destroy(); recognizer = null }
    }
}
