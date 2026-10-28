package com.makefunstuff.herdr.ui.common

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.makefunstuff.herdr.core.chat.Dictation
import com.makefunstuff.herdr.core.chat.RecognizerMic

/**
 * Voice messages for a chat's composer: the phone's own speech recognition ([RecognizerMic]), set up
 * as in apps whose voice input works on the same phones: the regular recognizer on the application
 * context, asked for free-form speech with partial results and nothing more (no language, no offline
 * preference: it hears in the one chosen for voice typing). When it fails before any words, the
 * phone's own speech dialog (`ACTION_RECOGNIZE_SPEECH`, Google's voice UI) hears instead. [onText]
 * gets the words heard and how long you spoke. Null on a phone without speech recognition: the
 * composer shows no mic. Each start, error and dialog answer is logged (tag `HerdrMic`).
 *
 * The microphone is asked for on first use, from the screen; refused, the composer explains why it
 * is needed, and once Android stops asking it offers the app's page in the phone's settings.
 */
@Composable
fun rememberDictation(onText: (text: String, millis: Long) -> Unit): Dictation? {
    val context = LocalContext.current
    val available = remember(context) { SpeechRecognizer.isRecognitionAvailable(context) }
    if (!available) return null
    val latest = rememberUpdatedState(onText)
    val phone = remember(context) { AndroidPhone(context) }
    val mic = remember(phone) { RecognizerMic(phone).also { phone.mic = it } }
    val dictation = remember(mic) { Dictation(mic, System::currentTimeMillis) { text, millis -> latest.value(text, millis) }.also { mic.dictation = it } }
    val asked = remember(context) { context.getSharedPreferences("mic", Context.MODE_PRIVATE) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Refused, and Android won't ask again: only the settings can allow it now. Android says so
        // only by no longer wanting a reason shown, which is also the case before it ever asked and
        // after its question was closed unanswered the first time: those still offer Allow.
        val rationale = context.activity()?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == true
        val forever = !granted && !rationale && asked.getBoolean(REFUSED, false)
        if (!granted && rationale) asked.edit().putBoolean(REFUSED, true).apply()
        dictation.answered(granted, forever)
    }
    val dialog = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val words = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        Log.i(TAG, "dialog result ${result.resultCode}")
        // Closed, or it heard nothing (it said so itself): nothing more to say. Its errors come after RESULT_NO_MATCH.
        mic.dialog(words, failed = words.isNullOrBlank() && result.resultCode > RecognizerIntent.RESULT_NO_MATCH)
    }
    phone.ask = { ask.launch(Manifest.permission.RECORD_AUDIO) }
    phone.dialog = {
        try {
            dialog.launch(intent().putExtra(RecognizerIntent.EXTRA_PROMPT, "Say your message"))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
    DisposableEffect(mic) {
        onDispose {
            // Off the screen (or the app put away with it): nothing is heard any more.
            dictation.cancel()
            mic.close()
        }
    }
    return dictation
}

/** Set once the microphone was refused with Android ready to ask again: a later refusal without that is for good. */
private const val REFUSED = "refused"

/** What the recognizer and the dialog are asked for: free-form speech, the words as they come. Nothing else. */
private fun intent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** [RecognizerMic.Phone] on Android: its microphone permission, its [SpeechRecognizer] and its speech dialog. */
private class AndroidPhone(private val context: Context) : RecognizerMic.Phone {
    lateinit var mic: RecognizerMic
    var ask: () -> Unit = {}
    var dialog: () -> Boolean = { false }
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

    override fun recognizer(): RecognizerMic.Recognizer {
        val r = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
        r.setRecognitionListener(Listener())
        return object : RecognizerMic.Recognizer {
            override fun start() = r.startListening(intent())
            override fun stop() = r.stopListening()
            override fun cancel() = r.cancel()
            override fun destroy() = r.destroy()
        }
    }

    override fun dialog() = dialog.invoke()

    override fun later(ms: Long, run: () -> Unit) { main.postDelayed(run, ms) }

    override fun cancelLater() = main.removeCallbacksAndMessages(null)

    override fun log(line: String) { Log.i(TAG, line) }

    private inner class Listener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = mic.ready()
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) = mic.dictation.level(rmsdB)
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) { best(partialResults)?.let(mic.dictation::partial) }
        override fun onResults(results: Bundle?) = mic.dictation.segment(best(results).orEmpty())
        override fun onError(error: Int) = mic.error(error)

        private fun best(b: Bundle?): String? = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
    }
}

private const val TAG = "HerdrMic"
