package com.makefunstuff.herdr.ui.common

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.makefunstuff.herdr.core.chat.Dictation
import com.makefunstuff.herdr.core.chat.Dictation.Problem

/**
 * Voice messages for a chat's composer: the phone's own speech recognition, on the phone when it
 * can (Android 12 and newer with an on-device recognizer), else the phone's recognizer told to
 * prefer offline. [onText] gets the words heard and how long you spoke. Null on a phone without
 * speech recognition: the composer shows no mic.
 *
 * The microphone is asked for on first use, from the screen; refused, the composer explains why it
 * is needed, and once Android stops asking it offers the app's page in the phone's settings.
 */
@Composable
fun rememberDictation(onText: (text: String, millis: Long) -> Unit): Dictation? {
    val context = LocalContext.current
    val available = remember(context) { SpeechRecognizer.isRecognitionAvailable(context) || onDevice(context) }
    if (!available) return null
    val latest = rememberUpdatedState(onText)
    val mic = remember(context) { AndroidMic(context) }
    val dictation = remember(mic) { Dictation(mic, System::currentTimeMillis) { text, millis -> latest.value(text, millis) }.also { mic.dictation = it } }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Refused with Android no longer showing its question: only the settings can allow it now.
        val forever = !granted && context.activity()?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == false
        dictation.answered(granted, forever)
    }
    mic.ask = { ask.launch(Manifest.permission.RECORD_AUDIO) }
    DisposableEffect(mic) {
        onDispose {
            // Off the screen (or the app put away with it): nothing is heard any more.
            dictation.cancel()
            mic.close()
        }
    }
    return dictation
}

private fun onDevice(context: Context): Boolean =
    Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** [Dictation.Mic] on Android's [SpeechRecognizer], made when first needed and kept while the chat is open. */
private class AndroidMic(private val context: Context) : Dictation.Mic {
    lateinit var dictation: Dictation
    var ask: () -> Unit = {}
    private var recognizer: SpeechRecognizer? = null
    /** On the phone's own recognizer, after the on-device one couldn't do the language. */
    private var online = !onDevice(context)
    /** Started again too soon after a stretch (some recognizers are still busy then): once more, a moment later. */
    private var retried = false
    private val main = Handler(Looper.getMainLooper())

    override fun allowed() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun ask() = ask.invoke()

    override fun settings() {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    override fun listen() {
        val r = recognizer ?: make().also { recognizer = it }
        runCatching { r.startListening(intent()) }.onFailure { dictation.failed(Problem.Other) }
    }

    override fun stop() { recognizer?.stopListening() }

    override fun cancel() {
        main.removeCallbacksAndMessages(null)
        recognizer?.cancel()
    }

    fun close() {
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
    }

    private fun make(): SpeechRecognizer {
        val r = if (!online && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)
        r.setRecognitionListener(Listener())
        return r
    }

    private fun intent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

    private inner class Listener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { retried = false }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) = dictation.level(rmsdB)
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) { best(partialResults)?.let(dictation::partial) }
        override fun onResults(results: Bundle?) = dictation.segment(best(results).orEmpty())

        override fun onError(error: Int) {
            // The on-device recognizer without the phone's language: the phone's own one, from now on.
            if (!online && (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)) {
                online = true
                close()
                listen()
                return
            }
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && !retried) {
                retried = true
                main.postDelayed({ listen() }, 250)
                return
            }
            dictation.failed(
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Problem.NoSpeech
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> Problem.Busy
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER,
                    SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> Problem.Network
                    SpeechRecognizer.ERROR_AUDIO -> Problem.Audio
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Problem.NoPermission
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> Problem.Language
                    else -> Problem.Other
                },
            )
        }

        private fun best(b: Bundle?): String? = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
    }
}
