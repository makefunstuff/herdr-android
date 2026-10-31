package com.makefunstuff.herdr.voice

import com.makefunstuff.herdr.core.voice.VoiceState
import kotlinx.coroutines.flow.StateFlow

/**
 * The coordinator's voice: a chat's new replies and task news read aloud, and your words heard and
 * sent to it, hands-free or held to talk. One chat at a time; it goes on with the screen off. On the
 * phone it is [AndroidCoordinatorVoice]; the previews give a still one.
 */
interface CoordinatorVoice {
    val state: StateFlow<VoiceState>

    /** On for [machineId]'s space [space] ([spaceName] as people say it); another chat's voice stops. */
    fun start(machineId: String, space: String, spaceName: String)

    /** Off: nothing more is read or heard. */
    fun stop()

    /** Stops what it is saying now, at once (a tap, a headset button, your voice). */
    fun hush()

    /** Push to talk: [down] starts hearing you, up sends what you said. */
    fun talk(down: Boolean)

    fun setHandsFree(on: Boolean)
    fun setMuted(on: Boolean)
    fun setSpeed(speed: Float)

    /** Says a test line with the voice set now: how long until it was heard, and whose voice, in words. */
    suspend fun test(): String
}
