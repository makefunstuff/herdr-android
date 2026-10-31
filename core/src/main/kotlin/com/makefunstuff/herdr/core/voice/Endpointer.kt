package com.makefunstuff.herdr.core.voice

/**
 * Tells when the owner starts and stops speaking, from the loudness of each 30 ms block of the microphone. The
 * background level is learnt as it goes, so a quiet room and a noisy street both work. Pure logic, so it is tested
 * without a microphone.
 */
class Endpointer(
    private val blockMs: Int = 30,
    /** Silence after speech that ends a turn. */
    private val endSilenceMs: Int = 800,
    /** Speech shorter than this is a cough or a click, not a turn. */
    private val minSpeechMs: Int = 240,
    /** Nothing said for this long: hands-free stops listening. */
    private val idleMs: Int = 20_000,
    /** The longest turn; it ends there. */
    private val maxTurnMs: Int = 30_000,
) {
    enum class Event { None, Started, Ended, Idle }

    private var noise = 0.004f
    /** Blocks heard so far for the first estimate of the background (the first 240 ms are never speech). */
    private var calibrated = 0
    private var speaking = false
    private var speechMs = 0
    private var silenceMs = 0
    private var waitedMs = 0
    private var voicedRun = 0
    val inSpeech get() = speaking
    /** Silence so far inside a turn: the owner may have finished (see [TurnRecorder]'s early hearing). */
    val pausedMs get() = if (speaking) silenceMs else 0
    /** How long a silence ends a turn. */
    val endMs get() = endSilenceMs

    private companion object { const val CALIBRATION_BLOCKS = 8 }

    fun reset() { speaking = false; speechMs = 0; silenceMs = 0; waitedMs = 0; voicedRun = 0 }

    /** Feeds one block's RMS level (0..1). */
    fun feed(rms: Float): Event {
        if (calibrated < CALIBRATION_BLOCKS) {
            noise = if (calibrated == 0) rms else (noise * calibrated + rms) / (calibrated + 1)
            calibrated++
            waitedMs += blockMs
            return Event.None
        }
        val threshold = maxOf(noise * 3.2f, 0.012f)
        val voiced = rms > threshold
        if (!speaking) {
            // The background level follows quiet blocks quickly and loud ones slowly.
            noise = if (rms < noise) noise * 0.9f + rms * 0.1f else noise * 0.995f + rms * 0.005f
            waitedMs += blockMs
            voicedRun = if (voiced) voicedRun + blockMs else 0
            if (voicedRun >= 90) { speaking = true; speechMs = voicedRun; silenceMs = 0; return Event.Started }
            return if (waitedMs >= idleMs) Event.Idle.also { waitedMs = 0 } else Event.None
        }
        speechMs += blockMs
        silenceMs = if (voiced) 0 else silenceMs + blockMs
        if (silenceMs >= endSilenceMs || speechMs >= maxTurnMs) {
            val enough = speechMs - silenceMs >= minSpeechMs
            reset()
            return if (enough) Event.Ended else Event.None
        }
        return Event.None
    }
}
