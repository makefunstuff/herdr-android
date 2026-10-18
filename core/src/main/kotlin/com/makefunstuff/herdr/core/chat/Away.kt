package com.makefunstuff.herdr.core.chat

import com.makefunstuff.herdr.core.activity.Origin
import com.makefunstuff.herdr.core.activity.StepKind
import com.makefunstuff.herdr.core.activity.StepState
import com.makefunstuff.herdr.core.activity.Turn

/**
 * "While you were away · 25 min": what an agent did since the chat was last on screen, in up to
 * three lines, built from its transcript alone (what it answered, what it was asked, what it changed
 * and ran), so coming back doesn't mean scrolling up to find out.
 */
data class Away(
    /** How long the chat was away, as the card's title says it: `25 min`, `3 h`, `2 days`. */
    val gone: String,
    val lines: List<String>,
)

object AwaySummary {
    /** Less time than this away is a glance elsewhere, not an absence worth a card. */
    const val MIN_AWAY_MS = 5 * 60_000L

    /**
     * What happened in [turns] after [since] (when the chat was last looked at), seen at [now]; null
     * when it was a short while, or nothing happened. [live]: the newest turn is still running.
     */
    fun of(turns: List<Turn>, since: Long, now: Long, live: Boolean): Away? {
        if (since <= 0 || now - since < MIN_AWAY_MS) return null
        val steps = turns.flatMap { it.steps }.filter { maxOf(it.at, it.end) > since }
        val asked = turns.filter { it.at > since && it.from != Origin.Agent }
        if (steps.isEmpty() && asked.isEmpty()) return null

        // What it said and was asked, in the order it happened.
        val said = ArrayList<Pair<Long, String>>()
        val answered = turns.withIndex().lastOrNull { (i, t) -> !(live && i == turns.lastIndex) && t.end > since && answer(t) != null }?.value
        answered?.let { t -> said += t.end to "Answered: “${sentence(answer(t)!!)}”" }
        val ask = asked.lastOrNull()
        val asking = live && ask != null && ask === turns.last()
        ask?.let { t ->
            val who = when (t.from) {
                Origin.You -> "You asked: “${clip(t.prompt, 90)}”"
                Origin.Board -> "The board: ${clip(t.prompt, 90)}"
                else -> clip(t.prompt, 90)
            }
            said += t.at to who + if (asking) "; " + (ChatTurns.doing(t)?.let { "now $it" } ?: "working on it") else ""
        }
        // Still at work on something older than what it was asked: what it does now.
        if (live && !asking) ChatTurns.doing(turns.lastOrNull())?.let { said += now to "Now $it" }

        // What it changed and ran.
        val edits = steps.filter { it.kind == StepKind.Edit && it.state == StepState.Done }
        val files = edits.mapNotNull { it.path }.distinct()
        val diffs = edits.mapNotNull { it.diff }
        val commands = steps.filter { it.kind == StepKind.Command || it.kind == StepKind.Test }
        val failed = commands.count { it.failed }
        val tests = steps.lastOrNull { it.kind == StepKind.Test && it.state != StepState.Running }
        val reads = steps.count { it.kind == StepKind.Read }
        val numbers = listOfNotNull(
            files.size.takeIf { it > 0 }?.let { n ->
                (if (n == 1) "1 file changed" else "$n files changed") +
                    (if (diffs.isNotEmpty()) ", +${diffs.sumOf { it.added }} −${diffs.sumOf { it.removed }}" else "")
            },
            commands.size.takeIf { it > 0 }?.let { n ->
                (if (n == 1) "1 command" else "$n commands") + (if (failed > 0) ", $failed failed" else "")
            },
            tests?.let { if (it.failed) "tests failed" else "tests passed" },
            reads.takeIf { it > 0 && files.isEmpty() && commands.isEmpty() }?.let { if (it == 1) "1 file read" else "$it files read" },
        ).joinToString(" · ")

        val lines = said.sortedBy { it.first }.map { it.second }.takeLast(2) + listOfNotNull(numbers.ifEmpty { null })
        if (lines.isEmpty()) return null
        return Away(gone(now - since), lines)
    }

    /** A settled turn's answer: what it said after its last action. */
    private fun answer(t: Turn): String? {
        val lastAction = t.steps.indexOfLast { it.kind != StepKind.Message && it.kind != StepKind.Notice }
        return t.steps.drop(lastAction + 1).lastOrNull { it.kind == StepKind.Message }?.let { it.detail ?: it.title }
    }

    private val MARKS = Regex("""\*\*|__|`|^#+\s*|^[-*]\s+""")

    /** A message's first sentence, without its Markdown marks, cut to fit a line or two. */
    fun sentence(text: String): String {
        val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty().replace(MARKS, "")
        val end = Regex("""[.!?](\s|$)""").find(line)?.range?.first
        return clip(if (end != null) line.substring(0, end + 1) else line, 110)
    }

    private fun clip(text: String, max: Int): String = if (text.length <= max) text else text.take(max - 1).trimEnd() + "…"

    /** `25 min`, `3 h`, `3 h 10 min`, `2 days`. */
    fun gone(ms: Long): String {
        val minutes = ms / 60_000
        return when {
            minutes < 60 -> "$minutes min"
            minutes < 24 * 60 -> if (minutes % 60 < 5) "${minutes / 60} h" else "${minutes / 60} h ${minutes % 60} min"
            minutes < 48 * 60 -> "1 day"
            else -> "${minutes / (24 * 60)} days"
        }
    }
}
