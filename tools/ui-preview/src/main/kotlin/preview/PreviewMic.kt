package preview

/**
 * Voice messages in the previews: the stand-in for `ui/common/Mic.kt` records without a microphone.
 * [allowed] is the microphone permission and [answer] what the phone's question gives (null: it
 * stays up; [forever] when Android won't ask again). Recording, it is as loud as [level]; finished,
 * it recorded [spokeFor] ms on the phone's clock. Scenes set them with `mic`.
 */
object PreviewMic {
    @Volatile var allowed: Boolean = true
    @Volatile var answer: Boolean? = true
    @Volatile var forever: Boolean = false
    @Volatile var level: Float = 0.6f
    @Volatile var spokeFor: Long = 7_000

    /** What a recording's file holds: a few bytes, as the stand-in's player never decodes them. */
    val RECORDING = "OggS preview recording".toByteArray()

    /** The scene's microphone, set before the app starts. */
    data class Setup(
        val allowed: Boolean = true,
        val answer: Boolean? = true,
        val forever: Boolean = false,
        val level: Float = 0.6f,
        val spokeFor: Long = 7_000,
    )

    fun set(s: Setup) {
        allowed = s.allowed; answer = s.answer; forever = s.forever; level = s.level; spokeFor = s.spokeFor
    }
}

/**
 * Playing voice messages in the previews: the stand-in for `ui/voice/Player.kt` plays nothing, and
 * shows what it is told to play as [position] ms into it, playing.
 */
object PreviewPlayer {
    @Volatile var position: Long = 2_600
}
