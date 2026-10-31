package com.makefunstuff.herdr.voice

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.makefunstuff.herdr.core.board.WorkBoard
import com.makefunstuff.herdr.core.shepherdr.SpaceChat
import com.makefunstuff.herdr.core.shepherdr.SpaceChatItem
import com.makefunstuff.herdr.core.voice.EdgeVoice
import com.makefunstuff.herdr.core.voice.Endpointer
import com.makefunstuff.herdr.core.voice.ReadQueue
import com.makefunstuff.herdr.core.voice.SpeechText
import com.makefunstuff.herdr.core.voice.VoiceEngine
import com.makefunstuff.herdr.core.voice.VoiceState
import com.makefunstuff.herdr.core.voice.VoiceState.Phase
import com.makefunstuff.herdr.data.HostManager
import com.makefunstuff.herdr.data.VoiceSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID
import kotlin.math.sqrt

/**
 * [CoordinatorVoice] on the phone. While on, it follows the chat (its machine kept connected with the
 * app closed), reads each new reply and task card aloud through the voice server ([EdgeVoice]: Omni's
 * `/tts`, sentence by sentence, through Inkling's jitter buffer in [AudioOut]), and sends what you say
 * as a message, heard by the server. When the server can't be reached the phone's own voice and
 * recogniser ([PhoneSpeech]) stand in, and the server is tried again every [RETRY_MS].
 * [VoiceService] keeps it going with the screen off. Each reply's time to first sound is logged
 * (tag `herdr-voice`).
 */
class AndroidCoordinatorVoice(
    private val context: Context,
    private val scope: CoroutineScope,
    private val hosts: HostManager,
    private val settings: VoiceSettingsStore,
) : CoordinatorVoice {
    private val _state = MutableStateFlow(fromSettings(VoiceState()))
    override val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val out = AudioOut()
    private val phone = PhoneSpeech(context)
    private val audio = context.getSystemService(AudioManager::class.java)
    // A call, or another app taking the speaker for itself, stops the voice.
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener { change -> if (change < 0 && change != AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) hush() }
        .build()

    /** A reply to read, and when it came (for the time to first sound). */
    private data class Pending(val line: ReadQueue.Line, val at: Long)

    private var run: Job? = null
    /** Work for this chat only (hearing, sending): stopping voice cancels it, so late words never reach another chat. */
    private var runScope: CoroutineScope? = null
    private var lines = Channel<Pending>(Channel.UNLIMITED)
    @Volatile private var board: WorkBoard? = null
    /** When the server last failed; it is tried again [RETRY_MS] after. */
    @Volatile private var awaySince = 0L
    @Volatile private var hushed = false

    // Hearing you.
    private val mic = MicIn(::heard)
    private val ends = Endpointer()
    private val turn = ArrayList<FloatArray>()
    private val before = ArrayDeque<FloatArray>()
    @Volatile private var capturing = false
    @Volatile private var pushing = false
    private var phoneEars: Job? = null

    init {
        out.onSound = { _state.update { it.copy(level = 0f) } }
    }

    override fun start(machineId: String, space: String, spaceName: String) {
        stop()
        _state.value = fromSettings(VoiceState(on = true, machineId = machineId, space = space, spaceName = spaceName))
        lines = Channel(Channel.UNLIMITED)
        val queue = ReadQueue()
        ContextCompat.startForegroundService(context, Intent(context, VoiceService::class.java))
        val job = scope.launch {
            launch { follow(machineId, space, queue) }
            launch { speaking() }
            launch { out.level.collect { l -> if (_state.value.phase == Phase.Speaking) _state.update { it.copy(level = l) } } }
            launch { probe() }
            if (_state.value.handsFree) openMic()
        }
        run = job
        runScope = CoroutineScope(scope.coroutineContext + Job(job))
    }

    override fun stop() {
        if (!_state.value.on && run == null) return
        hush()
        closeMic()
        phoneEars?.cancel()
        run?.cancel()
        run = null
        runScope = null
        board = null
        _state.update { it.copy(on = false, phase = Phase.Ready, level = 0f, line = "", problem = null) }
    }

    override fun hush() {
        hushed = true
        // Stops the voice, not just this line: what waited to be read is dropped too.
        while (lines.tryReceive().isSuccess) Unit
        out.stop()
        phone.hush()
        if (_state.value.phase == Phase.Speaking) _state.update { it.copy(phase = Phase.Ready, level = 0f) }
    }

    override fun talk(down: Boolean) {
        if (!_state.value.on) return
        if (down) {
            hush()
            if (useServer() == null) return hearOnPhone()
            pushing = true
            synchronized(turn) { turn.clear(); turn.addAll(before) }
            capturing = true
            if (!mic.isOn && !openMic()) return
            _state.update { it.copy(phase = Phase.Listening) }
        } else {
            if (phoneEars?.isActive == true) { phone.stopHearing(); return }
            // Hands-free, mid-turn: Send ends it now.
            if (!pushing && !capturing) return
            pushing = false
            capturing = false
            ends.reset()
            finishTurn()
            if (!_state.value.handsFree) closeMic()
        }
    }

    override fun setHandsFree(on: Boolean) {
        settings.setHandsFree(on)
        _state.update { it.copy(handsFree = on) }
        if (!_state.value.on) return
        if (on) openMic() else if (!pushing) { closeMic(); phoneEars?.cancel() }
    }

    override fun setMuted(on: Boolean) {
        settings.setMuted(on)
        _state.update { it.copy(muted = on) }
        if (on) hush()
    }

    override fun setSpeed(speed: Float) {
        settings.setSpeed(speed)
        _state.update { it.copy(speed = settings.state.value.speed) }
        out.speed = settings.state.value.speed
    }

    override suspend fun test(): String {
        if (_state.value.on) return "Turn the chat's voice off to test"
        val s = settings.state.value
        val engine = if (s.usable) runCatching { EdgeVoice(s.server, s.key) }.getOrNull() else null
        hushed = false
        if (engine != null) {
            try {
                val t0 = SystemClock.elapsedRealtime()
                var first = 0L
                out.speed = s.speed
                out.onSound = { first = SystemClock.elapsedRealtime() - t0 }
                take()
                out.start()
                engine.speak("Voice is on. I'll read the coordinator's replies.", s.voice) { pcm -> out.write(pcm); !hushed }
                out.finish()
                awaySince = 0
                return "Server voice: first sound in $first ms"
            } catch (e: IOException) {
                out.stop()
                awaySince = SystemClock.elapsedRealtime()
                val t0 = SystemClock.elapsedRealtime()
                var first = 0L
                phone.say("The server can't be reached. This is the phone's voice.", s.speed) { first = SystemClock.elapsedRealtime() - t0 }
                return "${e.message ?: "The server can't be reached"}. Phone voice: first sound in $first ms"
            } finally {
                out.onSound = { _state.update { it.copy(level = 0f) } }
                release()
            }
        }
        val t0 = SystemClock.elapsedRealtime()
        var first = 0L
        take()
        try { phone.say("No voice server is set up. This is the phone's voice.", s.speed) { first = SystemClock.elapsedRealtime() - t0 } } finally { release() }
        return "No server set up. Phone voice: first sound in $first ms"
    }

    /** Reads [item] as if it just came in the chat (debug builds drive it from adb to time it). */
    fun readAloud(item: SpaceChatItem) {
        if (!_state.value.on) return
        SpeechText.of(item)?.let { lines.trySend(Pending(ReadQueue.Line(item.id, it), SystemClock.elapsedRealtime())) }
    }

    /** Hears [pcm16k] as if you just said it (debug builds feed a recording from adb). */
    fun hearRecording(pcm16k: FloatArray) {
        if (!_state.value.on) return
        synchronized(turn) { turn.clear(); turn += pcm16k }
        finishTurn()
    }

    /** The chat, followed for as long as voice is on: each new reply goes to [lines]. */
    private suspend fun follow(machineId: String, space: String, queue: ReadQueue) {
        if (hosts.session(machineId) == null) {
            _state.update { it.copy(problem = "$NO_CHAT: this machine isn't in the app any more") }
            return
        }
        hosts.borrow(machineId) { session ->
            while (true) {
                try {
                    val b = session.awaitWorkBoard()
                    board = b
                    b.coordinatorThreads(space).collect { t ->
                        val chat = t.v2 ?: return@collect
                        val at = SystemClock.elapsedRealtime()
                        queue.next(SpaceChat.items(space, chat)).forEach { lines.send(Pending(it, at)) }
                        if (_state.value.problem?.startsWith(NO_CHAT) == true) _state.update { it.copy(problem = null) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(AudioOut.TAG, "can't follow the chat", e)
                    _state.update { it.copy(problem = "$NO_CHAT: ${e.message ?: "not connected"}") }
                }
                delay(5_000)
            }
        }
    }

    /** Reads [lines] aloud, one after another; muted, they pass unsaid. */
    private suspend fun speaking() {
        for (p in lines) {
            // While you talk it waits, and reads after.
            while (capturing || phoneEars?.isActive == true || _state.value.phase == Phase.Hearing || _state.value.phase == Phase.Sending) delay(100)
            if (_state.value.muted) continue
            speak(p.line.text, p.at)
        }
    }

    private suspend fun speak(text: String, at: Long) {
        hushed = false
        _state.update { it.copy(phase = Phase.Speaking, line = text) }
        val s = settings.state.value
        var heardAny = false
        if (!take()) {
            // A call has the speaker: this one isn't read.
            _state.update { it.copy(phase = Phase.Ready) }
            return
        }
        try {
            val engine = useServer()
            if (engine != null) {
                try {
                    out.speed = s.speed
                    out.onSound = {
                        Log.i(AudioOut.TAG, "first sound %d ms after the reply came (server)".format(SystemClock.elapsedRealtime() - at))
                        _state.update { it.copy(engine = VoiceState.Engine.Server) }
                    }
                    out.start()
                    for (chunk in SpeechText.chunks(text)) {
                        val done = engine.speak(chunk, s.voice) { pcm -> heardAny = true; out.write(pcm); !hushed }
                        if (!done || hushed) break
                    }
                    if (!hushed) out.finish()
                    return
                } catch (e: IOException) {
                    out.stop()
                    away(e)
                    // Half said by the server: the phone doesn't say it again.
                    if (heardAny || hushed) return
                }
            }
            if (hushed) return
            phone.say(text, s.speed) {
                Log.i(AudioOut.TAG, "first sound %d ms after the reply came (phone)".format(SystemClock.elapsedRealtime() - at))
            }
        } finally {
            release()
            if (_state.value.phase == Phase.Speaking) _state.update { it.copy(phase = Phase.Ready, level = 0f) }
        }
    }

    /** The server to use now: null when none is set up or it failed less than [RETRY_MS] ago. */
    private fun useServer(): VoiceEngine? {
        val s = settings.state.value
        if (!s.usable) { _state.update { it.copy(engine = VoiceState.Engine.Phone) }; return null }
        if (awaySince != 0L && SystemClock.elapsedRealtime() - awaySince < RETRY_MS) return null
        return runCatching { EdgeVoice(s.server, s.key) }.getOrNull()
    }

    private fun away(e: Exception) {
        Log.w(AudioOut.TAG, "voice server away: ${e.message}")
        awaySince = SystemClock.elapsedRealtime()
        _state.update { it.copy(engine = VoiceState.Engine.Phone) }
    }

    /** Looks whether the server answers, at the start and while it is away, so the bar says whose voice it is. */
    private suspend fun probe() {
        while (true) {
            val s = settings.state.value
            if (s.usable && (awaySince == 0L || SystemClock.elapsedRealtime() - awaySince >= RETRY_MS)) {
                try {
                    EdgeVoice(s.server, s.key).ping()
                    awaySince = 0
                    _state.update { it.copy(engine = VoiceState.Engine.Server) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    away(e)
                }
            } else if (!s.usable) {
                _state.update { it.copy(engine = VoiceState.Engine.Phone) }
            }
            delay(if (awaySince == 0L) 60_000 else RETRY_MS)
        }
    }

    private fun openMic(): Boolean {
        if (mic.isOn) return true
        ends.reset()
        useHeadsetMic()
        if (mic.start()) {
            // The service takes the microphone too now (it may not have had the permission when it started).
            runCatching { ContextCompat.startForegroundService(context, Intent(context, VoiceService::class.java)) }
            return true
        }
        settings.setHandsFree(false)
        _state.update { it.copy(problem = "No microphone: allow it in the phone's settings", handsFree = false, phase = Phase.Ready) }
        pushing = false
        capturing = false
        return false
    }

    private fun closeMic() {
        mic.stop()
        if (Build.VERSION.SDK_INT >= 31) runCatching { audio.clearCommunicationDevice() }
        capturing = false
        synchronized(turn) { turn.clear(); before.clear() }
    }

    /** A block from the microphone (its own thread). */
    private fun heard(pcm: FloatArray) {
        var sum = 0.0
        for (x in pcm) sum += x * x
        val rms = sqrt(sum / pcm.size).toFloat()
        if (capturing) {
            synchronized(turn) { turn += pcm }
            _state.update { it.copy(level = (rms * 6).coerceIn(0f, 1f)) }
        } else synchronized(turn) {
            before.addLast(pcm)
            while (before.size > PRE_ROLL_BLOCKS) before.removeFirst()
        }
        if (pushing || !_state.value.handsFree) return
        // Out of the loudspeaker, its own voice leaks past the echo canceller: only a clear voice breaks in.
        val speaking = _state.value.phase == Phase.Speaking
        // Those blocks aren't fed at all: as silence they would teach the endpointer a quiet room that isn't.
        if (speaking && rms < BARGE_IN && !headset()) return
        when (ends.feed(rms)) {
            Endpointer.Event.Started -> {
                if (speaking) hush()
                if (useServer() == null) { scope.launch(kotlinx.coroutines.Dispatchers.Main) { hearOnPhone() }; return }
                synchronized(turn) { turn.clear(); turn.addAll(before); before.clear() }
                capturing = true
                _state.update { it.copy(phase = Phase.Listening) }
            }
            Endpointer.Event.Ended -> if (capturing) { capturing = false; finishTurn() }
            else -> {}
        }
    }

    /** What you said, to words and into the chat. */
    private fun finishTurn() {
        val pcm = synchronized(turn) {
            val all = FloatArray(turn.sumOf { it.size })
            var o = 0
            turn.forEach { System.arraycopy(it, 0, all, o, it.size); o += it.size }
            turn.clear()
            all
        }
        if (pcm.size < MicIn.RATE * MIN_TURN_MS / 1000) {
            _state.update { it.copy(phase = Phase.Ready, level = 0f) }
            return
        }
        val chat = runScope ?: return
        chat.launch {
            _state.update { it.copy(phase = Phase.Hearing, level = 0f) }
            val text = try {
                val engine = useServer() ?: throw IOException("The server can't be reached")
                val t0 = SystemClock.elapsedRealtime()
                engine.transcribe(pcm).also { Log.i(AudioOut.TAG, "heard %.1f s in %d ms: %s".format(pcm.size / 16_000.0, SystemClock.elapsedRealtime() - t0, it)) }
            } catch (e: IOException) {
                away(e)
                _state.update { it.copy(problem = "Couldn't hear you: ${e.message}. Try again.") }
                ""
            }
            send(text)
        }
    }

    /** Push to talk or hands-free while the server is away: the phone's recogniser hears one turn. */
    private fun hearOnPhone() {
        if (phoneEars?.isActive == true) return
        val reopen = mic.isOn
        mic.stop()
        phoneEars = (runScope ?: return).launch {
            _state.update { it.copy(phase = Phase.Listening, engine = VoiceState.Engine.Phone) }
            val words = phone.hear { l -> _state.update { it.copy(level = l) } }
            send(words)
            if (reopen && _state.value.on && _state.value.handsFree) { delay(300); openMic() }
        }
    }

    private suspend fun send(text: String) {
        val words = text.trim()
        val s = _state.value
        val b = board
        if (words.length < 2 || !s.on || b == null) {
            if (words.isNotEmpty() && b == null) _state.update { it.copy(problem = "Not connected: what you said wasn't sent") }
            _state.update { it.copy(phase = Phase.Ready, level = 0f) }
            return
        }
        _state.update { it.copy(phase = Phase.Sending, line = "You: $words", problem = null) }
        try {
            b.say(s.space, words, cid = UUID.randomUUID().toString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(problem = "Couldn't send: ${e.message ?: "not connected"}") }
        }
        _state.update { if (it.phase == Phase.Sending) it.copy(phase = Phase.Ready) else it }
    }

    private fun take(): Boolean = audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_FAILED
    private fun release() { audio.abandonAudioFocusRequest(focus) }

    /** A Bluetooth headset's microphone, when one is connected: you talk into it, not the phone. */
    private fun useHeadsetMic() {
        if (Build.VERSION.SDK_INT < 31) return
        audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
            ?.let { runCatching { audio.setCommunicationDevice(it) } }
    }

    /** Wired or Bluetooth headphones: nothing it says reaches the microphone. */
    private fun headset(): Boolean = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
        // Not A2DP: that is as often a car's or a room's speaker as headphones.
        it.type in setOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLE_HEADSET)
    }

    private fun fromSettings(v: VoiceState): VoiceState = settings.state.value.let { s ->
        v.copy(handsFree = s.handsFree, muted = s.muted, speed = s.speed, engine = if (s.usable) VoiceState.Engine.Server else VoiceState.Engine.Phone)
    }

    companion object {
        const val RETRY_MS = 20_000L
        /** About 300 ms before you were heard to start: the first sound of a word. */
        const val PRE_ROLL_BLOCKS = 10
        const val MIN_TURN_MS = 350
        /** How loud you must be to talk over the voice from the loudspeaker. */
        const val BARGE_IN = 0.05f
        private const val NO_CHAT = "Can't follow the chat"
    }
}
