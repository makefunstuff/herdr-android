package com.makefunstuff.herdr.core.code

import kotlinx.serialization.Serializable

/**
 * A comment on some lines of code, written on the phone: the file, the lines and the code they
 * hold go with it, so it reads as a review note or an instruction to whoever gets it.
 */
@Serializable
data class ReviewNote(
    val id: String,
    val machineId: String,
    /** The checkout the file is in. */
    val root: String,
    /** Its branch, when it has one. */
    val branch: String = "",
    /** The file, relative to [root]. */
    val path: String,
    val start: Int,
    val end: Int,
    /** Where the lines were read: "" for the file as it is, else "the diff against main", "commit 1a2b3c4"… */
    val source: String = "",
    /** The lines are the old side of a diff (removed lines): their numbers are the old file's. */
    val oldSide: Boolean = false,
    /** The code, as it was shown (a diff's lines with their `+`/`-`). */
    val quote: String,
    /** The fence's language: `kotlin`, `diff`… */
    val language: String = "",
    val text: String,
    /** When it was written, in milliseconds. */
    val at: Long = 0,
)

object ReviewNotes {
    /** Quoted lines beyond this are left out, and how many is said. */
    const val QUOTE_LINES = 40

    /** "line 12", "lines 12–14", "old lines 3–4". */
    fun lines(n: ReviewNote): String {
        val what = if (n.start == n.end) "line ${n.start}" else "lines ${n.start}–${n.end}"
        return if (n.oldSide) "old $what" else what
    }

    /** One note, or several, as the message an agent or the Coordinator gets. */
    fun message(notes: List<ReviewNote>): String {
        if (notes.isEmpty()) return ""
        if (notes.size == 1) {
            val n = notes.single()
            return buildString {
                append("Review note on ").append(where(n.root, n.branch)).append(":\n\n")
                append('`').append(n.path).append("`, ").append(lines(n))
                if (n.source.isNotEmpty()) append(" (").append(n.source).append(')')
                append(":\n").append(fenced(n)).append("\n\n").append(n.text.trim())
            }
        }
        val byRoot = notes.groupBy { it.root }
        return buildString {
            if (byRoot.size == 1) {
                val n = notes.first()
                append(notes.size).append(" review notes on ").append(where(n.root, n.branch)).append(':')
            } else {
                append(notes.size).append(" review notes:")
            }
            var k = 1
            byRoot.forEach { (_, list) ->
                if (byRoot.size > 1) append("\n\nOn ").append(where(list.first().root, list.first().branch)).append(':')
                list.forEach { n ->
                    append("\n\n").append(k++).append(". `").append(n.path).append("`, ").append(lines(n))
                    if (n.source.isNotEmpty()) append(" (").append(n.source).append(')')
                    append(":\n").append(fenced(n)).append('\n').append(n.text.trim())
                }
            }
        }
    }

    private fun where(root: String, branch: String) = if (branch.isEmpty()) root else "$root (branch $branch)"

    /** The quoted code in a fence that its own backticks can't close. */
    fun fenced(n: ReviewNote): String {
        val all = n.quote.trimEnd('\n').split('\n')
        val shown = all.take(QUOTE_LINES)
        val fence = if (shown.any { it.trimStart().startsWith("```") }) "~~~~" else "```"
        return buildString {
            append(fence).append(n.language).append('\n')
            shown.forEach { append(it).append('\n') }
            if (all.size > shown.size) append("… ").append(all.size - shown.size).append(" more lines\n")
            append(fence)
        }
    }
}
