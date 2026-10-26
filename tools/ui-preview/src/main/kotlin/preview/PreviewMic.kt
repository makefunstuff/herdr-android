package preview

/**
 * Voice messages in the previews: the stand-in for `ui/common/Mic.kt` hears without a microphone.
 * [allowed] is the microphone permission and [answer] what the phone's question gives (null: it
 * stays up; [forever] when Android won't ask again). Listening, it hears [heard] at once; finished,
 * the words are [said], after [spokeFor] ms on the phone's clock. Scenes set them with `mic`.
 */
object PreviewMic {
    @Volatile var allowed: Boolean = true
    @Volatile var answer: Boolean? = true
    @Volatile var forever: Boolean = false
    @Volatile var heard: String = ""
    @Volatile var said: String = ""
    @Volatile var spokeFor: Long = 7_000

    /** The scene's microphone, set before the app starts. */
    data class Setup(
        val allowed: Boolean = true,
        val answer: Boolean? = true,
        val forever: Boolean = false,
        val heard: String = "Run the failing board test again and",
        val said: String = "Run the failing board test again and tell me which assertion breaks before you change anything.",
        val spokeFor: Long = 7_000,
    )

    fun set(s: Setup) {
        allowed = s.allowed; answer = s.answer; forever = s.forever; heard = s.heard; said = s.said; spokeFor = s.spokeFor
    }
}
