package preview

import com.makefunstuff.herdr.core.voice.VoiceState
import com.makefunstuff.herdr.voice.CoordinatorVoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * The coordinator's voice in the previews: no sound and no microphone. It shows the state a scene
 * sets in `voice` (on in a chat, speaking, hearing you), and the bar's controls change it as on the phone.
 */
class PreviewCoordinatorVoice(start: VoiceState = VoiceState()) : CoordinatorVoice {
    private val _state = MutableStateFlow(start)
    override val state: StateFlow<VoiceState> = _state

    override fun start(machineId: String, space: String, spaceName: String) {
        _state.value = _state.value.copy(on = true, machineId = machineId, space = space, spaceName = spaceName)
    }
    override fun stop() = _state.update { it.copy(on = false) }
    override fun hush() = _state.update { if (it.phase == VoiceState.Phase.Speaking) it.copy(phase = VoiceState.Phase.Ready) else it }
    override fun talk(down: Boolean) = _state.update { it.copy(phase = if (down) VoiceState.Phase.Listening else VoiceState.Phase.Hearing) }
    override fun setHandsFree(on: Boolean) = _state.update { it.copy(handsFree = on) }
    override fun setMuted(on: Boolean) = _state.update { it.copy(muted = on) }
    override fun setSpeed(speed: Float) = _state.update { it.copy(speed = speed) }
    override suspend fun test(): String = "Server voice: first sound in 140 ms"

    companion object {
        /** What the next scene's voice starts as (`SceneData.voice`). */
        @Volatile var next = VoiceState()
    }
}
