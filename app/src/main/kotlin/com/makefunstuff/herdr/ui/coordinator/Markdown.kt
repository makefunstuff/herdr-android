package com.makefunstuff.herdr.ui.coordinator

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * Enough Markdown for an agent's chat message: headings, bullets, code blocks, and inline bold,
 * italics, code and links (shown as their text).
 */
object Markdown {
    private val bullet = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val heading = Regex("^#{1,6}\\s+(.*)$")
    private val inline = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__|`([^`]+)`|\\[([^\\]]+)]\\(([^)]+)\\)|(?<![\\w*])\\*([^*\\s][^*]*?)\\*(?![\\w*])")

    fun render(text: String, codeBackground: Color): AnnotatedString = buildAnnotatedString {
        val code = SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)
        var inFence = false
        var first = true
        for (raw in text.trimEnd().lines()) {
            if (raw.trimStart().startsWith("```")) {
                inFence = !inFence
                continue
            }
            if (!first) append('\n')
            first = false
            if (inFence) {
                withStyle(code) { append(raw) }
            } else {
                val h = heading.matchEntire(raw)
                val b = bullet.matchEntire(raw)
                when {
                    h != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { inline(h.groupValues[1], code) }
                    b != null -> {
                        append(b.groupValues[1] + "•  ")
                        inline(b.groupValues[2], code)
                    }
                    else -> inline(raw, code)
                }
            }
        }
    }

    private fun AnnotatedString.Builder.inline(s: String, code: SpanStyle) {
        var at = 0
        for (m in inline.findAll(s)) {
            append(s.substring(at, m.range.first))
            val g = m.groupValues
            when {
                g[1].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { inline(g[1], code) }
                g[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { inline(g[2], code) }
                g[3].isNotEmpty() -> withStyle(code) { append(g[3]) }
                g[4].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(g[4]) }
                g[6].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[6]) }
            }
            at = m.range.last + 1
        }
        append(s.substring(at))
    }
}
