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

/** What the chat list shows: transcript items, with runs of tool calls folded into one group. */
sealed interface ChatRow {
    val key: String

    data class User(val item: ChatItem.User, val pending: Boolean = false) : ChatRow {
        override val key get() = if (pending) "pending/${item.id}" else item.id
    }
    data class Agent(val item: ChatItem.Agent) : ChatRow { override val key get() = item.id }
    data class Tools(val tools: List<ChatItem.Tool>) : ChatRow { override val key get() = "tools/" + tools.first().id }
    data class Notice(val item: ChatItem.Notice) : ChatRow { override val key get() = item.id }
    data class Screen(val text: String) : ChatRow { override val key get() = "screen" }
    data class Info(override val key: String, val text: String) : ChatRow
}

fun chatRows(items: List<ChatItem>): List<ChatRow> {
    val rows = mutableListOf<ChatRow>()
    val run = mutableListOf<ChatItem.Tool>()
    fun flush() {
        if (run.isNotEmpty()) rows += ChatRow.Tools(run.toList())
        run.clear()
    }
    for (item in items) {
        when (item) {
            is ChatItem.Tool -> run += item
            is ChatItem.User -> { flush(); rows += ChatRow.User(item) }
            is ChatItem.Agent -> { flush(); rows += ChatRow.Agent(item) }
            is ChatItem.Notice -> { flush(); rows += ChatRow.Notice(item) }
        }
    }
    flush()
    return rows
}

@Composable
fun ChatRowView(row: ChatRow) {
    when (row) {
        is ChatRow.User -> UserBubble(row.item, row.pending)
        is ChatRow.Agent -> MarkdownText(row.item.text, Modifier.fillMaxWidth().padding(end = Space.s), style = MaterialTheme.typography.bodyLarge)
        is ChatRow.Tools -> ToolGroup(row.tools)
        is ChatRow.Notice -> NoticeLine(row.item.text)
        is ChatRow.Screen -> SelectionContainer { CodeBlock(row.text) }
        is ChatRow.Info -> NoticeLine(row.text)
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
private fun ToolGroup(tools: List<ChatItem.Tool>) {
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
        tools.drop(hidden).forEach { ToolRow(it) }
    }
}

@Composable
private fun ToolRow(tool: ChatItem.Tool) {
    var open by rememberSaveable(tool.id) { mutableStateOf(false) }
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
                tool.summary.lineSequence().firstOrNull().orEmpty(),
                style = if (looksLikeCode(tool.summary)) CodeStyle.copy(fontSize = 12.sp, lineHeight = 18.sp) else MaterialTheme.typography.bodySmall,
                color = Herdr.colors.text,
                maxLines = if (open) 4 else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
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

/**
 * The question a blocked agent is asking, as the web UI's prompt card: `--bg-elevated` with a
 * strong hairline and `--radius-xl`; its title the card's one red; the question in strong prose;
 * the command it asks about in the one mono box; the choices flat rows with a keycap each, all of
 * the same weight (the agent's current one on the neutral fill).
 */
@Composable
fun BlockedCard(
    prompt: BlockedPrompt?,
    screen: String,
    onKeys: (List<String>) -> Unit,
) {
    val c = Herdr.colors
    var showDetails by remember(prompt?.question) { mutableStateOf(false) }
    Surface(
        color = c.card,
        border = BorderStroke(1.dp, c.lineStrong),
        shape = RoundedCornerShape(Radius.xl),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Text("Needs you", style = MaterialTheme.typography.titleSmall, color = c.red)
            if (prompt == null) {
                // A menu the app couldn't read: show the bottom of the screen and the usual keys.
                val tail = screen.lines().filter { it.isNotBlank() }.takeLast(12).joinToString("\n")
                if (tail.isNotEmpty()) CodeBlock(tail, maxLines = 12)
                Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    listOf("1", "2", "3", "y", "n").forEach { k -> KeyButton(k) { onKeys(listOf(k)) } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    KeyButton("Enter") { onKeys(listOf("enter")) }
                    KeyButton("↑") { onKeys(listOf("up")) }
                    KeyButton("↓") { onKeys(listOf("down")) }
                    KeyButton("Esc") { onKeys(listOf("esc")) }
                }
                return@Column
            }
            Text(prompt.question, style = MaterialTheme.typography.bodyLarge, color = c.strong)
            val details = prompt.details
            if (details.isNotEmpty()) {
                val shown = if (showDetails) details else details.take(3)
                SelectionContainer {
                    Text(
                        shown.joinToString("\n"),
                        style = CodeStyle.copy(fontSize = 12.sp, lineHeight = 18.sp),
                        color = c.text,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.m)).background(c.ground)
                            .border(1.dp, c.line, RoundedCornerShape(Radius.m)).padding(horizontal = Space.m, vertical = Space.s),
                    )
                }
                if (details.size > 3 && !showDetails) {
                    Text(
                        "Show more",
                        style = MaterialTheme.typography.labelLarge,
                        color = c.accent,
                        modifier = Modifier.heightIn(min = 48.dp).clickable { showDetails = true }.padding(vertical = Space.m),
                    )
                }
            }
            Column(Modifier.padding(top = Space.xs), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                prompt.options.forEachIndexed { i, option ->
                    val selected = i == prompt.selected
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(Radius.m))
                            .background(if (selected) c.hover else Color.Transparent)
                            .clickable { onKeys(prompt.keysFor(i)) }
                            .padding(horizontal = Space.s, vertical = Space.s),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // The menu's number as a keycap (`.prompt-card-option`).
                        Box(
                            Modifier.defaultMinSize(minWidth = 22.dp, minHeight = 22.dp).clip(RoundedCornerShape(Radius.s))
                                .border(1.dp, c.lineStrong, RoundedCornerShape(Radius.s)).padding(horizontal = 5.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(option.number?.toString() ?: "${i + 1}", style = CodeStyle.copy(fontSize = 11.sp, lineHeight = 14.sp), color = c.muted) }
                        Spacer(Modifier.width(Space.m))
                        Column(Modifier.weight(1f)) {
                            Text(option.label, style = MaterialTheme.typography.bodyLarge, color = if (selected) c.strong else c.text)
                            option.description?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = c.muted)
                            }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    prompt.hint.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = c.faint,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                QuietButton(onClick = { onKeys(listOf("esc")) }) { Text("Esc") }
            }
        }
    }
}

@Composable
fun KeyButton(label: String, onClick: () -> Unit) = KeyChip(label, onClick)
