package com.makefunstuff.herdr.core.chat

/** How the chat shows an agent's reasoning ([ChatItem.Reasoning]): folded to a line, read in pieces. */
object Thinking {
    private val THINK = Regex("""^\s*<think(?:ing)?>([\s\S]*?)(?:</think(?:ing)?>|$)""")

    /**
     * Splits reasoning a model wrote in front of its words in `<think>…</think>` (left unclosed while
     * it still thinks) from the words: (the reasoning or null, the words).
     */
    fun split(text: String): Pair<String?, String> {
        val m = THINK.find(text) ?: return null to text
        return m.groupValues[1].trim().ifEmpty { null } to text.substring(m.range.last + 1).trim()
    }

    /** The first line worth reading, without the Markdown marks a summary heads with (`**Checking the tests**`). */
    fun preview(text: String): String =
        text.lineSequence().map(::plain).firstOrNull { it.isNotEmpty() }.orEmpty()

    /** The last [lines] lines with words, plain: what the agent thinks about now. */
    fun tail(text: String, lines: Int): String =
        text.lines().map(::plain).filter { it.isNotEmpty() }.takeLast(lines).joinToString("\n")

    /** A line without the Markdown marks a one-line preview can't show: `**`, backticks, a heading's or a list's lead. */
    private fun plain(line: String): String =
        line.trim().replace(MARKS, "").replace(LEAD, "").trim()

    fun words(text: String): Int = text.split(SPACE).count { it.isNotEmpty() }

    /** `48 words`, `1.2k words`. */
    fun size(text: String): String {
        val n = words(text)
        return when {
            n == 1 -> "1 word"
            n < 1_000 -> "$n words"
            n < 10_000 -> "${(n + 50) / 100 / 10.0}k words".replace(".0k", "k")
            else -> "${(n + 500) / 1_000}k words"
        }
    }

    /**
     * [text] cut at paragraph breaks into pieces of about [size] characters or more, never inside a
     * code fence, so long reasoning opens as several rows a list renders as they scroll in.
     */
    fun chunks(text: String, size: Int = 1_500): List<String> {
        if (text.length <= size) return listOf(text)
        val out = ArrayList<String>()
        val piece = StringBuilder()
        var fenced = false
        for (line in text.lines()) {
            if (line.trimStart().startsWith("```")) fenced = !fenced
            if (!fenced && line.isBlank() && piece.length >= size) {
                out += piece.toString().trim()
                piece.clear()
                continue
            }
            piece.append(line).append('\n')
        }
        piece.toString().trim().takeIf { it.isNotEmpty() }?.let { out += it }
        return out
    }

    private val SPACE = Regex("\\s+")
    private val MARKS = Regex("\\*\\*|__|`")
    private val LEAD = Regex("^(#{1,6}|[-*+]|\\d+\\.)\\s+")
}
