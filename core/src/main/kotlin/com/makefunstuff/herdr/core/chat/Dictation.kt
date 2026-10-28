package com.makefunstuff.herdr.core.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * A voice message in a chat's composer: the phone's speech recognition hears you and turns what you
 * say into text, which goes into the draft to read (and fix) before it is sent. Nothing is recorded
 * or kept: only the words.
 *
 * The mic button is held while you speak (let go to finish, slide away to cancel) or tapped to start
 * and tapped again to finish. Android's recognizer stops by itself after a pause, so while you still
 * talk it is started again after each stretch ([segment]) and the stretches are joined. Every call
 * comes from the main thread: the UI's and the recognizer's callbacks.
 */
class Dictation(
    private val mic: Mic,
    /** The phone's clock, in milliseconds: how long you spoke. */
    val clock: () -> Long,
    /** The words heard and how long you spoke, once you finish. */
    private val onText: (text: String, millis: Long) -> Unit,
) {
    /** The phone's side: its microphone permission and its speech recognizer. */
    interface Mic {
        /** Whether the app may use the microphone. */
        fun allowed(): Boolean
        /** Asks for the microphone; the answer comes back through [answered]. */
        fun ask()
        /** Opens the app's page in the phone's settings, where a microphone refused for good is allowed. */
        fun settings()
        /** Starts hearing; what it hears comes back through [partial], [segment], [level] and [failed]. */
        fun listen()
        /** Stops hearing: the last words come back as a [segment] (or [Problem.NoSpeech]). */
        fun stop()
        /** Stops hearing and drops what it heard. */
        fun cancel()
    }

    sealed interface State {
        data object Idle : State
        /** The phone asks you for the microphone. */
        data object Asking : State
        /**
         * Hearing you since [startedAt]: [said] is the stretches heard so far, [partial] the one being
         * heard now. [held] while a finger holds the mic (else it was tapped and goes until tapped
         * again); [cancelling] once that finger slid far enough that letting go cancels. [level] is
         * how loud, 0 to 1.
         */
        data class Recording(
            val startedAt: Long,
            val held: Boolean,
            val said: String = "",
            val partial: String = "",
            val cancelling: Boolean = false,
            val level: Float = 0f,
        ) : State {
            val text: String get() = join(said, partial)
        }
        /** You finished at [stoppedAt]; the recognizer makes text of the last words. */
        data class Finishing(val startedAt: Long, val stoppedAt: Long, val said: String, val partial: String) : State {
            val text: String get() = join(said, partial)
        }
        /**
         * The recognizer failed and the phone's own speech dialog hears you instead, over the app, since
         * [since]: what it hears comes back through [fromDialog]. Nothing shows here meanwhile.
         */
        data class Away(val since: Long) : State
        /** No microphone: refused, [forever] when the phone won't ask again (only its settings can allow it). */
        data class NoMic(val forever: Boolean) : State
        /** It went wrong; [message] says what to do. */
        data class Failed(val message: String) : State
    }

    /** A draft with words put in ([insert]), and where the cursor goes: right after them. */
    data class Inserted(val text: String, val cursor: Int)

    /** Why the recognizer stopped without words; [Unavailable]: the phone's recognizer couldn't be started at all. */
    enum class Problem { NoSpeech, Busy, Network, Audio, NoPermission, Language, Unavailable, Other }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** A finger down on the mic: starts hearing (asking for the microphone first), or finishes a tapped recording. */
    fun down() {
        when (val s = _state.value) {
            is State.Recording -> if (!s.held) stop()
            is State.Finishing, State.Asking -> {}
            else -> if (mic.allowed()) start(held = true) else {
                _state.value = State.Asking
                mic.ask()
            }
        }
    }

    /**
     * The finger up again after [heldFor] ms: a quick tap leaves it hearing until the next tap; after
     * a hold it finishes, or cancels when the finger slid away.
     */
    fun up(heldFor: Long) {
        val s = _state.value as? State.Recording ?: return
        if (!s.held) return
        when {
            s.cancelling -> cancel()
            heldFor < TAP_MS -> _state.value = s.copy(held = false)
            else -> stop()
        }
    }

    /** The holding finger is far enough away that letting go cancels ([cancel] true), or back again. */
    fun slide(cancel: Boolean) {
        val s = _state.value as? State.Recording ?: return
        if (s.held && s.cancelling != cancel) _state.value = s.copy(cancelling = cancel)
    }

    /** Finishes: the last words become text, then go into the draft. */
    fun stop() {
        val s = _state.value as? State.Recording ?: return
        _state.value = State.Finishing(s.startedAt, clock(), s.said, s.partial)
        mic.stop()
    }

    /** Drops what was heard (or the question for the microphone). */
    fun cancel() {
        val s = _state.value
        if (s is State.Recording || s is State.Finishing) mic.cancel()
        if (s !is State.Idle) _state.value = State.Idle
    }

    /** Puts away what [State.NoMic] or [State.Failed] said. */
    fun dismiss() {
        val s = _state.value
        if (s is State.NoMic || s is State.Failed) _state.value = State.Idle
    }

    /** Opens the phone's settings for the microphone (from [State.NoMic]). */
    fun settings() {
        dismiss()
        mic.settings()
    }

    /** The phone's answer to [Mic.ask]: allowed, it starts hearing at once (the finger has long left the button). */
    fun answered(granted: Boolean, forever: Boolean) {
        if (_state.value != State.Asking) return
        if (granted) start(held = false) else _state.value = State.NoMic(forever)
    }

    /** Asks for the microphone again, from [State.NoMic]. */
    fun askAgain() {
        if (_state.value !is State.NoMic) return
        _state.value = State.Asking
        mic.ask()
    }

    /** What the recognizer hears so far of the stretch it is on. */
    fun partial(text: String) {
        when (val s = _state.value) {
            is State.Recording -> _state.value = s.copy(partial = text.trim())
            is State.Finishing -> _state.value = s.copy(partial = text.trim())
            else -> {}
        }
    }

    /** A stretch heard to its end: kept, and the recognizer goes on while you still speak. */
    fun segment(text: String) {
        when (val s = _state.value) {
            is State.Recording -> {
                _state.value = s.copy(said = join(s.said, text), partial = "")
                mic.listen()
            }
            is State.Finishing -> finish(s, join(s.said, text.ifBlank { s.partial }))
            else -> {}
        }
    }

    /** The phone's speech dialog opened in the recognizer's place ([Mic] did it, after a failure before any words). */
    fun away() {
        val s = _state.value
        if (s is State.Recording || s is State.Finishing) _state.value = State.Away(clock())
    }

    /**
     * What the phone's speech dialog gave back: [text] goes into the draft like any voice message
     * (never sent by itself); without words, [problem] is said, or nothing when it was closed or heard
     * nothing (it showed that itself). Also after the app was made anew while the dialog was up.
     */
    fun fromDialog(text: String?, problem: Problem?) {
        val s = _state.value
        if (s is State.Recording || s is State.Finishing || s == State.Asking) return
        if (text.isNullOrBlank()) {
            _state.value = if (problem != null) State.Failed(message(problem)) else State.Idle
            return
        }
        _state.value = State.Idle
        onText(text.trim(), if (s is State.Away) (clock() - s.since).coerceAtLeast(0) else 0L)
    }

    /** How loud it is, as Android's recognizer gives it (about -2 to 10 dB). */
    fun level(rmsDb: Float) {
        val s = _state.value as? State.Recording ?: return
        _state.value = s.copy(level = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f))
    }

    /** The recognizer stopped without words. */
    fun failed(problem: Problem) {
        when (val s = _state.value) {
            is State.Recording -> when {
                // A pause while you think: keep hearing.
                problem == Problem.NoSpeech -> {
                    _state.value = s.copy(said = s.text, partial = "")
                    mic.listen()
                }
                problem == Problem.NoPermission -> _state.value = State.NoMic(forever = false)
                s.text.isNotBlank() -> finish(State.Finishing(s.startedAt, clock(), s.said, s.partial), s.text)
                else -> _state.value = State.Failed(message(problem))
            }
            is State.Finishing -> finish(s, s.text, problem)
            else -> {}
        }
    }

    /** Called a few times a second while it hears: ends a recording that went on too long, or a finish the recognizer never answered. */
    fun tick() {
        val now = clock()
        when (val s = _state.value) {
            is State.Recording -> if (now - s.startedAt >= MAX_MS) stop()
            is State.Finishing -> if (now - s.stoppedAt >= FINISH_MS) {
                mic.cancel()
                finish(s, s.text)
            }
            else -> {}
        }
    }

    private fun start(held: Boolean) {
        _state.value = State.Recording(clock(), held = held)
        mic.listen()
    }

    private fun finish(s: State.Finishing, text: String, problem: Problem = Problem.NoSpeech) {
        if (text.isBlank()) {
            _state.value = State.Failed(message(problem))
            return
        }
        _state.value = State.Idle
        onText(text.trim(), (s.stoppedAt - s.startedAt).coerceAtLeast(0))
    }

    companion object {
        /** Shorter than this, a press on the mic is a tap: it keeps hearing until the next tap. */
        const val TAP_MS = 400L
        /** The longest voice message. */
        const val MAX_MS = 5 * 60_000L
        /** How long to wait for the last words once you finish. */
        const val FINISH_MS = 6_000L

        /** Two stretches of speech as one text. */
        fun join(a: String, b: String): String = listOf(a.trim(), b.trim()).filter { it.isNotEmpty() }.joinToString(" ")

        /** The words heard, after what is already typed in [draft]. */
        fun append(draft: String, text: String): String = insert(draft, draft.length, text).text

        /**
         * The words heard put into [draft] where the cursor stands ([at]; nothing typed is replaced),
         * a space between them and the words on either side. An empty box takes the words alone.
         */
        fun insert(draft: String, at: Int, text: String): Inserted {
            if (draft.isBlank()) return Inserted(text, text.length)
            val head = draft.substring(0, at.coerceIn(0, draft.length))
            val tail = draft.substring(head.length)
            // After a bracket that opens, and before a mark that closes ("," "." ")"), no space, as typed.
            val lead = if (head.isEmpty() || head.last().isWhitespace() || head.last() in OPENING) "" else " "
            val trail = if (tail.isEmpty() || tail.first().isWhitespace() || tail.first() in CLOSING) "" else " "
            val cursor = head.length + lead.length + text.length
            return Inserted(head + lead + text + trail + tail, cursor)
        }

        private const val OPENING = "([{"
        private const val CLOSING = ".,;:!?)]}\"'…"

        /**
         * [draft] without the words a voice message put into [before] (the draft as it was before
         * them, see [insert]): [before] again while the draft is still exactly that with [words] in
         * one place, else null (the words were changed, or typed into: they are yours now, and nothing
         * is taken out).
         */
        fun drop(draft: String, before: String, words: String): String? {
            if (words.isEmpty()) return null
            // An empty box takes the words alone (see [insert]).
            if (before.isBlank()) return "".takeIf { draft.trim() == words }
            if (draft.length < before.length + words.length) return null
            // Where they went in: what stands before and after them is the old draft, split there.
            val found = (0..before.length).any { at ->
                val tail = before.substring(at)
                draft.startsWith(before.substring(0, at)) && draft.endsWith(tail) &&
                    draft.substring(at, draft.length - tail.length).trim() == words
            }
            return before.takeIf { found }
        }

        /** How long you spoke: `0:07`, `1:05`. */
        fun elapsed(millis: Long): String {
            val s = (millis / 1000).coerceAtLeast(0)
            return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
        }

        fun message(problem: Problem): String = when (problem) {
            Problem.NoSpeech -> "Didn't catch that. Hold the mic while you speak, or tap it to start and stop."
            Problem.Busy -> "Speech recognition is busy. Try again in a moment."
            Problem.Network -> "Couldn't reach speech recognition. Download your language for offline speech in the phone's settings, or try again online."
            Problem.Audio -> "The microphone didn't work. Another app may be using it."
            Problem.NoPermission -> "The microphone isn't allowed."
            Problem.Language -> "This phone can't turn speech in your language into text."
            Problem.Unavailable -> "Speech recognition isn't available on this phone right now."
            Problem.Other -> "Speech recognition stopped. Try again."
        }

        /** What one of Android's `SpeechRecognizer.ERROR_*` codes means here. */
        fun problem(error: Int): Problem = when (error) {
            Errors.NO_MATCH, Errors.SPEECH_TIMEOUT -> Problem.NoSpeech
            Errors.RECOGNIZER_BUSY, Errors.TOO_MANY_REQUESTS -> Problem.Busy
            Errors.NETWORK, Errors.NETWORK_TIMEOUT, Errors.SERVER, Errors.SERVER_DISCONNECTED -> Problem.Network
            Errors.AUDIO -> Problem.Audio
            Errors.INSUFFICIENT_PERMISSIONS -> Problem.NoPermission
            Errors.LANGUAGE_NOT_SUPPORTED, Errors.LANGUAGE_UNAVAILABLE -> Problem.Language
            else -> Problem.Other
        }
    }

    /** Android's `SpeechRecognizer.ERROR_*` codes (core has no Android): the app's tests check they match. */
    object Errors {
        const val NETWORK_TIMEOUT = 1
        const val NETWORK = 2
        const val AUDIO = 3
        const val SERVER = 4
        const val CLIENT = 5
        const val SPEECH_TIMEOUT = 6
        const val NO_MATCH = 7
        const val RECOGNIZER_BUSY = 8
        const val INSUFFICIENT_PERMISSIONS = 9
        const val TOO_MANY_REQUESTS = 10
        const val SERVER_DISCONNECTED = 11
        const val LANGUAGE_NOT_SUPPORTED = 12
        const val LANGUAGE_UNAVAILABLE = 13
    }
}

/**
 * The messages sent as voice messages, so the chat shows them with a mic: kept on the phone (the
 * agent's transcript only has the words), each by its words and when it was sent.
 */
object Spoken {
    @Serializable
    data class Sent(val text: String, val at: Long)

    /** How many are kept per chat. */
    const val KEEP = 100
    /** How much earlier than the send a transcript may time the message (the machine's clock against the phone's). */
    const val SLACK_MS = 2 * 60_000L

    /** [sent] with [text] sent at [at], keeping the newest [KEEP]. */
    fun add(sent: List<Sent>, text: String, at: Long): List<Sent> = (sent + Sent(text, at)).takeLast(KEEP)

    /**
     * The ids of the messages in [users] (each with when it was sent on the phone's clock, 0 when
     * unknown) that went as voice messages: each of [sent] marks the first message like it from its
     * time on, and only one.
     */
    fun marks(sent: List<Sent>, users: List<Pair<ChatItem.User, Long>>): Set<String> {
        if (sent.isEmpty()) return emptySet()
        val marked = LinkedHashSet<String>()
        sent.sortedBy { it.at }.forEach { s ->
            users.firstOrNull { (u, at) -> u.id !in marked && (at == 0L || at >= s.at - SLACK_MS) && SentMatch.same(s.text, u.text) }
                ?.let { marked += it.first.id }
        }
        return marked
    }

    /** Whether a message on its way, [text] sent at [at], is one of [sent]. */
    fun isSpoken(sent: List<Sent>, text: String, at: Long): Boolean =
        sent.any { abs(it.at - at) < SLACK_MS && SentMatch.same(it.text, text) }
}
