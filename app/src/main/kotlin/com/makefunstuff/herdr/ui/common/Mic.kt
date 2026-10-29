package com.makefunstuff.herdr.ui.common

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.makefunstuff.herdr.core.chat.VoiceRecorder
import com.makefunstuff.herdr.data.VoiceStore
import java.io.File
import kotlin.math.log10

/**
 * Voice messages for a chat's composer: the phone's microphone records AAC audio (an `.m4a` file,
 * small enough for a long message: about 360 KB a minute) into [store]'s folder, and [onRecorded]
 * gets the file and how long it is. Null on a phone without a microphone: the composer shows no mic.
 * Each start and failure is logged (tag `HerdrMic`).
 *
 * The microphone is asked for on first use, from the screen; refused, the composer explains why it
 * is needed, and once Android stops asking it offers the app's page in the phone's settings. The app
 * put away mid-recording sends what was said so far (Android gives a closed app no microphone).
 */
@Composable
fun rememberRecorder(store: VoiceStore, onRecorded: (file: File, millis: Long) -> Unit): VoiceRecorder? {
    val context = LocalContext.current
    val available = remember(context) { context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) }
    if (!available) return null
    val latest = rememberUpdatedState(onRecorded)
    val phone = remember(context) { AndroidMic(context, store) }
    val recorder = remember(phone) {
        VoiceRecorder(phone, System::currentTimeMillis) { path, millis -> latest.value(File(path), millis) }.also { phone.recorder = it }
    }
    val asked = remember(context) { context.getSharedPreferences("mic", Context.MODE_PRIVATE) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Refused, and Android won't ask again: only the settings can allow it now. Android says so
        // only by no longer wanting a reason shown, which is also the case before it ever asked and
        // after its question was closed unanswered the first time: those still offer Allow.
        val rationale = context.activity()?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == true
        val forever = !granted && !rationale && asked.getBoolean(REFUSED, false)
        if (!granted && rationale) asked.edit().putBoolean(REFUSED, true).apply()
        recorder.answered(granted, forever)
    }
    phone.ask = { ask.launch(Manifest.permission.RECORD_AUDIO) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(recorder, lifecycle) {
        val away = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) recorder.interrupted() }
        lifecycle.addObserver(away)
        onDispose {
            lifecycle.removeObserver(away)
            // Off the screen: nothing is recorded any more, and what was is dropped.
            recorder.cancel()
        }
    }
    return recorder
}

/** Set once the microphone was refused with Android ready to ask again: a later refusal without that is for good. */
private const val REFUSED = "refused"

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** [VoiceRecorder.Mic] on Android: its microphone permission and a [MediaRecorder]. */
private class AndroidMic(private val context: Context, private val store: VoiceStore) : VoiceRecorder.Mic {
    lateinit var recorder: VoiceRecorder
    var ask: () -> Unit = {}
    private val main = Handler(Looper.getMainLooper())
    private var media: MediaRecorder? = null
    private var file: File? = null

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

    override fun start(): Boolean {
        val f = store.newFile()
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(48_000)
            // The recorder's own stop, a little after VoiceRecorder's: it can only end it early, never lose it.
            r.setMaxDuration((VoiceRecorder.MAX_MS + 5_000).toInt())
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) main.post { recorder.interrupted() }
            }
            r.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "recorder error $what/$extra")
                main.post { recorder.interrupted() }
            }
            r.setOutputFile(f.path)
            r.prepare()
            r.start()
            media = r
            file = f
            Log.i(TAG, "recording")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "couldn't record", t)
            r.release()
            f.delete()
            false
        }
    }

    override fun stop(): String? {
        val r = media ?: return null
        val f = file
        media = null
        file = null
        // stop() throws when nothing was written yet.
        val ok = runCatching { r.stop() }.onFailure { Log.w(TAG, "stop failed", it) }.isSuccess
        r.release()
        if (!ok || f == null || f.length() == 0L) {
            f?.delete()
            return null
        }
        return f.path
    }

    override fun cancel() {
        media?.let { r ->
            runCatching { r.stop() }
            r.release()
        }
        media = null
        file?.delete()
        file = null
    }

    /** The loudest since the last call, on a scale of decibels: -50 dB and under is silence, 0 the loudest. */
    override fun level(): Float {
        val amp = runCatching { media?.maxAmplitude ?: 0 }.getOrDefault(0)
        if (amp <= 0) return 0f
        return ((20 * log10(amp / 32767.0) + 50) / 50).toFloat().coerceIn(0f, 1f)
    }
}

private const val TAG = "HerdrMic"
