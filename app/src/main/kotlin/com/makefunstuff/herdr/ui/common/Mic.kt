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
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import com.makefunstuff.herdr.core.chat.Recorder
import com.makefunstuff.herdr.core.chat.Recorder.Problem
import com.makefunstuff.herdr.data.VoiceStore
import java.io.File
import kotlin.math.sqrt

/**
 * Voice messages for a chat's composer: the phone's microphone records what you say to a small
 * audio file in [store] (Ogg Opus, which every harness that takes audio reads; AAC on Android 9,
 * which has no Opus encoder), and [onClip] gets it. Nothing is turned into text on the phone.
 *
 * The microphone is asked for on first use, from the screen; refused, the composer explains why it
 * is needed, and once Android stops asking it offers the app's page in the phone's settings.
 */
@Composable
fun rememberRecorder(store: VoiceStore, onClip: (Recorder.Clip) -> Unit): Recorder {
    val context = LocalContext.current
    val latest = rememberUpdatedState(onClip)
    val mic = remember(context) { AndroidMic(context, store) }
    val recorder = remember(mic) { Recorder(mic, System::currentTimeMillis) { latest.value(it) }.also { mic.recorder = it } }
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
    mic.ask = { ask.launch(Manifest.permission.RECORD_AUDIO) }
    // The app put away, or the chat left, while it records: what was said so far is kept and waits in
    // the composer (Android gives an app in the background only silence).
    LifecycleStartEffect(recorder) {
        onStopOrDispose {
            recorder.stop()
            mic.cancel()
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

/** [Recorder.Mic] on Android's [MediaRecorder], one per recording. */
private class AndroidMic(private val context: Context, private val store: VoiceStore) : Recorder.Mic {
    lateinit var recorder: Recorder
    var ask: () -> Unit = {}
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
        cancel()
        val opus = Build.VERSION.SDK_INT >= 29
        val target = store.newFile(if (opus) "ogg" else "aac")
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            if (opus) {
                r.setOutputFormat(MediaRecorder.OutputFormat.OGG)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                r.setAudioSamplingRate(48_000)
                r.setAudioEncodingBitRate(24_000)
            } else {
                r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioSamplingRate(44_100)
                r.setAudioEncodingBitRate(48_000)
            }
            r.setAudioChannels(1)
            r.setOutputFile(target.path)
            r.setOnErrorListener { _, _, _ -> recorder.failed(Problem.Audio) }
            r.prepare()
            r.start()
            media = r
            file = target
            true
        } catch (e: Exception) {
            r.release()
            target.delete()
            false
        }
    }

    // MediaRecorder's loudest sample since it was last asked, as a level that follows the ear.
    override fun level(): Float = media?.let { runCatching { sqrt(it.maxAmplitude / 32767f) }.getOrNull() } ?: 0f

    override fun stop(): String? {
        val r = media ?: return null
        val f = file
        media = null
        file = null
        // stop() throws when nothing was written yet: no recording then.
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        if (!ok || f == null || f.length() == 0L) {
            f?.delete()
            return null
        }
        return f.path
    }

    override fun cancel() {
        val r = media ?: return
        media = null
        runCatching { r.stop() }
        r.release()
        file?.delete()
        file = null
    }
}
