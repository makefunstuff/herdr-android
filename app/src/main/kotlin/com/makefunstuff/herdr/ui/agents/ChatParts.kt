package com.makefunstuff.herdr.ui.agents

import com.makefunstuff.herdr.ui.theme.Mono
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.unit.sp
import com.makefunstuff.herdr.ui.theme.CodeStyle
import androidx.compose.ui.draw.drawBehind
import com.makefunstuff.herdr.core.chat.BlockedPrompt
import com.makefunstuff.herdr.core.chat.ChatItem
import com.makefunstuff.herdr.core.chat.ToolState
import com.makefunstuff.herdr.core.chat.Away
import com.makefunstuff.herdr.core.chat.ChatTurn
import com.makefunstuff.herdr.core.chat.ChatTurns
import com.makefunstuff.herdr.core.chat.ToolLine
import com.makefunstuff.herdr.ui.code.AddedRemoved
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.makefunstuff.herdr.core.host.Images
import com.makefunstuff.herdr.ui.images.MessageImages
import com.makefunstuff.herdr.ui.common.CodeBlock
import com.makefunstuff.herdr.ui.common.MarkdownText
import com.makefunstuff.herdr.ui.common.StatusDot
import com.makefunstuff.herdr.ui.design.BubbleShape
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.KeyChip
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Radius
import com.makefunstuff.herdr.ui.theme.Space

/** What the chat list shows: transcript items cut into turns, each turn's work folded under one header. */
sealed interface ChatRow {
    val key: String

    data class User(val item: ChatItem.User, val pending: Boolean = false) : ChatRow {
        override val key get() = if (pending) "pending/${item.id}" else item.id
    }
    /** The agent's words: its answer, or ([quiet]) narration inside a settled turn's work. */
    data class Agent(val item: ChatItem.Agent, val quiet: Boolean = false) : ChatRow { override val key get() = item.id }
    data class Tools(val tools: List<ChatItem.Tool>, val lines: Map<String, ToolLine> = emptyMap()) : ChatRow {
        override val key get() = "tools/" + tools.first().id
    }
    data class Notice(val item: ChatItem.Notice) : ChatRow { override val key get() = item.id }
    data class Screen(val text: String) : ChatRow { override val key get() = "screen" }
    data class Info(override val key: String, val text: String) : ChatRow
    /** When a turn started: `10:05`, or `Oct 16, 09:12` on another day. */
    data class Time(override val key: String, val text: String) : ChatRow
    /** A turn's work header: "Worked for 2m 36s · 1 edit · 1 command", tapped to fold or open; under [time], when the turn started. */
    data class Head(val turn: String, val title: String, val summary: String, val failed: Int, val live: Live?, val open: Boolean, val time: String? = null) : ChatRow {
        override val key get() = "head/$turn"
    }

    /** What a turn still running does: works, or waits for you. */
    enum class Live { Working, Blocked }

    /** A row that counts as a new message while the reader is scrolled up. */
    val isMessage: Boolean get() = (this is User && !pending) || (this is Agent && !quiet)
}

/**
 * The rows of [turns]: what you said, when the turn started, its work under a header (open while it
 * runs, folded once it settles unless [opened] says otherwise) and its answer outside the fold.
 * [live] is the newest turn's state while it runs; [now] times it; [time] says when a turn started.
 */
fun chatRows(
    turns: List<ChatTurn>,
    steps: ChatTurns.Steps,
    live: ChatRow.Live?,
    opened: Map<String, Boolean>,
    now: Long,
    time: (Long) -> String?,
): List<ChatRow> {
    val rows = mutableListOf<ChatRow>()
    turns.forEachIndexed { i, turn ->
        turn.user?.let { rows += ChatRow.User(it) }
        val started = turn.at.takeIf { it > 0 }?.let(time)
        val running = live.takeIf { i == turns.lastIndex }
        if (turn.hasWork) {
            val open = opened[turn.key] ?: (running != null || turn.keepsOpen)
            val title = when {
                running == ChatRow.Live.Blocked -> "Needs you"
                running != null -> if (turn.at > 0 && now > turn.at) "Working for ${ChatTurns.duration(now - turn.at)}" else "Working…"
                else -> turn.took?.let { "Worked for ${ChatTurns.duration(it)}" } ?: "Worked"
            }
            rows += ChatRow.Head(turn.key, title, turn.counts.summary, turn.failed, running, open, started)
            if (open) rows += workRows(turn.work, steps, quiet = running == null)
        } else {
            started?.let { rows += ChatRow.Time("time/${turn.key}", it) }
            rows += workRows(turn.work, steps, quiet = false)
        }
        turn.answer.forEach { rows += ChatRow.Agent(it) }
    }
    return rows
}

/** A turn's work as rows: runs of tool calls as one group, narration and markers between them. */
private fun workRows(items: List<ChatItem>, steps: ChatTurns.Steps, quiet: Boolean): List<ChatRow> {
    val rows = mutableListOf<ChatRow>()
    val run = mutableListOf<ChatItem.Tool>()
    fun flush() {
        if (run.isNotEmpty()) rows += ChatRow.Tools(run.toList(), run.associate { it.id to ChatTurns.line(it, steps) })
        run.clear()
    }
    for (item in items) {
        when (item) {
            is ChatItem.Tool -> run += item
            is ChatItem.Agent -> { flush(); rows += ChatRow.Agent(item, quiet) }
            is ChatItem.Notice -> { flush(); rows += ChatRow.Notice(item) }
            is ChatItem.User -> { flush(); rows += ChatRow.User(item) }
        }
    }
    flush()
    return rows
}

@Composable
fun ChatRowView(row: ChatRow, onToggle: (ChatRow.Head) -> Unit = {}) {
    when (row) {
        is ChatRow.User -> UserBubble(row.item, row.pending)
        is ChatRow.Agent ->
            // Narration in a settled turn is a quiet row again, under the answer's voice.
            if (row.quiet) MarkdownText(row.item.text, Modifier.fillMaxWidth().padding(horizontal = Space.xs), style = MaterialTheme.typography.bodySmall, color = Herdr.colors.muted)
            else MarkdownText(row.item.text, Modifier.fillMaxWidth().padding(end = Space.s), style = MaterialTheme.typography.bodyLarge)
        is ChatRow.Tools -> ToolGroup(row.tools, row.lines)
        is ChatRow.Notice -> NoticeLine(row.item.text)
        is ChatRow.Screen -> SelectionContainer { CodeBlock(row.text) }
        is ChatRow.Info -> NoticeLine(row.text)
        is ChatRow.Time -> TimeLine(row.text)
        is ChatRow.Head -> TurnHead(row) { onToggle(row) }
    }
}

@Composable
private fun UserBubble(item: ChatItem.User, pending: Boolean) {
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    // The pictures it names show as pictures, over the words.
    val split = remember(item.text) { Images.split(item.text) }
    val text = split.text
    val long = text.length > 600 || text.count { it == '\n' } > 12
    Row(Modifier.fillMaxWidth().padding(start = 48.dp), horizontalArrangement = Arrangement.End) {
        // Your turn: a neutral raised card (`.chat-turn-user`), never a wall of colour.
        Surface(
            color = Herdr.colors.card,
            contentColor = Herdr.colors.text,
            shape = BubbleShape,
            border = if (Herdr.colors.bubbleBorder.alpha > 0f) androidx.compose.foundation.BorderStroke(1.dp, Herdr.colors.bubbleBorder) else null,
            modifier = Modifier.alpha(if (pending) 0.6f else 1f).animateContentSize(),
        ) {
            Column(Modifier.padding(horizontal = Space.l, vertical = Space.m)) {
                if (split.paths.isNotEmpty()) MessageImages(split.paths, Modifier.padding(bottom = if (text.isEmpty()) 0.dp else Space.s))
                if (text.isNotEmpty()) SelectionContainer {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = if (long && !expanded) 10 else Int.MAX_VALUE,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (item.images > 0) {
                    Text(
                        if (item.images == 1) "1 image attached" else "${item.images} images attached",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp).alpha(0.7f),
                    )
                }
                if (long) {
                    Text(
                        if (expanded) "Show less" else "Show more",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 6.dp).clickable { expanded = !expanded },
                    )
                }
                if (pending) Text("Sending…", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun NoticeLine(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = Herdr.colors.faint,
        textAlign = TextAlign.Center,
        maxLines = if (expanded) Int.MAX_VALUE else 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = Space.xxl, vertical = Space.xs),
    )
}

/**
 * A run of tool calls as the web UI's work block: quiet one-line rows on the prose edge (state,
 * verb, object), no card; a hairline under the run. Long runs show only their last few until opened.
 */
@Composable
private fun ToolGroup(tools: List<ChatItem.Tool>, lines: Map<String, ToolLine>) {
    var showAll by rememberSaveable(tools.first().id) { mutableStateOf(false) }
    val hidden = if (showAll || tools.size <= 4) 0 else tools.size - 3
    val line = Herdr.colors.line
    Column(
        Modifier.fillMaxWidth().animateContentSize().drawBehind {
            drawLine(line, androidx.compose.ui.geometry.Offset(0f, size.height - 0.5f), androidx.compose.ui.geometry.Offset(size.width, size.height - 0.5f), 1.dp.toPx())
        }.padding(bottom = Space.xs),
    ) {
        if (hidden > 0) {
            Text(
                "▸ $hidden earlier actions",
                style = MaterialTheme.typography.labelLarge,
                color = Herdr.colors.muted,
                modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(Radius.m)).clickable { showAll = true }
                    .padding(horizontal = Space.xs, vertical = Space.s),
            )
        }
        tools.drop(hidden).forEach { ToolRow(it, lines[it.id]) }
    }
}

@Composable
private fun ToolRow(tool: ChatItem.Tool, line: ToolLine?) {
    var open by rememberSaveable(tool.id) { mutableStateOf(false) }
    // The object worth reading: a path from the repo (`core/…/host/HostState.kt`), or the call's own words.
    val text = line?.text ?: tool.summary.lineSequence().firstOrNull().orEmpty()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.m)).clickable { open = !open }.padding(horizontal = Space.xs, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ToolStateIcon(tool.state)
            Spacer(Modifier.width(Space.s))
            Text(
                displayName(tool.name),
                style = MaterialTheme.typography.labelLarge,
                color = if (tool.state == ToolState.Failed) Herdr.colors.red else Herdr.colors.muted,
                maxLines = 1,
            )
            Spacer(Modifier.width(Space.s))
            Text(
                if (open) tool.summary.lineSequence().firstOrNull().orEmpty() else text,
                style = if (line?.isPath == true || looksLikeCode(text)) CodeStyle.copy(fontSize = 12.sp, lineHeight = 18.sp) else MaterialTheme.typography.bodySmall,
                color = Herdr.colors.text,
                maxLines = if (open) 4 else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            val added = line?.added
            val removed = line?.removed
            if (added != null && removed != null) {
                Spacer(Modifier.width(Space.s))
                AddedRemoved(added, removed)
            }
        }
        if (open) {
            val input = tool.input?.takeIf { it.trim() != tool.summary.trim() }
            if (input != null) {
                Spacer(Modifier.size(6.dp))
                SelectionContainer { CodeBlock(input, maxLines = 30) }
            }
            Spacer(Modifier.size(6.dp))
            SelectionContainer {
                CodeBlock(tool.output ?: if (tool.state == ToolState.Running) "Running…" else "(no output)", maxLines = 40)
            }
        }
    }
}

@Composable
private fun ToolStateIcon(state: ToolState) {
    Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
        when (state) {
            ToolState.Running -> CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.5.dp, color = Herdr.colors.working)
            ToolState.Done -> Icon(HerdrIcons.Check, "Done", Modifier.size(16.dp), tint = Herdr.colors.done)
            ToolState.Failed -> Icon(HerdrIcons.X, "Failed", Modifier.size(16.dp), tint = Herdr.colors.red)
        }
    }
}

private fun displayName(name: String): String = when (name.lowercase()) {
    "bash", "shell", "exec_command", "exec" -> "Run"
    "read", "view" -> "Read"
    "edit", "multiedit", "apply_patch", "str_replace_based_edit_tool" -> "Edit"
    "write" -> "Write"
    "grep", "glob", "find", "rg" -> "Search"
    "webfetch", "fetch" -> "Fetch"
    "websearch", "web search", "web.search" -> "Web"
    "task", "agent" -> "Agent"
    "todowrite", "todo", "update_plan" -> "Plan"
    "askuserquestion" -> "Ask"
    else -> name.substringAfterLast("__").substringAfterLast('.').replaceFirstChar { it.uppercase() }.take(18)
}

/** Paths and shell commands read better in monospace; descriptions don't. */
private fun looksLikeCode(text: String) = text.startsWith("/") || text.startsWith("~") || text.startsWith("./") ||
    text.contains(" && ") || text.contains(" | ") || text.contains('$')

/** "Working…" with a breathing dot, under the newest message. */
@Composable
fun WorkingLine(activity: String?) {
    val pulse = rememberInfiniteTransition(label = "working")
    val alpha by pulse.animateFloat(0.3f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "alpha")
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        StatusDot(Herdr.colors.working, Modifier.alpha(alpha), size = 8)
        Spacer(Modifier.width(Space.s))
        Text(
            activity?.let { "Working · $it" } ?: "Working…",
            style = MaterialTheme.typography.bodySmall,
            color = Herdr.colors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** When a turn started, quiet on the prose edge. */
@Composable
private fun TimeLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = Herdr.colors.faint,
        modifier = Modifier.padding(horizontal = Space.xs).semantics { heading() },
    )
}

/**
 * A turn's work header, the web UI's `▸ Worked for 7s · 1 edit · 2 commands`: a quiet footnote once
 * the turn settled (with a hairline under it while folded), the agent's state colour behind a
 * breathing dot while it runs. The failures are their own item, never cut, as the fold hides their rows.
 */
@Composable
private fun TurnHead(head: ChatRow.Head, onToggle: () -> Unit) {
    val c = Herdr.colors
    val line = c.line
    val folded = !head.open && head.live == null
    Column {
        head.time?.let { TimeLine(it) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
                .then(
                    if (folded) Modifier.drawBehind {
                        drawLine(line, androidx.compose.ui.geometry.Offset(0f, size.height - 0.5f), androidx.compose.ui.geometry.Offset(size.width, size.height - 0.5f), 1.dp.toPx())
                    } else Modifier,
                )
                .heightIn(min = 40.dp)
                .clip(RoundedCornerShape(Radius.m))
                .clickable(onClickLabel = if (head.open) "Fold this turn's work" else "Show this turn's work", onClick = onToggle)
                .padding(horizontal = Space.xs, vertical = Space.s),
        ) {
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                when (head.live) {
                    ChatRow.Live.Working -> {
                        val pulse = rememberInfiniteTransition(label = "working")
                        val alpha by pulse.animateFloat(0.3f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "alpha")
                        StatusDot(c.working, Modifier.alpha(alpha), size = 8)
                    }
                    ChatRow.Live.Blocked -> StatusDot(c.red, size = 8)
                    null -> Icon(if (head.open) HerdrIcons.ChevronDown else HerdrIcons.ChevronRight, null, Modifier.size(16.dp), tint = c.faint)
                }
            }
            Spacer(Modifier.width(Space.s))
            Text(
                head.title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (head.live != null) FontWeight.Medium else FontWeight.Normal,
                color = when (head.live) {
                    ChatRow.Live.Working -> c.working
                    else -> c.muted
                },
                maxLines = 1,
            )
            if (head.summary.isNotEmpty()) {
                Text(
                    " · ${head.summary}",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            if (head.failed > 0) {
                Text(" · ${head.failed} failed", style = MaterialTheme.typography.bodySmall, color = c.red, maxLines = 1, softWrap = false)
            }
        }
    }
}

/**
 * "While you were away · 25 min": what the agent did since the chat was last on screen, in a few
 * lines from its transcript. Docked over the conversation until hidden or until you write.
 */
@Composable
fun AwayCard(away: Away, onHide: () -> Unit, modifier: Modifier = Modifier) {
    val c = Herdr.colors
    com.makefunstuff.herdr.ui.design.QuietCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = Space.l, end = Space.xs)) {
            Icon(HerdrIcons.History, null, Modifier.size(18.dp), tint = c.muted)
            Spacer(Modifier.width(Space.s))
            Text(
                "While you were away · ${away.gone}",
                style = MaterialTheme.typography.titleSmall,
                color = c.strong,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            QuietButton(onClick = onHide) { Text("Hide", color = c.accent) }
        }
        Column(Modifier.padding(start = Space.l, end = Space.l, bottom = Space.m), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
            away.lines.forEach { l ->
                Row {
                    Text("•", style = MaterialTheme.typography.bodyMedium, color = c.faint, modifier = Modifier.width(14.dp))
                    Text(l, style = MaterialTheme.typography.bodyMedium, color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** "↓ 2 new messages": over the composer while the reader is scrolled up and more came in. */
@Composable
fun NewMessagesPill(count: Int, onClick: () -> Unit) {
    val c = Herdr.colors
    val shape = RoundedCornerShape(50)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.heightIn(min = 48.dp).padding(vertical = 6.dp)
            .shadow(8.dp, shape).clip(shape).background(c.card).border(1.dp, c.line, shape)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "Go to the newest", onClick = onClick)
            .padding(horizontal = Space.l, vertical = Space.s),
    ) {
        Icon(HerdrIcons.ArrowDown, null, Modifier.size(16.dp), tint = c.text)
        Spacer(Modifier.width(Space.s))
        Text(if (count == 1) "1 new message" else "$count new messages", style = MaterialTheme.typography.labelLarge, color = c.text)
    }
}

@Composable
fun KeyButton(label: String, onClick: () -> Unit) = KeyChip(label, onClick)
