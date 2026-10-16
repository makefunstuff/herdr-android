package com.makefunstuff.herdr.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Radius
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
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

/**
 * The app's one Markdown view: chat replies, and everything the board shows (descriptions, closing
 * notes, findings, plans). Selectable, themed, links open in the browser; code blocks and tables
 * scroll sideways and code can be copied.
 */
@Composable
fun MarkdownText(
    source: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
) {
    val blocks = remember(source) { Markdown.parse(source) }
    val colors = MaterialTheme.colorScheme
    val codeBackground = Herdr.colors.field
    val link = Herdr.colors.accent
    SelectionContainer {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (block in blocks) {
                when (block) {
                    is Markdown.Block.Heading -> Text(
                        block.text.annotated(codeBackground, link),
                        style = when (block.level) {
                            1 -> MaterialTheme.typography.titleLarge
                            2 -> MaterialTheme.typography.titleMedium
                            else -> MaterialTheme.typography.titleSmall
                        },
                        color = color,
                        modifier = Modifier.padding(top = 4.dp).semantics { heading() },
                    )
                    is Markdown.Block.Paragraph -> Text(block.text.annotated(codeBackground, link), style = style, color = color)
                    is Markdown.Block.ListItem -> Row(Modifier.padding(start = (block.depth * 16).dp), verticalAlignment = Alignment.Top) {
                        if (block.checked != null) {
                            Text(
                                if (block.checked == true) "☑" else "☐", style = style,
                                color = if (block.checked == true) Herdr.colors.accent else colors.onSurfaceVariant,
                                modifier = Modifier.padding(end = 6.dp).semantics { contentDescription = if (block.checked == true) "Done" else "Not done" },
                            )
                        } else {
                            Text(
                                block.marker, style = style, color = colors.onSurfaceVariant,
                                modifier = Modifier.width(if (block.marker == "•") 14.dp else 26.dp),
                            )
                        }
                        Text(block.text.annotated(codeBackground, link), style = style, color = color, modifier = Modifier.weight(1f))
                    }
                    is Markdown.Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                        Box(Modifier.width(3.dp).fillMaxHeight().background(Herdr.colors.fieldFocused, RoundedCornerShape(2.dp)))
                        Text(
                            block.text.annotated(codeBackground, link),
                            style = style,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(start = 10.dp),
                        )
                    }
                    is Markdown.Block.Code -> CodeBlock(block.text, copyable = true)
                    is Markdown.Block.Table -> TableBlock(block.rows)
                    Markdown.Block.Rule -> HorizontalDivider(color = Herdr.colors.line, modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }
    }
}

/** Monospace text in a sideways-scrolling box, for code; [copyable] adds a copy button. */
@Composable
fun CodeBlock(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE, copyable: Boolean = false) {
    val context = LocalContext.current
    Box(modifier.fillMaxWidth().background(Herdr.colors.field, RoundedCornerShape(Radius.m))) {
        Text(
            text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            maxLines = maxLines,
            color = MaterialTheme.colorScheme.onSurface,
            softWrap = false,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, end = if (copyable) 48.dp else 12.dp, top = 10.dp, bottom = 10.dp),
        )
        if (copyable) {
            Box(
                Modifier.align(Alignment.TopEnd).size(48.dp).clickable(onClickLabel = "Copy code") { context.copyToClipboard("code", text) }
                    .semantics { contentDescription = "Copy" },
                contentAlignment = Alignment.Center,
            ) { Icon(HerdrIcons.ContentCopy, null, tint = Herdr.colors.muted, modifier = Modifier.size(18.dp)) }
        }
    }
}

/** A table as a grid of cells that scrolls sideways when it is wider than the screen. */
@Composable
private fun TableBlock(rows: List<List<String>>) {
    val columns = rows.maxOfOrNull { it.size } ?: return
    val line = Herdr.colors.fieldFocused
    Column(
        Modifier.horizontalScroll(rememberScrollState())
            .border(1.dp, line, RoundedCornerShape(Radius.m)).clip(RoundedCornerShape(Radius.m)),
    ) {
        rows.forEachIndexed { r, row ->
            if (r > 0) HorizontalDivider(color = line)
            Row(Modifier.height(IntrinsicSize.Min).then(if (r == 0) Modifier.background(Herdr.colors.field) else Modifier)) {
                for (c in 0 until columns) {
                    if (c > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(line))
                    Text(
                        row.getOrNull(c).orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (r == 0) FontWeight.SemiBold else null,
                        modifier = Modifier.widthIn(min = 56.dp, max = 220.dp).padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
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
