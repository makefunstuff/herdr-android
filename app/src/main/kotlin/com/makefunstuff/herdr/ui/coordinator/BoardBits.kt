package com.makefunstuff.herdr.ui.coordinator

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.makefunstuff.herdr.core.chat.Markdown
import com.makefunstuff.herdr.ui.common.MarkdownText
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.makefunstuff.herdr.core.board.BoardGlance
import com.makefunstuff.herdr.core.board.BoardStatus
import com.makefunstuff.herdr.core.board.BoardTask
import com.makefunstuff.herdr.core.board.CoordinatorAsk
import com.makefunstuff.herdr.core.board.TaskMentions
import com.makefunstuff.herdr.ui.board.color
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrShapes
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.design.RowDivider
import com.makefunstuff.herdr.ui.design.StatusBadge
import com.makefunstuff.herdr.ui.design.hairline
import com.makefunstuff.herdr.ui.theme.CodeStyle
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space

/**
 * The board at a glance under the Coordinator's bar: how many tasks need you, are at work and wait
 * their turn, and the way to the board. The whole strip opens it.
 */
@Composable
internal fun BoardStrip(glance: BoardGlance, onOpen: () -> Unit) {
    val c = Herdr.colors
    val words = listOfNotNull(
        glance.needYou.takeIf { it > 0 }?.let { "$it need you" },
        glance.working.takeIf { it > 0 }?.let { "$it working" },
        glance.queued.takeIf { it > 0 }?.let { "$it queued" },
    )
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClickLabel = "Open the board", onClick = onOpen)
                .padding(horizontal = Space.screen)
                .semantics(mergeDescendants = true) {
                    contentDescription = "Board: " + (words.joinToString(", ").ifEmpty { "nothing waits for you" })
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            if (glance.quiet) Text("Nothing waits for you", style = MaterialTheme.typography.labelLarge, color = c.muted)
            if (glance.needYou > 0) StatusBadge("${glance.needYou} need you", c.red)
            if (glance.working > 0) StatusBadge("${glance.working} working", c.working)
            if (glance.queued > 0) StatusBadge("${glance.queued} queued", BoardStatus.Queued.color())
            Spacer(Modifier.weight(1f))
            Text("Open board", style = MaterialTheme.typography.labelLarge, color = c.accent, maxLines = 1)
        }
        RowDivider(inset = 0.dp)
    }
}

/**
 * A task the Coordinator named, as a row with its live state: a stripe in the board's colour, the
 * key, the title and where it stands, and under them what the answer says of it ([note]) beyond
 * the title. A tap opens it on the board.
 */
@Composable
internal fun TaskMention(t: BoardTask, note: String, onOpen: () -> Unit) {
    val c = Herdr.colors
    val color = t.status.color()
    val state = listOfNotNull(t.status.word, t.status.detail, t.progress?.takeIf { t.status == BoardStatus.Working }?.let { "$it%" }).joinToString(" · ")
    val said = remember(note, t.title) { TaskMentions.beyondTitle(note, t.title).ifBlank { null } }
    QuietCard(
        Modifier.padding(vertical = Space.xs).semantics(mergeDescendants = true) {
            contentDescription = listOfNotNull(t.name, t.title, state, said?.lines()?.joinToString(" ") { Markdown.plain(it) }).joinToString(", ")
        },
        onClick = onOpen,
        onClickLabel = "Open on the board",
    ) {
        // With the answer's words under the title, the key and the chevron stay by the title.
        val top = said != null
        Row(Modifier.height(IntrinsicSize.Min).heightIn(min = 56.dp), verticalAlignment = if (top) Alignment.Top else Alignment.CenterVertically) {
            Box(Modifier.padding(start = Space.s, top = Space.m, bottom = Space.m).width(if (t.needsYou) 4.dp else 3.dp).fillMaxHeight().clip(HerdrShapes.Pill).background(color))
            Text(t.name, style = CodeStyle.copy(fontSize = 13.sp), color = c.strong, maxLines = 1, modifier = Modifier.padding(start = Space.m, top = if (top) Space.s + 6.dp else 0.dp))
            Column(Modifier.weight(1f).padding(start = Space.m, top = Space.s, bottom = Space.s)) {
                Text(
                    t.title, style = MaterialTheme.typography.titleSmall, color = if (t.status == BoardStatus.Done) c.muted else c.strong,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(state, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (said != null) MarkdownText(said, Modifier.padding(top = Space.xs), style = MaterialTheme.typography.bodyMedium, color = c.text)
            }
            Icon(HerdrIcons.ChevronRight, null, tint = c.faint, modifier = Modifier.padding(start = Space.m, end = Space.m, top = if (top) Space.s + 8.dp else 0.dp).size(18.dp))
        }
    }
}

/** The suggested asks above the composer, in one row that scrolls sideways; a tap puts the words in the message box. */
@Composable
internal fun AskRow(asks: List<CoordinatorAsk>, onAsk: (CoordinatorAsk) -> Unit, modifier: Modifier = Modifier) {
    val c = Herdr.colors
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.screen),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        asks.forEach { a ->
            Box(
                Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = "Put in the message box") { onAsk(a) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    a.label,
                    style = MaterialTheme.typography.labelLarge, color = c.text, maxLines = 1,
                    modifier = Modifier.heightIn(min = 36.dp).clip(HerdrShapes.Pill).background(c.card)
                        .hairline(HerdrShapes.Pill, c.line).padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}
