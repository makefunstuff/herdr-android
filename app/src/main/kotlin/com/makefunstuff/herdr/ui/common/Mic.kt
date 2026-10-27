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
import com.makefunstuff.herdr.core.chat.Dictation.Problem
import com.makefunstuff.herdr.core.chat.Hearing

/**
 * Voice messages for a chat's composer: the phone's own speech recognition, on the phone when it
 * can (Android 12 and newer with an on-device recognizer), else the phone's recognizer told to
 * prefer offline, in a language it has ([Hearing]): the closest to the app's language or the
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
    val mic = remember(context) { AndroidMic(context) }
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

/** Set once the microphone was refused with Android ready to ask again: a later refusal without that is for good. */
private const val REFUSED = "refused"

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
    /** How it hears: on the phone first where it can; after a failure, what [Hearing.next] says, from then on. */
    private var attempt = Hearing.Attempt(onDevice = onDevice(context))
    /** Whether [recognizer] was asked which languages it has (Android 13 and newer), and its answer: null when it couldn't say. */
    private var checked = false
    private var supported: List<String>? = null
    /** The language [recognizer] was last asked for, null for its own. */
    private var language: String? = null
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
        // The phone's recognizer gone (turned off, or its app removed since the screen opened): said, not thrown.
        runCatching {
            val r = recognizer ?: make().also { recognizer = it }
            if (checked || !attempt.withLanguage || Build.VERSION.SDK_INT < 33) start(r) else check(r)
        }.onFailure { dictation.failed(Problem.Unavailable) }
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
        checked = false
        supported = null
    }

    private fun make(): SpeechRecognizer {
        val r = if (attempt.onDevice && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)
        r.setRecognitionListener(Listener())
        return r
    }

    /** Starts [r] in the language [Hearing.language] picks from what it has, or in its own. */
    private fun start(r: SpeechRecognizer) {
        val app = appLanguages()
        val phone = phoneLanguages()
        val language = if (attempt.withLanguage) Hearing.language(app, phone, supported) else null
        Log.i(TAG, "listen: ${if (attempt.onDevice) "on-device" else "regular"}, language ${language ?: "its own"} (phone $phone, app $app, it has ${supported?.size ?: "?"})")
        if (!Hearing.starts(attempt, supported, language)) {
            failed(Dictation.Errors.LANGUAGE_UNAVAILABLE, asked = false)
            return
        }
        this.language = language
        r.startListening(intent(language))
    }

    /** Asks [r] which languages it has, then starts it; one that doesn't answer soon is started as if it couldn't say. */
    @RequiresApi(33)
    private fun check(r: SpeechRecognizer) {
        val onDevice = attempt.onDevice
        var answered = false
        fun answer(languages: List<String>?) {
            if (answered) return
            answered = true
            checked = true
            supported = languages
            // Put away, or the recording cancelled, while it was asked: nothing to start.
            if (recognizer !== r || dictation.state.value !is Dictation.State.Recording) return
            runCatching { start(r) }.onFailure { dictation.failed(Problem.Unavailable) }
        }
        main.postDelayed({ answer(null) }, CHECK_MS)
        runCatching {
            r.checkRecognitionSupport(
                intent(null), ContextCompat.getMainExecutor(context),
                object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) = answer(
                        // On the phone only what is downloaded hears; the regular one also has what it hears online.
                        if (onDevice) support.installedOnDeviceLanguages
                        else (support.installedOnDeviceLanguages + support.onlineLanguages).distinct(),
                    )
                    override fun onError(error: Int) = answer(null)
                },
            )
        }.onFailure { answer(null) }
    }

    /** What went wrong after [Hearing.next]: tried at once another way while it still hears, else said. [asked]: a language was asked for. */
    private fun failed(error: Int, asked: Boolean) {
        val next = Hearing.next(attempt, error, asked)
        if (next != null) {
            // The other recognizer: made anew at the next start.
            if (next.onDevice != attempt.onDevice) close()
            attempt = next
            if (dictation.state.value is Dictation.State.Recording) {
                listen()
                return
            }
        }
        dictation.failed(Dictation.problem(error))
    }

    private fun intent(language: String?) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .apply { if (language != null) putExtra(RecognizerIntent.EXTRA_LANGUAGE, language) }
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

    /** The phone's languages, in order (not this app's: those are [appLanguages]). */
    private fun phoneLanguages(): List<String> = Resources.getSystem().configuration.locales.let { l -> List(l.size()) { l[it].toLanguageTag() } }

    /** The languages chosen for this app in the phone's settings (Android 13 and newer), usually none. */
    private fun appLanguages(): List<String> {
        if (Build.VERSION.SDK_INT < 33) return emptyList()
        val l = context.getSystemService(LocaleManager::class.java)?.applicationLocales ?: return emptyList()
        return List(l.size()) { l[it].toLanguageTag() }
    }

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
            Log.i(TAG, "error $error: ${if (attempt.onDevice) "on-device" else "regular"}, language ${language ?: "its own"}")
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && !retried) {
                retried = true
                main.postDelayed({ listen() }, 250)
                return
            }
            failed(error, asked = language != null)
        }

        private fun best(b: Bundle?): String? = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
    }
}

private const val TAG = "HerdrMic"

/** How long a recognizer may take to say which languages it has. */
private const val CHECK_MS = 1_500L
