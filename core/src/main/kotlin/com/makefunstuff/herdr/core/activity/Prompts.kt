package com.makefunstuff.herdr.core.activity

/**
 * What a turn's first message is: one you typed, or one of the board's own (it starts the agent
 * and the reviewer, sends findings back, asks to continue), named for what it does.
 */
object Prompts {
    data class Reading(val origin: Origin, val title: String, val role: Role? = null)

    private val TASK = Regex("""^Work board task (\S+?), role: ([a-z]+)(?:, round (\d+))?\.""")
    private val PASTED = Regex("""</?pasted_content[^>]*>""")
    private val SPACE = Regex("""\s+""")

    /** [text] without the wrapper the board pastes it in. */
    fun unwrap(text: String): String = text.replace(PASTED, "").trim()

    fun read(raw: String): Reading {
        val text = unwrap(raw)
        val line = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        TASK.find(text)?.let { m ->
            val role = m.groupValues[2]
            val round = m.groupValues[3].toIntOrNull() ?: 0
            val title = when {
                role == Role.REVIEWER && round > 0 -> "Started the review, round $round"
                role == Role.REVIEWER -> "Started the review"
                role == Role.AGENT -> "Started the agent"
                else -> "Started the $role"
            }
            return Reading(Origin.Board, title, Role(role, round))
        }
        val board = when {
            line.startsWith("A reviewer went through your change") ->
                "Sent the review back" + line.substringAfter("Result:", "").substringBefore(". ").trim().trimEnd('.').takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty()
            line.startsWith("Continue this task") || line.startsWith("You stopped before the work was finished") -> "Asked the agent to continue"
            line.startsWith("This task ran into an error:") -> "Sent an error: " + line.removePrefix("This task ran into an error:").trim().take(120)
            line.startsWith("Your branch ") && " conflicts with " in line -> "Its branch conflicts with the base: sent to the agent"
            line.startsWith("GitHub's checks failed") -> "GitHub's checks failed: sent to the agent"
            line.startsWith("The plan was approved") || line.startsWith("Your plan was approved") -> "Approved the plan"
            else -> null
        }
        if (board != null) return Reading(Origin.Board, board)
        if (text.startsWith("<task-notification>")) return Reading(Origin.System, "A background job finished")
        return Reading(Origin.You, line.take(200))
    }

    /** What two copies of one message share: its start, spaces collapsed, for matching the board's record of what it typed. */
    fun key(text: String): String = unwrap(text).replace(SPACE, " ").trim().take(60).lowercase()
}
