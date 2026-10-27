package com.makefunstuff.herdr.ui.common

import android.Manifest
import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.makefunstuff.herdr.core.chat.Dictation
import com.makefunstuff.herdr.core.chat.RecognizerMic

/**
 * Voice messages for a chat's composer: the phone's own speech recognition, on the phone when it
 * can (Android 12 and newer with an on-device recognizer), else the phone's recognizer told to
 * prefer offline, in a language it has ([RecognizerMic]): the closest to the app's language or the
 * phone's, else the recognizer's own. [onText] gets the words heard and how long you spoke. Null on
 * a phone without speech recognition: the composer shows no mic.
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
    val phone = remember(context) { AndroidPhone(context) }
    val mic = remember(phone) { RecognizerMic(phone, onDevice(context)).also { phone.mic = it } }
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
    phone.ask = { ask.launch(Manifest.permission.RECORD_AUDIO) }
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

private fun onDevice(context: Context): Boolean =
    Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** [RecognizerMic.Phone] on Android: its microphone permission and its [SpeechRecognizer]s. */
private class AndroidPhone(private val context: Context) : RecognizerMic.Phone {
    lateinit var mic: RecognizerMic
    var ask: () -> Unit = {}
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

    override fun recognizer(onDevice: Boolean): RecognizerMic.Recognizer {
        val r = if (onDevice && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)
        r.setRecognitionListener(Listener())
        return AndroidRecognizer(r, onDevice)
    }

    override fun phoneLanguages(): List<String> = Resources.getSystem().configuration.locales.let { l -> List(l.size()) { l[it].toLanguageTag() } }

    override fun appLanguages(): List<String> {
        if (Build.VERSION.SDK_INT < 33) return emptyList()
        val l = context.getSystemService(LocaleManager::class.java)?.applicationLocales ?: return emptyList()
        return List(l.size()) { l[it].toLanguageTag() }
    }

    override fun later(ms: Long, run: () -> Unit) { main.postDelayed(run, ms) }

    override fun cancelLater() = main.removeCallbacksAndMessages(null)

    override fun log(line: String) { Log.i(TAG, line) }

    private inner class AndroidRecognizer(private val r: SpeechRecognizer, private val onDevice: Boolean) : RecognizerMic.Recognizer {
        override fun languages(answer: (List<String>?) -> Unit): Boolean {
            if (Build.VERSION.SDK_INT < 33) return false
            check(r, onDevice, answer)
            return true
        }

        override fun start(language: String?, offline: Boolean) = r.startListening(intent(language, offline))
        override fun stop() = r.stopListening()
        override fun cancel() = r.cancel()
        override fun destroy() = r.destroy()
    }

    @RequiresApi(33)
    private fun check(r: SpeechRecognizer, onDevice: Boolean, answer: (List<String>?) -> Unit) {
        r.checkRecognitionSupport(
            intent(null, offline = true), ContextCompat.getMainExecutor(context),
            object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) = answer(
                    // On the phone only what is downloaded hears; the regular one also has what it hears online.
                    if (onDevice) support.installedOnDeviceLanguages
                    else (support.installedOnDeviceLanguages + support.onlineLanguages).distinct(),
                )
                override fun onError(error: Int) = answer(null)
            },
        )
    }

    private fun intent(language: String?, offline: Boolean) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .apply { if (language != null) putExtra(RecognizerIntent.EXTRA_LANGUAGE, language) }
        .apply { if (offline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true) }
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

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
