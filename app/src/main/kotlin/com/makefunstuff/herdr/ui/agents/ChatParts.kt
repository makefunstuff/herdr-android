package com.makefunstuff.herdr.ui.agents

import com.makefunstuff.herdr.ui.common.CodeBlock
import com.makefunstuff.herdr.ui.common.MarkdownText
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.chat.BlockedPrompt
import com.makefunstuff.herdr.core.chat.ChatItem
import com.makefunstuff.herdr.core.chat.ToolState
import com.makefunstuff.herdr.ui.common.StatusDot
import com.makefunstuff.herdr.ui.theme.color

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
        is ChatRow.Agent -> MarkdownText(row.item.text, Modifier.fillMaxWidth().padding(end = 8.dp))
        is ChatRow.Tools -> ToolGroup(row.tools)
        is ChatRow.Notice -> NoticeLine(row.item.text)
        is ChatRow.Screen -> SelectionContainer { CodeBlock(row.text) }
        is ChatRow.Info -> NoticeLine(row.text)
    }
}

@Composable
private fun UserBubble(item: ChatItem.User, pending: Boolean) {
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    val long = item.text.length > 600 || item.text.count { it == '\n' } > 12
    Row(Modifier.fillMaxWidth().padding(start = 40.dp), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
            modifier = Modifier.alpha(if (pending) 0.6f else 1f).animateContentSize(),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                SelectionContainer {
                    Text(
                        item.text,
                        style = MaterialTheme.typography.bodyMedium,
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
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 280.dp).padding(horizontal = 10.dp).clickable { expanded = !expanded },
        )
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** A run of tool calls as one compact card; long runs show only their last few until opened. */
@Composable
private fun ToolGroup(tools: List<ChatItem.Tool>) {
    var showAll by rememberSaveable(tools.first().id) { mutableStateOf(false) }
    val hidden = if (showAll || tools.size <= 4) 0 else tools.size - 3
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            if (hidden > 0) {
                Text(
                    "$hidden earlier actions",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().clickable { showAll = true }.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            tools.drop(hidden).forEach { ToolRow(it) }
        }
    }
}

@Composable
private fun ToolRow(tool: ChatItem.Tool) {
    var open by rememberSaveable(tool.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 12.dp, vertical = 7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ToolStateIcon(tool.state)
            Spacer(Modifier.width(8.dp))
            Text(
                displayName(tool.name),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                tool.summary.lineSequence().firstOrNull().orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = if (looksLikeCode(tool.summary)) FontFamily.Monospace else null,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
        when (state) {
            ToolState.Running -> CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = AgentStatus.Working.color())
            ToolState.Done -> Icon(Icons.Filled.Check, "Done", Modifier.size(14.dp), tint = AgentStatus.Idle.color())
            ToolState.Failed -> Icon(Icons.Filled.Close, "Failed", Modifier.size(14.dp), tint = AgentStatus.Blocked.color())
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
        StatusDot(AgentStatus.Working.color(), Modifier.alpha(alpha), size = 8)
        Spacer(Modifier.width(8.dp))
        Text(
            activity?.let { "Working · $it" } ?: "Working…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The question a blocked agent is asking, with its choices as buttons. */
@Composable
fun BlockedCard(
    prompt: BlockedPrompt?,
    screen: String,
    onKeys: (List<String>) -> Unit,
) {
    val accent = AgentStatus.Blocked.color()
    var showDetails by remember(prompt?.question) { mutableStateOf(false) }
    Surface(
        color = accent.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Needs you", style = MaterialTheme.typography.labelMedium, color = accent, fontWeight = FontWeight.SemiBold)
            if (prompt == null) {
                // A menu the app couldn't read: show the bottom of the screen and the usual keys.
                val tail = screen.lines().filter { it.isNotBlank() }.takeLast(12).joinToString("\n")
                if (tail.isNotEmpty()) CodeBlock(tail, maxLines = 12)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("1", "2", "3", "y", "n").forEach { k -> KeyButton(k) { onKeys(listOf(k)) } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    KeyButton("Enter") { onKeys(listOf("enter")) }
                    KeyButton("↑") { onKeys(listOf("up")) }
                    KeyButton("↓") { onKeys(listOf("down")) }
                    KeyButton("Esc") { onKeys(listOf("esc")) }
                }
                return@Column
            }
            Text(prompt.question, style = MaterialTheme.typography.titleSmall)
            val details = prompt.details
            if (details.isNotEmpty()) {
                val shown = if (showDetails) details else details.take(3)
                SelectionContainer {
                    Text(
                        shown.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (details.size > 3 && !showDetails) {
                    Text(
                        "Show more",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { showDetails = true },
                    )
                }
            }
            prompt.options.forEachIndexed { i, option ->
                OutlinedButton(
                    onClick = { onKeys(prompt.keysFor(i)) },
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, if (i == prompt.selected) accent else MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            (option.number?.let { "$it. " } ?: "") + option.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        option.description?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    prompt.hint.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = { onKeys(listOf("esc")) }) { Text("Esc") }
            }
        }
    }
}

@Composable
fun KeyButton(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}
