package com.makefunstuff.herdr.ui.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.makefunstuff.herdr.core.chat.Markdown

/** An agent's Markdown reply, laid out for a phone. */
@Composable
fun MarkdownText(source: String, modifier: Modifier = Modifier) {
    val blocks = remember(source) { Markdown.parse(source) }
    val colors = MaterialTheme.colorScheme
    val codeBackground = colors.surfaceContainerHighest
    val link = colors.primary
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (block in blocks) {
            when (block) {
                is Markdown.Block.Heading -> Text(
                    block.text.annotated(codeBackground, link),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    modifier = Modifier.padding(top = 4.dp),
                )
                is Markdown.Block.Paragraph -> Text(block.text.annotated(codeBackground, link), style = MaterialTheme.typography.bodyMedium)
                is Markdown.Block.ListItem -> Row(Modifier.padding(start = (block.depth * 16).dp)) {
                    Text(
                        block.marker,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.width(if (block.marker == "•") 14.dp else 24.dp),
                    )
                    Text(block.text.annotated(codeBackground, link), style = MaterialTheme.typography.bodyMedium)
                }
                is Markdown.Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(colors.outlineVariant, RoundedCornerShape(2.dp)))
                    Text(
                        block.text.annotated(codeBackground, link),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
                is Markdown.Block.Code -> CodeBlock(block.text)
                is Markdown.Block.Table -> CodeBlock(tableText(block.rows))
                Markdown.Block.Rule -> HorizontalDivider(color = colors.outlineVariant)
            }
        }
    }
}

/** Monospace text in a scrollable box, for code and for tables. */
@Composable
fun CodeBlock(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        maxLines = maxLines,
        color = MaterialTheme.colorScheme.onSurface,
        softWrap = false,
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

private fun tableText(rows: List<List<String>>): String {
    val columns = rows.maxOfOrNull { it.size } ?: 0
    val widths = (0 until columns).map { c -> rows.maxOf { it.getOrNull(c)?.length ?: 0 }.coerceAtMost(40) }
    val lines = rows.map { row -> (0 until columns).joinToString("  ") { c -> (row.getOrNull(c) ?: "").take(40).padEnd(widths[c]) }.trimEnd() }
    val rule = widths.joinToString("  ") { "─".repeat(it) }
    return (listOf(lines.first(), rule) + lines.drop(1)).joinToString("\n")
}

private fun List<Markdown.Span>.annotated(codeBackground: Color, linkColor: Color): AnnotatedString = buildAnnotatedString {
    for (span in this@annotated) {
        val style = SpanStyle(
            fontWeight = if (span.bold) FontWeight.SemiBold else null,
            fontStyle = if (span.italic) FontStyle.Italic else null,
            fontFamily = if (span.code) FontFamily.Monospace else null,
            background = if (span.code) codeBackground else Color.Unspecified,
            fontSize = if (span.code) 13.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
            textDecoration = if (span.strike) TextDecoration.LineThrough else null,
        )
        val url = span.link?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        if (url != null) {
            withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))) {
                withStyle(style) { append(span.text) }
            }
        } else if (span.link != null) {
            // Local paths can't be opened on the phone; show them like code.
            withStyle(style.merge(SpanStyle(color = linkColor))) { append(span.text) }
        } else {
            withStyle(style) { append(span.text) }
        }
    }
}
