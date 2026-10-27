package com.makefunstuff.herdr.core.chat

import com.makefunstuff.herdr.core.chat.Dictation.Errors
import com.makefunstuff.herdr.core.chat.Dictation.Problem

/**
 * [Dictation.Mic] on the phone's speech recognizers ([Phone], Android's `SpeechRecognizer` in the
 * app): which one hears and in which language, as [Hearing] says. A recognizer is made when first
 * needed and kept while the chat is open; before it first starts it is asked which languages it has
 * (Android 13 and newer), and one that doesn't answer within [CHECK_MS] is started as if it couldn't
 * say. What fails is tried again another way while it still hears ([Hearing.next]), and stays that
 * way from then on. Every call comes from the main thread.
 */
class RecognizerMic(private val phone: Phone, onDevice: Boolean) : Dictation.Mic {
    /** The phone's side. */
    interface Phone {
        fun allowed(): Boolean
        fun ask()
        fun settings()
        /** A new recognizer, [onDevice] or the phone's regular one, telling this mic what it hears; throws when there is none. */
        fun recognizer(onDevice: Boolean): Recognizer
        /** The languages chosen for this app in the phone's settings, usually none. */
        fun appLanguages(): List<String>
        /** The phone's languages, in order. */
        fun phoneLanguages(): List<String>
        /** Runs [run] on the main thread [ms] from now, unless [cancelLater] comes first. */
        fun later(ms: Long, run: () -> Unit)
        fun cancelLater()
        fun log(line: String)
    }

    /** One of the phone's recognizers; what it hears comes back through [ready], [error] and [dictation]. */
    interface Recognizer {
        /**
         * Asks which languages it has: [answer] gets them, null when it can't say, on the main thread.
         * False when it can't be asked (Android 12 and older): [answer] never comes.
         */
        fun languages(answer: (List<String>?) -> Unit): Boolean
        /**
         * Starts hearing in [language], null for its own; [offline]: told to prefer hearing on the
         * phone, else asked plainly, as Gboard does.
         */
        fun start(language: String?, offline: Boolean)
        fun stop()
        fun cancel()
        fun destroy()
    }

    lateinit var dictation: Dictation
    private var recognizer: Recognizer? = null
    /** How it hears: on the phone first where it can; after a failure, what [Hearing.next] says. */
    var attempt = Hearing.Attempt(onDevice)
        private set
    /** Whether [recognizer] was asked which languages it has, and its answer: null when it couldn't say. */
    private var checked = false
    private var supported: List<String>? = null
    /** The language [recognizer] was last asked for, null for its own. */
    private var language: String? = null
    /** Started again too soon after a stretch (some recognizers are still busy then): once more, a moment later. */
    private var retried = false

    override fun allowed() = phone.allowed()
    override fun ask() = phone.ask()
    override fun settings() = phone.settings()

    override fun listen() {
        // The phone's recognizer gone (turned off, or its app removed since the screen opened): said, not thrown.
        runCatching {
            val r = recognizer ?: phone.recognizer(attempt.onDevice).also { recognizer = it }
            if (checked || !attempt.withLanguage || !check(r)) start(r)
        }.onFailure { dictation.failed(Problem.Unavailable) }
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
        checked = false
        supported = null
    }

    /** The recognizer is ready for speech. */
    fun ready() { retried = false }

    /** The recognizer stopped with Android's `SpeechRecognizer.ERROR_*` [error]. */
    fun error(error: Int) {
        phone.log("error $error: ${if (attempt.onDevice) "on-device" else "regular"}, language ${language ?: "its own"}")
        if (error == Errors.RECOGNIZER_BUSY && !retried) {
            retried = true
            phone.later(BUSY_MS) { listen() }
            return
        }
        failed(error, asked = language != null)
    }

    /** Asks [r] which languages it has, and starts it with the answer; false when it can't be asked. */
    private fun check(r: Recognizer): Boolean {
        var answered = false
        fun answer(languages: List<String>?) {
            if (answered) return
            answered = true
            checked = true
            supported = languages
            // Put away, or the recording cancelled or finished, while it was asked: nothing to start.
            if (recognizer !== r || dictation.state.value !is Dictation.State.Recording) return
            runCatching { start(r) }.onFailure { dictation.failed(Problem.Unavailable) }
        }
        if (!runCatching { r.languages(::answer) }.getOrDefault(false)) {
            answered = true
            checked = true
            return false
        }
        phone.later(CHECK_MS) { answer(null) }
        return true
    }

    /** Starts [r] in the language [Hearing.language] picks from what it has, or in its own. */
    private fun start(r: Recognizer) {
        val app = phone.appLanguages()
        val phoneLanguages = phone.phoneLanguages()
        val language = if (attempt.withLanguage) Hearing.language(app, phoneLanguages, supported) else null
        phone.log("listen: ${if (attempt.onDevice) "on-device" else "regular"}, language ${language ?: "its own"}${if (attempt.offline) "" else ", online too"} (phone $phoneLanguages, app $app, it has ${supported?.size ?: "?"})")
        if (!Hearing.starts(attempt, supported, language)) {
            failed(Errors.LANGUAGE_UNAVAILABLE, asked = false)
            return
        }
        this.language = language
        // The last way left asks as plainly as Gboard and other apps whose voice input works on the same phone.
        r.start(language, offline = attempt.offline)
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

    companion object {
        /** How long a recognizer may take to say which languages it has. */
        const val CHECK_MS = 1_500L
        /** How long to wait before starting a busy recognizer again. */
        const val BUSY_MS = 250L
    }
}
