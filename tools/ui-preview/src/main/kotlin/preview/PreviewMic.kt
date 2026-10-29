package preview

/**
 * Voice messages in the previews: the stand-in for `ui/common/Mic.kt` records without a microphone.
 * [allowed] is the microphone permission and [answer] what the phone's question gives (null: it
 * stays up; [forever] when Android won't ask again). Recording, it is [level] loud; finished, the
 * recording is [spokeFor] ms long on the phone's clock. A voice message played stands at [playedTo]
 * (`standins/Playback.kt`). Scenes set them with `mic`.
 */
object PreviewMic {
    @Volatile var allowed: Boolean = true
    @Volatile var answer: Boolean? = true
    @Volatile var forever: Boolean = false
    @Volatile var level: Float = 0.6f
    @Volatile var spokeFor: Long = 7_000
    @Volatile var playedTo: Long = 3_000

    /** The scene's microphone, set before the app starts. */
    data class Setup(
        val allowed: Boolean = true,
        val answer: Boolean? = true,
        val forever: Boolean = false,
        val level: Float = 0.6f,
        val spokeFor: Long = 7_000,
        val playedTo: Long = 3_000,
    )

    fun set(s: Setup) {
        allowed = s.allowed; answer = s.answer; forever = s.forever; level = s.level; spokeFor = s.spokeFor; playedTo = s.playedTo
    }
}
