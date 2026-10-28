package com.makefunstuff.herdr.core.chat

import com.makefunstuff.herdr.core.chat.Dictation.Errors
import com.makefunstuff.herdr.core.chat.Dictation.Problem

/**
 * [Dictation.Mic] on the phone's speech recognition ([Phone], Android's in the app), set up as in
 * apps whose voice input works on the same phones: the phone's regular recognizer, asked plainly
 * (free-form speech, partial results; no language and no offline preference, so it hears in the one
 * chosen for voice typing). A recognizer is made when first needed and kept while the chat is open.
 *
 * When it fails before any words for another reason than hearing nothing or having no microphone
 * (a language it turns down, no server, no recognizer at all), the phone's own speech dialog
 * (Google's voice UI) hears instead, and what it hears goes into the draft the same way. Only when
 * that fails too is the recognizer's error said. Every call comes from the main thread.
 */
class RecognizerMic(private val phone: Phone) : Dictation.Mic {
    /** The phone's side. */
    interface Phone {
        fun allowed(): Boolean
        fun ask()
        fun settings()
        /** The phone's regular recognizer, telling this mic what it hears; throws when there is none. */
        fun recognizer(): Recognizer
        /** Opens the phone's speech dialog; its answer comes back through [RecognizerMic.dialog]. False when there is none. */
        fun dialog(): Boolean
        /** Runs [run] on the main thread [ms] from now, unless [cancelLater] comes first. */
        fun later(ms: Long, run: () -> Unit)
        fun cancelLater()
        fun log(line: String)
    }

    /** The phone's recognizer; what it hears comes back through [ready], [error] and [dictation]. */
    interface Recognizer {
        fun start()
        fun stop()
        fun cancel()
        fun destroy()
    }

    lateinit var dictation: Dictation
    private var recognizer: Recognizer? = null
    /** Started again too soon after a stretch (some recognizers are still busy then): once more, a moment later. */
    private var retried = false
    /** What the recognizer failed with when the dialog took over: said if the dialog fails too. */
    private var handedOver = Problem.Other

    override fun allowed() = phone.allowed()
    override fun ask() = phone.ask()
    override fun settings() = phone.settings()

    override fun listen() {
        // The phone's recognizer gone (turned off, or its app removed since the screen opened): the dialog, not a throw.
        val r = runCatching { recognizer ?: phone.recognizer().also { recognizer = it } }.getOrNull()
        if (r == null) {
            phone.log("no recognizer")
            failed(Errors.CLIENT, Problem.Unavailable)
            return
        }
        phone.log("listen")
        runCatching { r.start() }.onFailure { failed(Errors.CLIENT, Problem.Unavailable) }
    }

    override fun stop() { recognizer?.stop() }

    override fun cancel() {
        phone.cancelLater()
        recognizer?.cancel()
    }

    /** Off the screen: the recognizer goes. */
    fun close() {
        phone.cancelLater()
        recognizer?.destroy()
        recognizer = null
    }

    /** The recognizer is ready for speech. */
    fun ready() { retried = false }

    /** The recognizer stopped with Android's `SpeechRecognizer.ERROR_*` [error]. */
    fun error(error: Int) {
        phone.log("error $error")
        if (error == Errors.RECOGNIZER_BUSY && !retried) {
            retried = true
            phone.later(BUSY_MS) { if (dictation.state.value is Dictation.State.Recording) listen() }
            return
        }
        failed(error, Dictation.problem(error))
    }

    /**
     * The phone's speech dialog answered: [text] heard, or none; [failed]: it went wrong (closed or
     * hearing nothing is not failing: it showed that itself).
     */
    fun dialog(text: String?, failed: Boolean) {
        phone.log("dialog: ${if (!text.isNullOrBlank()) "words" else if (failed) "failed" else "nothing"}")
        dictation.fromDialog(text, handedOver.takeIf { failed })
    }

    /** The recognizer couldn't hear: before any words and for a reason the dialog may not have, the dialog hears instead. */
    private fun failed(error: Int, problem: Problem) {
        val s = dictation.state.value
        val heard = when (s) {
            is Dictation.State.Recording -> s.text
            is Dictation.State.Finishing -> s.text
            else -> return
        }
        if (heard.isBlank() && error !in KEEP) {
            handedOver = problem
            if (runCatching { phone.dialog() }.getOrDefault(false)) {
                phone.log("dialog opened for error $error")
                dictation.away()
                return
            }
            phone.log("no dialog")
        }
        dictation.failed(problem)
    }

    companion object {
        /** How long to wait before starting a busy recognizer again. */
        const val BUSY_MS = 250L

        /** Errors the recognizer is right after, which the dialog wouldn't help: nothing was said, or the microphone isn't allowed. */
        private val KEEP = setOf(Errors.NO_MATCH, Errors.SPEECH_TIMEOUT, Errors.INSUFFICIENT_PERMISSIONS)
    }
}
