package com.makefunstuff.herdr.core.chat

/**
 * Just enough Markdown for agent replies on a phone: headings, paragraphs, lists, quotes, code
 * blocks and tables, with bold, italic, code, strike-through and links inline.
 */
object Markdown {

    sealed interface Block {
        data class Heading(val level: Int, val text: List<Span>) : Block
        data class Paragraph(val text: List<Span>) : Block
        data class ListItem(val depth: Int, val marker: String, val text: List<Span>, val checked: Boolean? = null) : Block
        data class Quote(val text: List<Span>) : Block
        data class Code(val language: String, val text: String) : Block

        /** A table laid out as aligned monospace rows; [header] says whether the first row is one. */
        data class Table(val rows: List<List<String>>, val header: Boolean) : Block
        data object Rule : Block
    }

    data class Span(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false,
        val strike: Boolean = false,
        val link: String? = null,
    )

    private val HEADING = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val BULLET = Regex("^(\\s*)([-*+])\\s+(.*)$")
    private val TASK = Regex("^\\[([ xX])]\\s+(.*)$")
    private val ORDERED = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$")
    private val FENCE = Regex("^\\s*(```+|~~~+)\\s*([\\w+#.-]*).*$")
    private val RULE = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
    private val TABLE_SEPARATOR = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

    fun parse(source: String): List<Block> {
        val lines = source.replace("\r\n", "\n").lines()
        val blocks = mutableListOf<Block>()
        val paragraph = mutableListOf<String>()
        fun flush() {
            if (paragraph.isNotEmpty()) {
                blocks += Block.Paragraph(inline(paragraph.joinToString(" ") { it.trim() }))
                paragraph.clear()
            }
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val fence = FENCE.matchEntire(line)
            when {
                fence != null -> {
                    flush()
                    val close = fence.groupValues[1]
                    val body = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith(close)) body += lines[i++]
                    blocks += Block.Code(fence.groupValues[2], body.joinToString("\n").trimEnd())
                }
                line.isBlank() -> flush()
                RULE.matches(line) && paragraph.isEmpty() -> blocks += Block.Rule
                HEADING.matches(line) -> {
                    flush()
                    val m = HEADING.matchEntire(line)!!
                    blocks += Block.Heading(m.groupValues[1].length, inline(m.groupValues[2]))
                }
                line.trimStart().startsWith("|") && i + 1 < lines.size && TABLE_SEPARATOR.matches(lines[i + 1]) -> {
                    flush()
                    val rows = mutableListOf(cells(line))
                    i += 2
                    while (i < lines.size && lines[i].trimStart().startsWith("|")) rows += cells(lines[i++])
                    blocks += Block.Table(rows, header = true)
                    continue
                }
                BULLET.matches(line) || ORDERED.matches(line) -> {
                    flush()
                    val m = BULLET.matchEntire(line) ?: ORDERED.matchEntire(line)!!
                    val depth = m.groupValues[1].replace("\t", "    ").length / 2
                    val marker = if (m.groupValues[2].first().isDigit()) m.groupValues[2] + "." else "•"
                    var text = m.groupValues[3]
                    // Lines indented under an item, with no marker of their own, continue it.
                    while (i + 1 < lines.size && lines[i + 1].isNotBlank() && lines[i + 1].startsWith("  ") &&
                        !BULLET.matches(lines[i + 1]) && !ORDERED.matches(lines[i + 1]) && !FENCE.matches(lines[i + 1])
                    ) {
                        text += " " + lines[++i].trim()
                    }
                    val task = TASK.matchEntire(text)
                    if (task != null) text = task.groupValues[2]
                    blocks += Block.ListItem(minOf(depth, 6), marker, inline(text), task?.let { it.groupValues[1] != " " })
                }
                line.trimStart().startsWith(">") -> {
                    flush()
                    val quote = mutableListOf<String>()
                    while (i < lines.size && lines[i].trimStart().startsWith(">")) quote += lines[i++].trimStart().removePrefix(">").trim()
                    blocks += Block.Quote(inline(quote.joinToString(" ")))
                    continue
                }
                else -> paragraph += line
            }
            i++
        }
        flush()
        return blocks
    }

    /** The first line of [source] as plain text, markers dropped: for a one-line summary in a list. */
    fun plain(source: String): String {
        var fenced = false
        val first = source.lineSequence().map { it.trim() }.firstOrNull { line ->
            if (FENCE.matches(line)) { fenced = !fenced; false } else !fenced && line.isNotEmpty()
        }.orEmpty()
        val bare = first.replace(Regex("^(#{1,6}\\s+|>\\s*|[-*+]\\s+(\\[[ xX]]\\s+)?|\\d{1,3}[.)]\\s+)"), "")
        return inline(bare).joinToString("") { it.text }
    }

    private fun cells(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim().replace("**", "").replace("`", "") }

    /** Splits inline Markdown into styled spans. Unmatched markers stay as plain text. */
    fun inline(text: String): List<Span> {
        val out = mutableListOf<Span>()
        val plain = StringBuilder()
        var bold = false
        var italic = false
        var strike = false
        fun emit(s: String, code: Boolean = false, link: String? = null) {
            if (s.isEmpty()) return
            out += Span(s, bold, italic, code, strike, link)
        }
        fun flushPlain() {
            emit(plain.toString())
            plain.clear()
        }

        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length && text[i + 1] in "\\`*_[]()#+-.!|~<>" -> {
                    plain.append(text[i + 1]); i += 2
                }
                c == '`' -> {
                    val ticks = text.drop(i).takeWhile { it == '`' }.length
                    val close = text.indexOf("`".repeat(ticks), i + ticks)
                    if (close < 0) { plain.append(text, i, i + ticks); i += ticks } else {
                        flushPlain()
                        emit(text.substring(i + ticks, close).trim(), code = true)
                        i = close + ticks
                    }
                }
                text.startsWith("**", i) || text.startsWith("__", i) -> {
                    val marker = text.substring(i, i + 2)
                    if (bold || text.indexOf(marker, i + 2) > i + 2) {
                        flushPlain(); bold = !bold
                    } else {
                        plain.append(marker)
                    }
                    i += 2
                }
                text.startsWith("~~", i) -> {
                    if (strike || text.indexOf("~~", i + 2) > i + 2) { flushPlain(); strike = !strike } else plain.append("~~")
                    i += 2
                }
                (c == '*' || c == '_') && italicToggles(text, i, italic) -> {
                    flushPlain(); italic = !italic; i++
                }
                c == '[' -> {
                    val close = text.indexOf("](", i)
                    val end = if (close > 0) text.indexOf(')', close + 2) else -1
                    if (close > i && end > close && !text.substring(i + 1, close).contains('[')) {
                        flushPlain()
                        emit(text.substring(i + 1, close), link = text.substring(close + 2, end).trim().substringBefore(' '))
                        i = end + 1
                    } else {
                        plain.append(c); i++
                    }
                }
                c == '<' && Regex("^<(https?://[^>\\s]+)>").find(text.substring(i)) != null -> {
                    val url = Regex("^<(https?://[^>\\s]+)>").find(text.substring(i))!!
                    flushPlain()
                    emit(url.groupValues[1], link = url.groupValues[1])
                    i += url.value.length
                }
                text.startsWith("https://", i) || text.startsWith("http://", i) -> {
                    var end = i
                    while (end < text.length && !text[end].isWhitespace() && text[end] !in "<>\"'`") end++
                    while (end > i && text[end - 1] in ".,;:!?)*_~") end--
                    flushPlain()
                    val url = text.substring(i, end)
                    emit(url, link = url)
                    i = end
                }
                else -> {
                    plain.append(c); i++
                }
            }
        }
        flushPlain()
        return out
    }

    /** An emphasis marker opens before a word and closes after one; `snake_case` and `2 * 3` stay as they are. */
    private fun italicToggles(text: String, i: Int, open: Boolean): Boolean {
        val before = text.getOrNull(i - 1)
        val after = text.getOrNull(i + 1)
        return if (open) {
            before != null && !before.isWhitespace() && (after == null || !after.isLetterOrDigit())
        } else {
            after != null && !after.isWhitespace() && (before == null || !before.isLetterOrDigit()) &&
                text.indexOf(text[i], i + 1) > i + 1
        }
    }
}
