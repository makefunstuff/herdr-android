package com.makefunstuff.herdr.core.chat

/** How the chat shows an agent's reasoning ([ChatItem.Reasoning]): folded to a line, read in pieces. */
object Thinking {
    /**
     * `<think>` on a line of its own, the reasoning, `</think>` on a line of its own: as open models
     * (Qwen, DeepSeek) write it. Words that merely start with the tag, or never close it, are words.
     */
    private val THINK = Regex("""^\s*<think>[ \t]*\n([\s\S]*?)\n[ \t]*</think>[ \t]*(?:\n|$)""")

    /** Splits the reasoning a model wrote in front of its words from the words: (the reasoning or null, the words). */
    fun split(text: String): Pair<String?, String> {
        val m = THINK.find(text) ?: return null to text
        return m.groupValues[1].trim().ifEmpty { null } to text.substring(m.range.last + 1).trim()
    }

    /** The first line worth reading, without the Markdown marks a summary heads with (`**Checking the tests**`). */
    fun preview(text: String): String =
        text.lineSequence().map(::plain).firstOrNull { it.isNotEmpty() }.orEmpty()

    /**
     * The last [lines] lines with words, plain and run together, at most [chars] of them (from a word, after `…`): what
     * the agent thinks about now, its newest words always in it.
     */
    fun tail(text: String, lines: Int, chars: Int = 150): String {
        // One run of words: three short lines on a phone, never a line break eating one.
        val t = text.lines().map(::plain).filter { it.isNotEmpty() }.takeLast(lines).joinToString(" ")
        if (t.length <= chars) return t
        val from = t.length - chars
        val word = t.indexOfAny(charArrayOf(' ', '\n'), from).takeIf { it in from until t.length - 1 } ?: from
        return "…" + t.substring(word).trimStart()
    }

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
     * [text] cut into pieces a list renders as they scroll in: at paragraph breaks once a piece has
     * about [size] characters, outside code fences. A piece that would grow past three times that
     * (no breaks, a long fence, a fence never closed) is cut at a line or a space anyway, its fence
     * closed and opened again in the next piece, so no piece is ever huge.
     */
    fun chunks(text: String, size: Int = 1_500): List<String> {
        if (text.length <= size) return listOf(text)
        val max = size * 3
        val out = ArrayList<String>()
        val piece = StringBuilder()
        // The line that opened the code block we are in, or null.
        var fence: String? = null
        fun flush() {
            piece.toString().trim().takeIf { it.isNotEmpty() }?.let { out += it }
            piece.clear()
        }
        for (line in text.lines().flatMap { wrap(it, max) }) {
            val isFence = line.trimStart().startsWith("```")
            if (fence == null && !isFence && line.isBlank() && piece.length >= size) {
                flush()
                continue
            }
            val closing = isFence && fence != null
            if (!closing && piece.isNotBlank() && piece.length + line.length > max) {
                val open = fence
                if (open != null) piece.append("```\n")
                flush()
                if (open != null) piece.append(open).append('\n')
            }
            piece.append(line).append('\n')
            if (isFence) fence = if (fence == null) line.trim() else null
        }
        flush()
        return out
    }

    /** [line] in parts of at most [max] characters, cut at a space where there is one. */
    private fun wrap(line: String, max: Int): List<String> {
        if (line.length <= max) return listOf(line)
        val parts = ArrayList<String>()
        var rest = line
        while (rest.length > max) {
            val cut = rest.lastIndexOf(' ', max).takeIf { it > max / 2 } ?: max
            parts += rest.substring(0, cut)
            rest = rest.substring(cut).trimStart()
        }
        if (rest.isNotEmpty()) parts += rest
        return parts
    }

    /**
     * What pi (or omp) shows itself thinking right now, from its screen with its colours ([ansi],
     * herdr's `pane.read` with `format: ansi`): pi writes a thought to its transcript only with the
     * call or the words it led to, but draws it as it streams, in italics, just above its editor.
     * Null when the newest thing over the editor isn't such a thought.
     */
    fun onScreen(ansi: String): String? {
        val lines = styled(ansi)
        val rules = lines.indices.filter { RULE.matches(lines[it].text.trim()) }
        // pi's editor sits between two rules, with its footer under the second.
        var i = (if (rules.size >= 2) rules[rules.size - 2] else rules.lastOrNull() ?: lines.size) - 1
        while (i >= 0 && (lines[i].text.isBlank() || isLoader(lines[i]))) i--
        val end = i
        while (i >= 0 && (lines[i].text.isBlank() || lines[i].italic)) i--
        if (end < 0 || end == i) return null
        val found = lines.subList(i + 1, end + 1).map { it.text.trimEnd().trimEnd('│').trimEnd() }.dropWhile { it.isBlank() }
        val indent = found.filter { it.isNotBlank() }.minOfOrNull { l -> l.indexOfFirst { it != ' ' } } ?: 0
        // The screen wraps at the pane's width; a phone reads the lines joined into their paragraphs again.
        val out = StringBuilder()
        for (raw in found.map { it.drop(indent) }) {
            when {
                raw.isBlank() -> if (out.isNotEmpty() && !out.endsWith("\n\n")) out.append("\n\n")
                out.isEmpty() || out.endsWith("\n") -> out.append(raw.trim())
                ITEM.containsMatchIn(raw) -> out.append('\n').append(raw.trim())
                else -> out.append(' ').append(raw.trim())
            }
        }
        return out.toString().trim().ifEmpty { null }
    }

    private class Line(val text: String, val italic: Boolean)

    private fun isLoader(line: Line): Boolean {
        val t = line.text.trim()
        return !line.italic && (t.firstOrNull()?.let { it in '⠀'..'⣿' } == true || t.startsWith("Working"))
    }

    /** The screen's lines as plain text, each marked italic when most of its letters are. */
    private fun styled(ansi: String): List<Line> {
        val out = ArrayList<Line>()
        val text = StringBuilder()
        var italic = false
        var letters = 0
        var slanted = 0
        fun end() {
            out += Line(text.toString(), letters > 0 && slanted * 2 >= letters)
            text.clear(); letters = 0; slanted = 0
        }
        var i = 0
        while (i < ansi.length) {
            val c = ansi[i]
            when {
                c == '\u001b' && ansi.getOrNull(i + 1) == '[' -> {
                    var j = i + 2
                    while (j < ansi.length && ansi[j] !in '@'..'~') j++
                    if (ansi.getOrNull(j) == 'm') italic = sgr(ansi.substring(i + 2, j), italic)
                    i = j
                }
                c == '\u001b' && ansi.getOrNull(i + 1) == ']' -> {
                    // An OSC (a link, a title) runs to BEL or ESC \.
                    var j = i + 2
                    while (j < ansi.length && ansi[j] != '\u0007' && !(ansi[j] == '\u001b' && ansi.getOrNull(j + 1) == '\\')) j++
                    i = if (ansi.getOrNull(j) == '\u001b') j + 1 else j
                }
                c == '\u001b' -> i++
                c == '\n' -> end()
                c == '\r' -> {}
                else -> {
                    text.append(c)
                    if (!c.isWhitespace()) { letters++; if (italic) slanted++ }
                }
            }
            i++
        }
        if (text.isNotEmpty()) end()
        return out
    }

    /** Italics after the SGR parameters [params], skipping the colours' own numbers (`38;2;r;g;b`). */
    private fun sgr(params: String, italic: Boolean): Boolean {
        var on = italic
        val p = params.split(';', ':').map { it.toIntOrNull() ?: 0 }
        var k = 0
        while (k < p.size) {
            when (p[k]) {
                0 -> on = false
                3 -> on = true
                23 -> on = false
                38, 48, 58 -> k += when (p.getOrNull(k + 1)) { 2 -> 4; 5 -> 2; else -> 0 }
            }
            k++
        }
        return on
    }

    private val SPACE = Regex("\\s+")
    private val MARKS = Regex("\\*\\*|__|`")
    private val LEAD = Regex("^(#{1,6}|[-*+]|\\d+\\.)\\s+")
    private val RULE = Regex("[─━]{10,}")
    private val ITEM = Regex("^\\s*([-*+•]|\\d+[.)])\\s")
}
