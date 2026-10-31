package com.makefunstuff.herdr.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.util.Log
import kotlin.concurrent.thread

/**
 * The microphone as 16 kHz mono float, in 30 ms blocks to [onBlock] on a thread of its own. It takes
 * the voice-call source with the phone's echo canceller, so the voice it plays isn't heard as you
 * (that is what lets you talk over it). [stop] closes it.
 */
class MicIn(private val onBlock: (FloatArray) -> Unit) {
    /** One opening of the microphone: a reopen right after a close gets a new one, so the old reader can't carry on. */
    private class Run { @Volatile var on = true }
    @Volatile private var run: Run? = null

    /** Opens the microphone; false if Android gave none (no permission, in use). */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(): Boolean {
        if (run != null) return true
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT, maxOf(min, BLOCK * 4 * 8))
        }.getOrNull()
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            rec?.release()
            Log.w(AudioOut.TAG, "no microphone")
            return false
        }
        val effects = listOfNotNull(
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true } else null,
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true } else null,
        )
        val r = Run()
        run = r
        thread(name = "voice-in") {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
            try {
                rec.startRecording()
                val buf = FloatArray(BLOCK)
                while (r.on) {
                    val n = rec.read(buf, 0, BLOCK, AudioRecord.READ_BLOCKING)
                    if (n < 0) break
                    if (n > 0 && r.on) onBlock(buf.copyOf(n))
                }
            } catch (e: Exception) {
                Log.w(AudioOut.TAG, "microphone failed", e)
            } finally {
                runCatching { rec.stop() }
                effects.forEach { runCatching { it.release() } }
                rec.release()
                r.on = false
                synchronized(this@MicIn) { if (run === r) run = null }
            }
        }
        return true
    }

    @Synchronized
    fun stop() {
        run?.on = false
        run = null
    }

    val isOn get() = run != null

    companion object {
        const val RATE = 16_000
        /** 30 ms. */
        const val BLOCK = 480
    }
}
