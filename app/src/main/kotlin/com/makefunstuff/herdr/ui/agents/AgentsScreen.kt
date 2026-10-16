package com.makefunstuff.herdr.ui.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.chat.BlockedPrompt
import com.makefunstuff.herdr.data.AgentRow
import com.makefunstuff.herdr.ui.board.WorkBoardCard
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.HerdrChip
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.IconTile
import com.makefunstuff.herdr.ui.design.ListRow
import com.makefunstuff.herdr.ui.design.PrimaryButton
import com.makefunstuff.herdr.ui.design.SectionHeading
import com.makefunstuff.herdr.ui.design.StateBlock
import com.makefunstuff.herdr.ui.manage.AgentActionsSheet
import com.makefunstuff.herdr.ui.manage.NoticeRow
import com.makefunstuff.herdr.ui.manage.NoticesSheet
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
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
        containerColor = Herdr.colors.ground,
        topBar = {
            HerdrTopBar("Agents") {
                BarIcon(
                    HerdrIcons.Notifications, "Notifications",
                    {
                        hosts.forEach { container.hosts.session(it.machine.id)?.markNoticesRead() }
                        showNotices = true
                    },
                    badge = unread,
                )
            }
        },
    ) { padding ->
        when {
            hosts.isEmpty() -> EmptyState(
                "No machines yet",
                "Add a machine that runs herdr. Its agents show up here, with the ones waiting on you first.",
                icon = HerdrIcons.SmartToy,
            ) { PrimaryButton("Add machine") { nav.navigate(Routes.edit()) } }

            agents.isEmpty() && boards.isEmpty() -> EmptyState(
                if (hosts.any { it.online }) "No agents running" else "Not connected yet",
                if (hosts.any { it.online }) {
                    "Start an agent in herdr, or open a machine to start one from here."
                } else {
                    "Check the Machines tab for connection details."
                },
                icon = if (hosts.any { it.online }) HerdrIcons.SmartToy else HerdrIcons.Dns,
            )

            else -> {
                val shown = agents.filter { filter == null || it.machine.id == filter }
                val grouped = shown.groupBy { it.agent.agentStatus }
                val shownBoards = boards.filter { filter == null || it.machine.id == filter }
                LazyColumn(
                    modifier = Modifier.padding(padding),
                    contentPadding = PaddingValues(bottom = Space.xl),
                ) {
                    if (hosts.size > 1) {
                        item(key = "filters") {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = Space.screen),
                                horizontalArrangement = Arrangement.spacedBy(Space.s),
                            ) {
                                item { HerdrChip("All", selected = filter == null, onClick = { filter = null }) }
                                items(hosts, key = { it.machine.id }) { h ->
                                    HerdrChip(h.machine.label, selected = filter == h.machine.id, onClick = { filter = h.machine.id })
                                }
                            }
                        }
                    }
                    // The work board of each machine that has one, above its agents.
                    items(shownBoards, key = { "board/${it.machine.id}" }) { h ->
                        WorkBoardCard(
                            h.machine.label, showMachine = hosts.size > 1,
                            onBoard = { nav.navigate(Routes.board(h.machine.id)) },
                            onCoordinator = { nav.navigate(Routes.coordinator(h.machine.id)) },
                            modifier = Modifier.padding(horizontal = Space.screen, vertical = Space.xs),
                        )
                    }
                    if (shown.isEmpty()) {
                        item(key = "none") {
                            StateBlock(HerdrIcons.SmartToy, "No agents here", "Nothing is running on this machine right now.", Modifier.padding(top = Space.xl))
                        }
                    }
                    for (status in ORDER) {
                        val rows = grouped[status].orEmpty().sortedByDescending { it.agent.stateChangeSeq }
                        if (rows.isEmpty()) continue
                        item(key = "h-$status") {
                            SectionHeading(
                                "${status.title()} · ${rows.size}",
                                Modifier.padding(horizontal = Space.screen),
                                top = Space.l,
                            )
                        }
                        items(rows, key = { "${it.machine.id}/${it.agent.paneId}" }) { row ->
                            AgentRowItem(
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

/**
 * One agent: a tile with its kind's letter and a status dot, what it works on as the title, which
 * agent and where as the second line, and for one that waits on you, its question.
 */
@Composable
private fun AgentRowItem(row: AgentRow, showMachine: Boolean, onOpen: () -> Unit, onMenu: () -> Unit) {
    val agent = row.agent
    val status = agent.agentStatus
    val preview = rememberLastLine(row)
    val c = Herdr.colors
    val task = agent.subtitle
    ListRow(
        title = task ?: agent.displayName,
        subtitle = buildList {
            if (task != null) add(agent.displayName)
            if (showMachine) add(row.machine.label)
            add(row.location)
        }.joinToString(" · "),
        below = if (status == AgentStatus.Blocked && preview != null) {
            {
                Text(
                    preview, style = MaterialTheme.typography.bodyMedium, color = c.red, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = Space.xxs),
                )
            }
        } else null,
        leading = {
            IconTile(
                letter = agent.displayName.take(1).uppercase(),
                dot = status.color(),
            )
        },
        meta = if (status == AgentStatus.Working || status == AgentStatus.Done) agent.statusLabel else null,
        onClickLabel = "Open chat",
        onLongClick = onMenu,
        onClick = onOpen,
        modifier = Modifier.padding(horizontal = Space.xs),
    )
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
