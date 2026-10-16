package com.makefunstuff.herdr.ui.machines

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.api.PaneInfo
import com.makefunstuff.herdr.core.api.SessionSnapshot
import com.makefunstuff.herdr.core.api.TabInfo
import com.makefunstuff.herdr.core.api.TabLayout
import com.makefunstuff.herdr.core.api.WorkspaceInfo
import com.makefunstuff.herdr.ui.board.WorkBoardCard
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.detail
import com.makefunstuff.herdr.ui.common.label
import com.makefunstuff.herdr.ui.common.toast
import com.makefunstuff.herdr.ui.design.BarAdd
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.HerdrChip
import com.makefunstuff.herdr.ui.design.HerdrDialog
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrMenu
import com.makefunstuff.herdr.ui.design.HerdrShapes
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.IconTile
import com.makefunstuff.herdr.ui.design.ListRow
import com.makefunstuff.herdr.ui.design.LocalRowBackground
import com.makefunstuff.herdr.ui.design.MenuRow
import com.makefunstuff.herdr.ui.design.MenuSection
import com.makefunstuff.herdr.ui.design.Notice
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.design.SectionHeading
import com.makefunstuff.herdr.ui.manage.NoticeRow
import com.makefunstuff.herdr.ui.manage.NoticesSheet
import com.makefunstuff.herdr.ui.manage.NotifySheet
import com.makefunstuff.herdr.ui.manage.PaneActionsSheet
import com.makefunstuff.herdr.ui.manage.TabActionsSheet
import com.makefunstuff.herdr.ui.manage.WorkspaceActionsSheet
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import com.makefunstuff.herdr.ui.theme.color
import com.makefunstuff.herdr.ui.theme.status
import com.makefunstuff.herdr.ui.worktrees.CreateWorktreeSheet
import com.makefunstuff.herdr.ui.worktrees.RemoveRequest
import com.makefunstuff.herdr.ui.worktrees.RemoveWorktreeFlow
import com.makefunstuff.herdr.ui.worktrees.RepoTarget
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MachineScreen(nav: NavController, machineId: String) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = container.hosts.session(machineId)
    if (session == null) {
        EmptyState("Machine not found", "It may have been removed.")
        return
    }
    val state by session.state.collectAsStateWithLifecycle()
    val snap = state.snapshot
    var menu by remember { mutableStateOf(false) }
    var showNew by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf<List<ImportCandidate>?>(null) }
    var workspaceMenu by remember { mutableStateOf<WorkspaceInfo?>(null) }
    var tabMenu by remember { mutableStateOf<TabInfo?>(null) }
    var paneMenu by remember { mutableStateOf<PaneInfo?>(null) }
    var showNotices by remember { mutableStateOf(false) }
    var showNotify by remember { mutableStateOf(false) }
    val selectedTab = remember { mutableStateMapOf<String, String>() }

    var newWorktreeFor by remember { mutableStateOf<WorkspaceInfo?>(null) }
    var removing by remember { mutableStateOf<RemoveRequest?>(null) }

    fun openPane(pane: PaneInfo) {
        val hasAgent = snap?.agents?.any { it.paneId == pane.paneId } == true
        nav.navigate(if (hasAgent) Routes.agent(machineId, pane.paneId) else Routes.terminal(machineId, pane.paneId))
    }

    Scaffold(
        containerColor = Herdr.colors.ground,
        topBar = {
            HerdrTopBar(
                state.machine.label,
                subtitle = "${state.status.label()} · session ${state.machine.session}",
                onBack = { nav.popBackStack() },
            ) {
                if (state.notices.isNotEmpty()) {
                    BarIcon(HerdrIcons.Notifications, "Notifications", { showNotices = true; session.markNoticesRead() }, badge = state.unreadNotices)
                }
                if (state.online) BarAdd("New") { showNew = true }
                BarIcon(HerdrIcons.MoreVert, "More", { menu = true })
                HerdrMenu(expanded = menu, onDismissRequest = { menu = false }, title = state.machine.label, subtitle = state.machine.target) {
                    MenuRow("Refresh", HerdrIcons.Refresh, { menu = false; session.retry(); session.refresh(); scope.launch { session.checkBoard() } })
                    MenuRow("Worktrees", HerdrIcons.AccountTree, { menu = false; nav.navigate(Routes.worktrees(machineId)) })
                    MenuRow("Notify herdr…", HerdrIcons.NotificationsActive, { menu = false; showNotify = true })
                    MenuSection("herdr on ${state.machine.label}")
                    MenuRow("Plugins", HerdrIcons.Extension, { menu = false; nav.navigate(Routes.admin(machineId, 0)) })
                    MenuRow("Integrations", HerdrIcons.Hub, { menu = false; nav.navigate(Routes.admin(machineId, 1)) })
                    MenuRow("Server and updates", HerdrIcons.CloudDownload, { menu = false; nav.navigate(Routes.admin(machineId, 2)) })
                    MenuRow("herdr's saved machines", HerdrIcons.Lan, { menu = false; nav.navigate(Routes.admin(machineId, 3)) })
                    MenuRow("Import herdr machines from here", HerdrIcons.Download, {
                        menu = false
                        scope.launch {
                            runCatching { MachineImport.discover(session, container.machines.machines.value) }
                                .onSuccess {
                                    if (it.isEmpty()) context.toast("No saved herdr machines on ${state.machine.label}") else importing = it
                                }
                                .onFailure { context.toast(it.message ?: "Couldn't read machines") }
                        }
                    })
                    MenuSection("This app")
                    MenuRow("Edit machine", HerdrIcons.Edit, { menu = false; nav.navigate(Routes.edit(machineId)) })
                }
            }
        },
    ) { padding ->
        if (snap == null) {
            EmptyState(state.status.label(), state.status.detail() ?: "Waiting for ${state.machine.label}…", icon = HerdrIcons.Dns)
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(start = Space.screen, end = Space.screen, top = Space.xs, bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            if (!state.online) {
                item { Notice("Offline · showing the last known state", color = Herdr.colors.amber, icon = HerdrIcons.Schedule) }
            }
            if (state.hasBoard) {
                item(key = "board") {
                    WorkBoardCard(
                        state.machine.label, showMachine = false,
                        onBoard = { nav.navigate(Routes.board(machineId)) },
                        onCoordinator = { nav.navigate(Routes.coordinator(machineId)) },
                    )
                }
            }
            item(key = "ws-heading") { SectionHeading("Workspaces", top = if (state.hasBoard) Space.l else Space.xs) }
            if (snap.workspaces.isEmpty()) {
                item { Text("No workspaces yet. Tap + to create one.", color = Herdr.colors.muted, style = MaterialTheme.typography.bodyMedium) }
            }
            items(snap.workspaces, key = { it.workspaceId }) { ws ->
                WorkspaceCard(
                    ws,
                    snap,
                    meta = state.workspaceMeta[ws.workspaceId].orEmpty(),
                    selectedTabId = selectedTab[ws.workspaceId] ?: ws.activeTabId,
                    onSelectTab = { selectedTab[ws.workspaceId] = it },
                    onOpenPane = ::openPane,
                    onTerminal = { nav.navigate(Routes.terminal(machineId, it.paneId)) },
                    online = state.online,
                    onWorktrees = { nav.navigate(Routes.worktrees(machineId, ws.workspaceId)) },
                    onNewWorktree = { newWorktreeFor = ws },
                    onRemoveWorktree = {
                        val wt = ws.worktree ?: return@WorkspaceCard
                        removing = RemoveRequest(ws.label.ifBlank { wt.checkoutPath.substringAfterLast('/') }, wt.checkoutPath, ws.workspaceId, RepoTarget(ws.workspaceId, null))
                    },
                    onWorkspaceMenu = { workspaceMenu = ws },
                    onTabMenu = { tabMenu = it },
                    onPaneMenu = { paneMenu = it },
                )
            }
        }
    }

    if (showNew && snap != null) {
        NewSheet(session, snap, onDismiss = { showNew = false })
    }

    newWorktreeFor?.let { ws ->
        CreateWorktreeSheet(
            session,
            ws.worktree?.repoName?.ifBlank { null } ?: ws.label,
            RepoTarget(ws.workspaceId, null),
            branches = emptyList(),
            onDismiss = { newWorktreeFor = null },
            onNeedsTrust = { newWorktreeFor = null; nav.navigate(Routes.worktrees(machineId, ws.workspaceId)) },
        )
    }

    removing?.let { req -> RemoveWorktreeFlow(session, req) { removing = null } }

    importing?.let { candidates ->
        ImportDialog(
            source = state.machine.label,
            candidates = candidates,
            onDismiss = { importing = null },
            onImport = { chosen, via ->
                chosen.forEach { container.machines.upsert(it.toMachine(if (via) machineId else null)) }
                importing = null
                context.toast("Imported ${chosen.size} machine${if (chosen.size == 1) "" else "s"}")
            },
        )
    }

    snap?.let { s ->
        workspaceMenu?.let { ws ->
            WorkspaceActionsSheet(session, ws, s, state.workspaceMeta[ws.workspaceId].orEmpty()) { workspaceMenu = null }
        }
        tabMenu?.let { tab -> TabActionsSheet(session, tab, s) { tabMenu = null } }
        paneMenu?.let { pane -> PaneActionsSheet(session, pane, s) { paneMenu = null } }
    }
    if (showNotices) {
        val rows = state.notices.map { NoticeRow(machineId, state.machine.label, it) }
        NoticesSheet(
            rows = rows,
            showMachine = false,
            onOpenAgent = { _, pane -> showNotices = false; nav.navigate(Routes.agent(machineId, pane)) },
            onClear = { session.clearNotices() },
            onDismiss = { showNotices = false },
        )
    }
    if (showNotify) {
        NotifySheet(session, state.machine.label) { showNotify = false }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WorkspaceCard(
    ws: WorkspaceInfo,
    snap: SessionSnapshot,
    meta: Map<String, String>,
    selectedTabId: String,
    onSelectTab: (String) -> Unit,
    onOpenPane: (PaneInfo) -> Unit,
    onTerminal: (PaneInfo) -> Unit,
    online: Boolean,
    onWorktrees: () -> Unit,
    onNewWorktree: () -> Unit,
    onRemoveWorktree: () -> Unit,
    onWorkspaceMenu: () -> Unit,
    onTabMenu: (TabInfo) -> Unit,
    onPaneMenu: (PaneInfo) -> Unit,
) {
    val c = Herdr.colors
    var menu by remember { mutableStateOf(false) }
    val repo = ws.worktree
    val tabs = snap.tabs.filter { it.workspaceId == ws.workspaceId }
    val tabId = tabs.firstOrNull { it.tabId == selectedTabId }?.tabId ?: tabs.firstOrNull()?.tabId
    val panes = snap.panes.filter { it.tabId == tabId }
    val layout = tabId?.let { snap.layout(it) }
    val wsStatus = ws.agentStatus
    QuietCard {
        CompositionLocalProvider(LocalRowBackground provides c.card) {
            // Long-press the header for the workspace's Git actions.
            ListRow(
                title = ws.label.ifBlank { ws.workspaceId },
                subtitle = buildList {
                    add("${ws.tabCount} tab${if (ws.tabCount == 1) "" else "s"}")
                    if (repo?.isLinkedWorktree == true) add("worktree")
                    val panes = snap.panes.count { p -> tabs.any { it.tabId == p.tabId } }
                    if (panes > 1) add("$panes panes")
                }.joinToString(" · "),
                leading = { IconTile(HerdrIcons.Folder, dot = if (wsStatus == AgentStatus.Unknown) null else wsStatus.color()) },
                trailing = { RowIcon(HerdrIcons.MoreVert, "Workspace actions", onWorkspaceMenu) },
                onLongClick = { menu = true },
                onClick = {},
                modifier = Modifier.padding(top = Space.xs),
            )
        }
        HerdrMenu(expanded = menu, onDismissRequest = { menu = false }, title = ws.label.ifBlank { ws.workspaceId }, subtitle = "Git") {
            MenuRow(
                if (repo != null) "Worktrees of ${repo.repoName.ifBlank { "this repository" }}" else "Worktrees in this folder",
                HerdrIcons.AccountTree, { menu = false; onWorktrees() },
            )
            MenuRow("New worktree…", HerdrIcons.Add, { menu = false; onNewWorktree() }, enabled = online)
            if (repo?.isLinkedWorktree == true) {
                MenuRow("Remove this worktree…", HerdrIcons.Delete, { menu = false; onRemoveWorktree() }, enabled = online, destructive = true)
            }
        }
        if (meta.isNotEmpty()) {
            Text(
                meta.entries.joinToString(" · ") { "${it.key}: ${it.value}" },
                style = MaterialTheme.typography.bodySmall,
                color = c.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 68.dp, end = Space.l, bottom = Space.xs),
            )
        }
        // The tab bar: chips when there are several, the one tab's name when not, and its actions.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = Space.l, end = Space.xs),
        ) {
            if (tabs.size > 1) {
                LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    items(tabs, key = { it.tabId }) { t ->
                        HerdrChip(t.label.ifBlank { t.tabId }, selected = t.tabId == tabId, onClick = { onSelectTab(t.tabId) })
                    }
                }
            } else {
                Text(
                    tabs.firstOrNull()?.let { t -> t.label.ifBlank { "${t.number}" }.let { l -> if (l.all { it.isDigit() }) "Tab $l" else l } } ?: "",
                    style = MaterialTheme.typography.titleSmall,
                    color = c.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            if (layout != null && layout.panes.size > 1) {
                LayoutMap(layout, snap, Modifier.padding(start = Space.s).size(width = 36.dp, height = 24.dp))
            }
            tabs.firstOrNull { it.tabId == tabId }?.let { active ->
                RowIcon(HerdrIcons.MoreHoriz, "Tab actions", { onTabMenu(active) })
            }
        }
        Column(Modifier.padding(start = Space.s, end = Space.s, bottom = Space.s)) {
            panes.forEach { pane ->
                val agent = snap.agents.firstOrNull { it.paneId == pane.paneId }
                val status = agent?.agentStatus ?: pane.agentStatus
                val where = (pane.foregroundCwd ?: pane.cwd)?.replace(Regex("^/(home|Users)/[^/]+"), "~")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clip(HerdrShapes.Tile)
                        .combinedClickable(onClick = { onOpenPane(pane) }, onLongClick = { onPaneMenu(pane) })
                        .heightIn(min = 56.dp).padding(start = Space.s, top = Space.xs, bottom = Space.xs),
                ) {
                    IconTile(
                        if (agent != null) HerdrIcons.SmartToy else HerdrIcons.Terminal,
                        size = 36.dp,
                        dot = if (agent != null) status.color() else null,
                        background = c.field,
                    )
                    Spacer(Modifier.width(Space.m))
                    Column(Modifier.weight(1f)) {
                        Text(
                            agent?.displayName ?: pane.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (agent != null) agent.statusLabel.replaceFirstChar { it.uppercase() } else where ?: "Shell",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (agent != null && status != AgentStatus.Unknown) status.color() else c.muted,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    RowIcon(HerdrIcons.Terminal, "Terminal", { onTerminal(pane) })
                }
            }
        }
    }
}

/** A 48dp icon button inside a row or card, quieter than a bar icon. */
@Composable
private fun RowIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Herdr.colors.muted, modifier = Modifier.size(22.dp)) }
}

/** A thumbnail of the tab's split layout, from herdr's pane rects. */
@Composable
private fun LayoutMap(layout: TabLayout, snap: SessionSnapshot, modifier: Modifier) {
    val c = Herdr.colors
    val colors = layout.panes.associate { p -> p.paneId to snap.agents.firstOrNull { it.paneId == p.paneId }?.agentStatus?.let { c.status(it) } }
    Canvas(modifier.semantics { contentDescription = "Layout: ${layout.panes.size} panes" }) {
        val area = layout.area
        if (area.width <= 0 || area.height <= 0) return@Canvas
        val sx = size.width / area.width
        val sy = size.height / area.height
        layout.panes.forEach { p ->
            val color = colors[p.paneId]
            val topLeft = Offset((p.rect.x - area.x) * sx + 1f, (p.rect.y - area.y) * sy + 1f)
            val sz = Size(p.rect.width * sx - 2f, p.rect.height * sy - 2f)
            drawRoundRect(color?.copy(alpha = 0.35f) ?: c.fieldFocused, topLeft, sz, CornerRadius(3.dp.toPx()))
        }
    }
}

@Composable
private fun ImportDialog(
    source: String,
    candidates: List<ImportCandidate>,
    onDismiss: () -> Unit,
    onImport: (List<ImportCandidate>, Boolean) -> Unit,
) {
    val chosen = remember { mutableStateMapOf<String, Boolean>().apply { candidates.forEach { put(it.id, !it.alreadyAdded) } } }
    var via by remember { mutableStateOf(true) }
    HerdrDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import from $source") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                candidates.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = chosen[c.id] == true, onCheckedChange = { chosen[c.id] = it }, enabled = !c.alreadyAdded)
                        Column {
                            Text(c.label)
                            Text(
                                if (c.alreadyAdded) "Already added" else "${c.user}@${c.host}:${c.port} · ${c.session}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Herdr.colors.muted,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = via, onCheckedChange = { via = it })
                    Text("Connect through $source", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "Leave this on if these machines are only reachable from $source, such as on its home network.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            QuietButton(onClick = { onImport(candidates.filter { chosen[it.id] == true && !it.alreadyAdded }, via) }) { Text("Import") }
        },
        dismissButton = { QuietButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
