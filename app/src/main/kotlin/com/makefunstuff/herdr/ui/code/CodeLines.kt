package com.makefunstuff.herdr.ui.code

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.makefunstuff.herdr.core.code.Language
import com.makefunstuff.herdr.core.code.Syntax
import com.makefunstuff.herdr.data.CodeView
import com.makefunstuff.herdr.ui.theme.CodeStyle
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.HerdrColors
import com.makefunstuff.herdr.ui.theme.Space

/** One row of code on screen: a line of a file, or of a diff (with its hunk's heading and Git's notes). */
@Immutable
data class CodeRow(
    val key: String,
    val text: String,
    /** The gutter: one number for a file's line, two (old, new) for a diff's. */
    val numbers: List<Int?>,
    val kind: Kind = Kind.Code,
    /** The highlighter's state at the line's start ([Syntax.states]). */
    val state: Int = 0,
) {
    enum class Kind { Code, Added, Removed, Hunk, Note }

    /** A line of code, which can be chosen for a comment (a hunk's heading can't). */
    val selectable: Boolean get() = kind == Kind.Code || kind == Kind.Added || kind == Kind.Removed

    /** The line as quoted in a comment: a diff's with its `+`, `-` or space. */
    val quoted: String get() = when (kind) {
        Kind.Added -> "+$text"
        Kind.Removed -> "-$text"
        else -> if (numbers.size == 2) " $text" else text
    }
}

/** Characters of a line shown at most: a minified file's one huge line would take long to lay out. */
private const val LONGEST_SHOWN = 4000

/** The text style of code at [size] sp: JetBrains Mono, the line 1.5 times as high. */
fun codeStyle(size: Int): TextStyle = CodeStyle.copy(fontSize = size.sp, lineHeight = (size * 1.5f).sp)

/**
 * Lines of code, numbered, coloured by [language], each chosen with a tap on its number or a long
 * press (then a tap on another number chooses the lines between). Wrapped, or scrolled sideways
 * as a whole when [CodeView.wrap] is off. [chosen] and [marked] are row indexes.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CodeLines(
    rows: List<CodeRow>,
    language: Language?,
    view: CodeView,
    chosen: IntRange?,
    marked: Int?,
    onChoose: (Int) -> Unit,
    state: LazyListState,
    modifier: Modifier = Modifier,
    bottom: Dp = 0.dp,
) {
    val c = Herdr.colors
    val style = codeStyle(view.textSize)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val charWidth = remember(style, density) { with(density) { measurer.measure("0000000000", style).size.width.toDp() / 10 } }
    // One column per number, as wide as the largest.
    val columns = rows.firstOrNull { it.selectable }?.numbers?.size ?: 1
    val digits = remember(rows) {
        (0 until columns).map { col -> rows.maxOfOrNull { r -> r.numbers.getOrNull(col)?.toString()?.length ?: 0 }?.coerceAtLeast(2) ?: 2 }
    }
    val gutter = digits.map { charWidth * it + Space.s + Space.xs }
    val diff = columns == 2
    val longest = remember(rows) { rows.maxOfOrNull { minOf(it.text.length, LONGEST_SHOWN) } ?: 0 }

    BoxWithConstraints(modifier.fillMaxWidth().background(c.panel)) {
        val contentWidth = gutter.fold(0.dp) { a, b -> a + b } + (if (diff) charWidth * 2 else 0.dp) + charWidth * (longest + 2) + Space.m * 2
        val width = if (view.wrap) maxWidth else maxOf(maxWidth, contentWidth)
        val sideways = rememberScrollState()
        Box(if (view.wrap) Modifier.fillMaxHeight() else Modifier.fillMaxHeight().horizontalScroll(sideways)) {
            LazyColumn(
                state = state,
                modifier = Modifier.width(width).fillMaxHeight(),
                contentPadding = PaddingValues(top = Space.xs, bottom = bottom + Space.l),
            ) {
                itemsIndexed(rows, key = { _, r -> r.key }) { i, row ->
                    val on = chosen != null && i in chosen
                    Line(row, language, style, c, on, on || i == marked, gutter, diff, view.wrap, chooseOnTap = chosen != null) { onChoose(i) }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Line(
    row: CodeRow,
    language: Language?,
    style: TextStyle,
    c: HerdrColors,
    chosen: Boolean,
    marked: Boolean,
    gutter: List<Dp>,
    diff: Boolean,
    wrap: Boolean,
    chooseOnTap: Boolean,
    onChoose: () -> Unit,
) {
    val tint = when {
        marked -> c.accentTint.compositeOver(c.panel)
        row.kind == CodeRow.Kind.Added -> c.lineTint(c.done).compositeOver(c.panel)
        row.kind == CodeRow.Kind.Removed -> c.lineTint(c.red).compositeOver(c.panel)
        row.kind == CodeRow.Kind.Hunk -> c.hover
        else -> Color.Transparent
    }
    if (row.kind == CodeRow.Kind.Hunk || row.kind == CodeRow.Kind.Note) {
        Text(
            row.text, style = style, color = c.muted, maxLines = if (wrap) 3 else 1, softWrap = wrap,
            modifier = Modifier.fillMaxWidth().background(tint).padding(horizontal = Space.m, vertical = if (row.kind == CodeRow.Kind.Hunk) Space.xs else 0.dp),
        )
        return
    }
    val text = remember(row.text, row.state, language, c) { highlight(row, language, c) }
    val number = row.numbers.lastOrNull { it != null } ?: row.numbers.firstOrNull()
    val what = number?.let { if (diff && row.kind == CodeRow.Kind.Removed) "old line $it" else "line $it" } ?: "this line"
    Row(
        Modifier.fillMaxWidth().background(tint)
            .combinedClickable(
                onClickLabel = if (chooseOnTap) "Choose up to $what" else null,
                onLongClickLabel = "Choose $what",
                onClick = { if (chooseOnTap) onChoose() },
                onLongClick = onChoose,
                indication = null,
                interactionSource = null,
            ),
        verticalAlignment = Alignment.Top,
    ) {
        row.numbers.forEachIndexed { col, n ->
            Text(
                n?.toString().orEmpty(), style = style, textAlign = TextAlign.End, maxLines = 1,
                color = if (marked) c.accent else c.lineNumber,
                modifier = Modifier.width(gutter[col]).heightIn(min = 24.dp).clickable(onClickLabel = "Choose $what", onClick = onChoose)
                    .padding(end = Space.xs)
                    .semantics { contentDescription = if (chosen) "$what, chosen" else what },
            )
        }
        if (diff) Text(
            when (row.kind) { CodeRow.Kind.Added -> "+"; CodeRow.Kind.Removed -> "−"; else -> " " },
            style = style, color = when (row.kind) { CodeRow.Kind.Added -> c.done; CodeRow.Kind.Removed -> c.red; else -> c.muted },
            modifier = Modifier.padding(start = Space.xs),
        )
        Text(
            text, style = style, color = c.text, softWrap = wrap,
            modifier = Modifier.padding(start = Space.s, end = Space.m).then(if (wrap) Modifier.weight(1f) else Modifier),
        )
    }
}

internal fun highlight(row: CodeRow, language: Language?, c: HerdrColors): AnnotatedString {
    val shown = if (row.text.length > LONGEST_SHOWN) row.text.take(LONGEST_SHOWN) else row.text
    // Tabs as four spaces: a phone has no room for eight.
    val text = shown.replace("\t", "    ")
    return buildAnnotatedString {
        append(text)
        if (row.text.length > LONGEST_SHOWN) append(" … ${row.text.length - LONGEST_SHOWN} more characters")
        if (language == null) return@buildAnnotatedString
        val spans = Syntax.line(language, shown, row.state).spans
        if (spans.isEmpty()) return@buildAnnotatedString
        // Where each character of the line landed once its tabs were widened.
        val at = IntArray(shown.length + 1)
        var k = 0
        for (i in shown.indices) { at[i] = k; k += if (shown[i] == '\t') 4 else 1 }
        at[shown.length] = k
        spans.forEach { s -> addStyle(SpanStyle(color = c.syntax(s.role)), at[s.start.coerceIn(0, shown.length)], at[s.end.coerceIn(0, shown.length)]) }
    }
}

/** "Line 12" or "Lines 12–14" for the rows [range] of [rows]: their own numbers, a diff's new ones first. */
fun linesLabel(rows: List<CodeRow>, range: IntRange): String {
    val (start, end, old) = lineSpan(rows, range)
    val what = if (start == end) "Line $start" else "Lines $start–$end"
    return if (old) "Old ${what.replaceFirstChar { it.lowercase() }}" else what
}

/** The first and last line numbers of [range], and whether they are a diff's old ones (only removed lines were chosen). */
fun lineSpan(rows: List<CodeRow>, range: IntRange): Triple<Int, Int, Boolean> {
    val chosen = range.mapNotNull { rows.getOrNull(it) }.filter { it.selectable }
    val new = chosen.mapNotNull { it.numbers.last() }
    if (new.isNotEmpty()) return Triple(new.min(), new.max(), false)
    val old = chosen.mapNotNull { it.numbers.first() }
    return Triple(old.minOrNull() ?: 0, old.maxOrNull() ?: 0, chosen.firstOrNull()?.numbers?.size == 2)
}

/** The chosen rows' code, as a comment quotes it. */
fun quote(rows: List<CodeRow>, range: IntRange): String =
    range.mapNotNull { rows.getOrNull(it) }.filter { it.selectable }.joinToString("\n") { it.quoted }

/** A tap on [i] with [chosen] lines: the first chooses a line, the next the lines between; the same line again lets go. */
fun choose(chosen: IntRange?, anchor: Int?, i: Int): Pair<IntRange?, Int?> = when {
    chosen == null || anchor == null -> (i..i) to i
    chosen.first == i && chosen.last == i -> null to null
    else -> (minOf(anchor, i)..maxOf(anchor, i)) to anchor
}

/** A line under the code that says what it is (cut, binary, empty). */
@Composable
fun CodeNote(text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = Space.screen, vertical = Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = Herdr.colors.muted)
    }
}
