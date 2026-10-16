package com.makefunstuff.herdr.ui.agents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.chat.BlockedPrompt
import com.makefunstuff.herdr.data.AgentRow
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.Pill
import com.makefunstuff.herdr.ui.common.StatusDot
import com.makefunstuff.herdr.ui.coordinator.CoordinatorCard
import com.makefunstuff.herdr.ui.manage.AgentActionsSheet
import com.makefunstuff.herdr.ui.manage.NoticeRow
import com.makefunstuff.herdr.ui.manage.NoticesSheet
import com.makefunstuff.herdr.ui.theme.color
import com.makefunstuff.herdr.ui.theme.title

private val ORDER = listOf(AgentStatus.Blocked, AgentStatus.Done, AgentStatus.Working, AgentStatus.Idle, AgentStatus.Unknown)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsScreen(nav: NavController) {
    val container = LocalContainer.current
    val agents by container.hosts.agents.collectAsStateWithLifecycle()
    val hosts by container.hosts.hosts.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf<String?>(null) }
    var menuAgent by remember { mutableStateOf<AgentRow?>(null) }
    var showNotices by remember { mutableStateOf(false) }
    val boards = hosts.filter { it.hasBoard }
    val unread = hosts.sumOf { it.unreadNotices }
    val noticeRows = remember(hosts) {
        hosts.flatMap { h -> h.notices.map { NoticeRow(h.machine.id, h.machine.label, it) } }.sortedByDescending { it.notice.at }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Agents") },
                actions = {
                    IconButton(
                        onClick = {
                            hosts.forEach { container.hosts.session(it.machine.id)?.markNoticesRead() }
                            showNotices = true
                        },
                    ) {
                        BadgedBox(badge = { if (unread > 0) Badge { Text(if (unread > 99) "99+" else "$unread") } }) {
                            Icon(Icons.Filled.Notifications, "Notifications")
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            hosts.isEmpty() -> EmptyState(
                "No machines yet",
                "Add a machine that runs herdr. Its agents show up here, with the ones waiting on you first.",
            ) { Button(onClick = { nav.navigate(Routes.edit()) }) { Text("Add machine") } }

            agents.isEmpty() && boards.isEmpty() -> EmptyState(
                if (hosts.any { it.online }) "No agents running" else "Not connected yet",
                if (hosts.any { it.online }) {
                    "Start an agent in herdr, or open a machine to start one from here."
                } else {
                    "Check the Machines tab for connection details."
                },
            )

            else -> {
                val shown = agents.filter { filter == null || it.machine.id == filter }
                val grouped = shown.groupBy { it.agent.agentStatus }
                LazyColumn(
                    modifier = Modifier.padding(padding),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (hosts.size > 1) {
                        item {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item { FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All machines") }) }
                                items(hosts, key = { it.machine.id }) { h ->
                                    FilterChip(
                                        selected = filter == h.machine.id,
                                        onClick = { filter = h.machine.id },
                                        label = { Text(h.machine.label) },
                                    )
                                }
                            }
                        }
                    }
                    // The Coordinator of each machine with the work board, above its agents.
                    items(boards.filter { filter == null || it.machine.id == filter }, key = { "coordinator/${it.machine.id}" }) { h ->
                        CoordinatorCard(h.machine.label, showMachine = hosts.size > 1) { nav.navigate(Routes.coordinator(h.machine.id)) }
                    }
                    for (status in ORDER) {
                        val rows = grouped[status].orEmpty().sortedByDescending { it.agent.stateChangeSeq }
                        if (rows.isEmpty()) continue
                        item(key = "h-$status") {
                            Text(
                                "${status.title()} · ${rows.size}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                            )
                        }
                        items(rows, key = { "${it.machine.id}/${it.agent.paneId}" }) { row ->
                            AgentCard(
                                row,
                                showMachine = hosts.size > 1,
                                onOpen = { nav.navigate(Routes.agent(row.machine.id, row.agent.paneId)) },
                                onMenu = { menuAgent = row },
                            )
                        }
                    }
                }
            }
        }
    }

    menuAgent?.let { row ->
        container.hosts.session(row.machine.id)?.let { session ->
            AgentActionsSheet(
                session = session,
                agent = row.agent,
                paneLabel = row.agent.paneId,
                onDismiss = { menuAgent = null },
                onTerminal = { menuAgent = null; nav.navigate(Routes.terminal(row.machine.id, row.agent.paneId)) },
            )
        }
    }
    if (showNotices) {
        NoticesSheet(
            rows = noticeRows,
            showMachine = hosts.size > 1,
            onOpenAgent = { mid, pane -> showNotices = false; nav.navigate(Routes.agent(mid, pane)) },
            onClear = { hosts.forEach { container.hosts.session(it.machine.id)?.clearNotices() } },
            onDismiss = { showNotices = false },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AgentCard(row: AgentRow, showMachine: Boolean, onOpen: () -> Unit, onMenu: () -> Unit) {
    val agent = row.agent
    val status = agent.agentStatus
    val preview = rememberLastLine(row)
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = onMenu),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(status.color())
                Spacer(Modifier.width(10.dp))
                Text(
                    agent.displayName + (agent.subtitle?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (status != AgentStatus.Blocked && status != AgentStatus.Unknown) {
                    Pill(agent.statusLabel)
                }
            }
            Text(
                (if (showMachine) "${row.machine.label} › " else "") + row.location,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (status == AgentStatus.Blocked && preview != null) {
                Text(
                    preview,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = status.color(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** For blocked agents, the most recent non-empty screen lines, so you can see what it's asking. */
@Composable
private fun rememberLastLine(row: AgentRow): String? {
    if (row.agent.agentStatus != AgentStatus.Blocked) return null
    val container = LocalContainer.current
    val cache = remember { mutableStateMapOf<String, String>() }
    val key = "${row.machine.id}/${row.agent.paneId}/${row.agent.stateChangeSeq}"
    LaunchedEffect(key) {
        val session = container.hosts.session(row.machine.id) ?: return@LaunchedEffect
        runCatching {
            val text = session.awaitApi(5_000).readPane(row.agent.paneId, source = "visible", lines = 40).text
            cache[key] = BlockedPrompt.parse(text)?.question ?: PromptGuess.question(text) ?: return@runCatching
        }
    }
    return cache[key]
}

/** Picks the line most likely to be the agent's question from the bottom of its screen. */
object PromptGuess {
    private val noise = Regex("^[\\s│╭╮╰╯─┃━┏┓┗┛>❯›·•|_=+-]*$")

    fun question(screen: String): String? {
        val lines = screen.lines()
            .map { it.trim().trim('│', '┃').trim() }
            .filter { it.isNotEmpty() && !noise.matches(it) }
        if (lines.isEmpty()) return null
        return lines.lastOrNull { it.endsWith("?") } ?: lines.last()
    }
}
