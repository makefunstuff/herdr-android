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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.makefunstuff.herdr.LocalPanes
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.ui.worktrees.workspaceFolder
import com.makefunstuff.herdr.ui.code.CodeSpot
import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.api.PaneInfo
import com.makefunstuff.herdr.core.api.SessionSnapshot
import com.makefunstuff.herdr.core.api.TabInfo
import com.makefunstuff.herdr.core.api.TabLayout
import com.makefunstuff.herdr.core.api.WorkspaceInfo
import com.makefunstuff.herdr.ui.board.BoardCard
import com.makefunstuff.herdr.ui.board.CoordinatorCard
import com.makefunstuff.herdr.ui.common.Pill
import com.makefunstuff.herdr.ui.common.StatusDot
import com.makefunstuff.herdr.ui.design.HerdrFab
import com.makefunstuff.herdr.ui.design.RowDivider
import com.makefunstuff.herdr.ui.design.SecondaryButton
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.detail
import com.makefunstuff.herdr.ui.common.label
import com.makefunstuff.herdr.ui.common.toast
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.HerdrChip
import com.makefunstuff.herdr.ui.design.HerdrDialog
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.AgentMark
import com.makefunstuff.herdr.ui.design.HerdrMenu
import com.makefunstuff.herdr.ui.design.HerdrShapes
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.MenuRow
import com.makefunstuff.herdr.ui.design.Notice
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.health.HealthCard
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
    val panes = LocalPanes.current
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
                    BarIcon(HerdrIcons.Bell, "Notifications", { showNotices = true; session.markNoticesRead() }, badge = state.unreadNotices)
                }
                BarIcon(HerdrIcons.RefreshCw, "Refresh", { session.retry(); session.refresh(); scope.launch { session.checkBoard() } })
                BarIcon(HerdrIcons.Ellipsis, "More", { menu = true })
                HerdrMenu(expanded = menu, onDismissRequest = { menu = false }, title = state.machine.label) {
                    MenuRow("Notify herdr…", HerdrIcons.BellRing, { menu = false; showNotify = true })
                    MenuRow("Plugins", HerdrIcons.Puzzle, { menu = false; nav.navigate(Routes.admin(machineId, 0)) })
                    MenuRow("Integrations", HerdrIcons.Plug, { menu = false; nav.navigate(Routes.admin(machineId, 1)) })
                    MenuRow("Server and updates", HerdrIcons.CloudDownload, { menu = false; nav.navigate(Routes.admin(machineId, 2)) })
                    MenuRow("herdr's saved machines", HerdrIcons.Network, { menu = false; nav.navigate(Routes.admin(machineId, 3)) })
                    RowDivider(inset = Space.l)
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
                    MenuRow("Code", HerdrIcons.Code, { menu = false; nav.navigate(Routes.codeRepos(machineId)) })
                    MenuRow("Worktrees", HerdrIcons.FolderGit2, { menu = false; nav.navigate(Routes.worktrees(machineId)) })
                    MenuRow("Health and cleanup", HerdrIcons.Activity, { menu = false; nav.navigate(Routes.health(machineId)) })
                    MenuRow("Edit machine", HerdrIcons.Pencil, { menu = false; nav.navigate(Routes.edit(machineId)) })
                }
            }
        },
        floatingActionButton = { if (state.online) HerdrFab("New") { showNew = true } },
    ) { padding ->
        if (snap == null) {
            EmptyState(state.status.label(), state.status.detail() ?: "Waiting for ${state.machine.label}…", icon = HerdrIcons.Monitor)
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(start = Space.screen, end = Space.screen, top = Space.xs, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            if (!state.online) {
                item { Notice("Offline · showing the last known state", color = Herdr.colors.notice, icon = HerdrIcons.Clock) }
            }
            if (state.hasBoard) {
                item(key = "board") { BoardCard(state.machine.label, showMachine = false) { panes.openBoard(nav, machineId) } }
                item(key = "coordinator") { CoordinatorCard(state.machine.label, showMachine = false) { nav.navigate(Routes.coordinator(machineId)) } }
            }
            if (state.online) item(key = "health") { HealthCard(session) { nav.navigate(Routes.health(machineId)) } }
            if (snap.workspaces.isEmpty()) {
                item { Text("No workspaces yet. Tap New to create one.", color = Herdr.colors.muted, style = MaterialTheme.typography.bodyMedium) }
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
            val folder = ws.worktree?.checkoutPath?.ifBlank { null } ?: s.workspaceFolder(ws.workspaceId)
            WorkspaceActionsSheet(
                session, ws, s, state.workspaceMeta[ws.workspaceId].orEmpty(),
                onCode = folder?.let { f -> { nav.navigate(Routes.codeAt(CodeSpot(machineId, f))) } },
            ) { workspaceMenu = null }
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
    QuietCard(padded = true) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            // Long-press the header for the workspace's Git actions.
            modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { menu = true }),
        ) {
            StatusDot(if (ws.agentStatus == AgentStatus.Unknown) c.off else ws.agentStatus.color())
            Spacer(Modifier.width(Space.m))
            Text(ws.label.ifBlank { ws.workspaceId }, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            repo?.takeIf { it.isLinkedWorktree }?.let { Pill("worktree"); Spacer(Modifier.width(Space.xs)) }
            Pill("${ws.tabCount} tab${if (ws.tabCount == 1) "" else "s"}")
            RowIcon(HerdrIcons.Ellipsis, "Workspace actions", onWorkspaceMenu)
        }
        HerdrMenu(expanded = menu, onDismissRequest = { menu = false }, title = ws.label.ifBlank { ws.workspaceId }) {
            MenuRow(
                if (repo != null) "Worktrees of ${repo.repoName.ifBlank { "this repository" }}" else "Worktrees in this folder",
                HerdrIcons.FolderGit2, { menu = false; onWorktrees() },
            )
            MenuRow("New worktree…", HerdrIcons.Plus, { menu = false; onNewWorktree() }, enabled = online)
            if (repo?.isLinkedWorktree == true) {
                MenuRow("Remove this worktree…", HerdrIcons.Trash2, { menu = false; onRemoveWorktree() }, enabled = online, destructive = true)
            }
        }
        if (meta.isNotEmpty()) {
            Text(
                meta.entries.joinToString(" · ") { "${it.key}: ${it.value}" },
                style = MaterialTheme.typography.bodySmall,
                color = c.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 20.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (tabs.size > 1) {
                LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    items(tabs, key = { it.tabId }) { t ->
                        HerdrChip(t.label.ifBlank { t.tabId }, selected = t.tabId == tabId, onClick = { onSelectTab(t.tabId) })
                    }
                }
            } else {
                // One tab: name it, so the row with its menu isn't an empty gap.
                Text(
                    tabs.firstOrNull()?.let { t -> t.label.ifBlank { "${t.number}" }.let { l -> if (l.all { it.isDigit() }) "Tab $l" else l } } ?: "",
                    style = MaterialTheme.typography.titleSmall,
                    color = c.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 20.dp),
                )
            }
            tabs.firstOrNull { it.tabId == tabId }?.let { active ->
                RowIcon(HerdrIcons.Ellipsis, "Tab actions", { onTabMenu(active) })
            }
        }
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            if (layout != null && layout.panes.size > 1) {
                LayoutMap(layout, snap, Modifier.padding(top = Space.xs).size(width = 72.dp, height = 48.dp))
            }
            Column(Modifier.weight(1f)) {
                panes.forEach { pane ->
                    val agent = snap.agents.firstOrNull { it.paneId == pane.paneId }
                    val status = agent?.agentStatus ?: pane.agentStatus
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clip(HerdrShapes.Tile)
                            .combinedClickable(onClick = { onOpenPane(pane) }, onLongClick = { onPaneMenu(pane) })
                            .heightIn(min = 52.dp),
                    ) {
                        // The leading cell says who the pane is: the agent's brand mark, else the shell's glyph.
                        if (agent != null) AgentMark(agent.agent ?: agent.displayName, size = 20.dp)
                        else Icon(HerdrIcons.Terminal, null, tint = c.muted, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(Space.m))
                        Text(
                            buildAnnotatedString {
                                append(agent?.displayName ?: pane.displayName)
                                if (agent != null) withStyle(SpanStyle(color = if (status == AgentStatus.Unknown) c.muted else status.color())) {
                                    append(" · " + agent.statusLabel)
                                }
                                else withStyle(SpanStyle(color = c.muted)) {
                                    (pane.foregroundCwd ?: pane.cwd)?.let { append(" · " + it.replace(Regex("^/(home|Users)/[^/]+"), "~")) }
                                }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        SecondaryButton(
                            onClick = { onTerminal(pane) },
                            contentPadding = PaddingValues(horizontal = 14.dp),
                        ) { Text("Terminal") }
                    }
                }
            }
        }
    }
}

/** A 48dp icon button inside a row or card, quieter than a bar icon. */
@Composable
private fun RowIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Herdr.colors.muted, modifier = Modifier.size(20.dp)) }
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
