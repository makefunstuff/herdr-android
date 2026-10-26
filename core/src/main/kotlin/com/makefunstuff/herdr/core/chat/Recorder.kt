package com.makefunstuff.herdr.core.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A voice message in a chat's composer: the phone records what you say to a small audio file
 * ([Clip]), which waits in the composer and goes to the agent's machine with the message. Nothing
 * is turned into text on the phone: the agent gets the file itself.
 *
 * The mic button is held while you speak (let go to finish, slide away to cancel) or tapped to start
 * and tapped again to finish. Every call comes from the main thread: the UI's and the recorder's
 * callbacks.
 */
class Recorder(
    private val mic: Mic,
    /** The phone's clock, in milliseconds: how long you spoke. */
    val clock: () -> Long,
    /** The recording, once you finish. */
    private val onClip: (Clip) -> Unit,
) {
    /** The phone's side: its microphone permission and its recorder. */
    interface Mic {
        /** Whether the app may use the microphone. */
        fun allowed(): Boolean
        /** Asks for the microphone; the answer comes back through [answered]. */
        fun ask()
        /** Opens the app's page in the phone's settings, where a microphone refused for good is allowed. */
        fun settings()
        /** Starts recording to a new file; false when the microphone couldn't be opened. */
        fun start(): Boolean
        /** How loud it is now, 0 to 1. */
        fun level(): Float
        /** Stops recording and returns the file's path, or null when nothing usable was recorded. */
        fun stop(): String?
        /** Stops recording and throws the file away. */
        fun cancel()
    }

    /** A recording: the audio file on the phone and how long it is. */
    data class Clip(val path: String, val millis: Long)

    sealed interface State {
        data object Idle : State
        /** The phone asks you for the microphone. */
        data object Asking : State
        /**
         * Recording since [startedAt]. [held] while a finger holds the mic (else it was tapped and
         * goes until tapped again); [cancelling] once that finger slid far enough that letting go
         * cancels. [level] is how loud, 0 to 1.
         */
        data class Recording(
            val startedAt: Long,
            val held: Boolean,
            val cancelling: Boolean = false,
            val level: Float = 0f,
        ) : State
        /** No microphone: refused, [forever] when the phone won't ask again (only its settings can allow it). */
        data class NoMic(val forever: Boolean) : State
        /** It went wrong; [message] says what to do. */
        data class Failed(val message: String) : State
    }

    /** Why there is no recording. */
    enum class Problem { TooShort, Audio, NoPermission }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** A finger down on the mic: starts recording (asking for the microphone first), or finishes a tapped recording. */
    fun down() {
        when (val s = _state.value) {
            is State.Recording -> if (!s.held) stop()
            State.Asking -> {}
            else -> if (mic.allowed()) start(held = true) else {
                _state.value = State.Asking
                mic.ask()
            }
        }
    }

    /**
     * The finger up again after [heldFor] ms: a quick tap leaves it recording until the next tap;
     * after a hold it finishes, or cancels when the finger slid away.
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

    /** Finishes: the recording goes to the composer, unless it is too short to be one. */
    fun stop() {
        val s = _state.value as? State.Recording ?: return
        val millis = (clock() - s.startedAt).coerceAtLeast(0)
        if (millis < MIN_MS) {
            mic.cancel()
            _state.value = State.Failed(message(Problem.TooShort))
            return
        }
        val path = mic.stop()
        if (path == null) {
            _state.value = State.Failed(message(Problem.Audio))
            return
        }
        _state.value = State.Idle
        onClip(Clip(path, millis))
    }

    /** Throws the recording away (or the question for the microphone). */
    fun cancel() {
        val s = _state.value
        if (s is State.Recording) mic.cancel()
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

    /** The phone's answer to [Mic.ask]: allowed, it starts recording at once (the finger has long left the button). */
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

    /** The recorder broke off by itself (the microphone taken away, its permission withdrawn): it says why. */
    fun failed(problem: Problem) {
        if (_state.value !is State.Recording) return
        mic.cancel()
        _state.value = if (problem == Problem.NoPermission) State.NoMic(forever = false) else State.Failed(message(problem))
    }

    /** Called a few times a second while it records: how loud, and the end of a recording that went on too long. */
    fun tick() {
        val s = _state.value as? State.Recording ?: return
        if (clock() - s.startedAt >= MAX_MS) return stop()
        val level = mic.level().coerceIn(0f, 1f)
        if (level != s.level) _state.value = s.copy(level = level)
    }

    private fun start(held: Boolean) {
        val at = clock()
        if (!mic.start()) {
            _state.value = State.Failed(message(Problem.Audio))
            return
        }
        _state.value = State.Recording(at, held = held)
    }

    companion object {
        /** Shorter than this, a press on the mic is a tap: it keeps recording until the next tap. */
        const val TAP_MS = 400L
        /** A recording shorter than this is a slip of the finger, not a message. */
        const val MIN_MS = 700L
        /** The longest voice message: about 2 MB of Opus. */
        const val MAX_MS = 10 * 60_000L

        /** How long you spoke: `0:07`, `1:05`. */
        fun elapsed(millis: Long): String {
            val s = (millis / 1000).coerceAtLeast(0)
            return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
        }

        fun message(problem: Problem): String = when (problem) {
            Problem.TooShort -> "Too short to send. Hold the mic while you speak, or tap it to start and stop."
            Problem.Audio -> "The microphone didn't work. Another app may be using it."
            Problem.NoPermission -> "The microphone isn't allowed."
        }
    }
}
