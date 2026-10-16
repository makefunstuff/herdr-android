package com.makefunstuff.herdr.ui.machines

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.core.api.PaneInfo
import com.makefunstuff.herdr.core.api.SessionSnapshot
import com.makefunstuff.herdr.core.api.TabLayout
import com.makefunstuff.herdr.core.api.WorkspaceInfo
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.Pill
import com.makefunstuff.herdr.ui.common.StatusDot
import com.makefunstuff.herdr.ui.common.color
import com.makefunstuff.herdr.ui.common.detail
import com.makefunstuff.herdr.ui.common.label
import com.makefunstuff.herdr.ui.common.toast
import com.makefunstuff.herdr.ui.coordinator.CoordinatorCard
import com.makefunstuff.herdr.ui.theme.color
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
    val selectedTab = remember { mutableStateMapOf<String, String>() }

    fun openPane(pane: PaneInfo) {
        val hasAgent = snap?.agents?.any { it.paneId == pane.paneId } == true
        nav.navigate(if (hasAgent) Routes.agent(machineId, pane.paneId) else Routes.terminal(machineId, pane.paneId))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    Column {
                        Text(state.machine.label)
                        Text(
                            "session ${state.machine.session} · ${state.status.label()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { session.retry(); session.refresh(); scope.launch { session.checkBoard() } }) { Icon(Icons.Filled.Refresh, "Refresh") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Plugins") }, onClick = { menu = false; nav.navigate(Routes.admin(machineId, 0)) })
                        DropdownMenuItem(text = { Text("Integrations") }, onClick = { menu = false; nav.navigate(Routes.admin(machineId, 1)) })
                        DropdownMenuItem(text = { Text("Server and updates") }, onClick = { menu = false; nav.navigate(Routes.admin(machineId, 2)) })
                        DropdownMenuItem(text = { Text("herdr's saved machines") }, onClick = { menu = false; nav.navigate(Routes.admin(machineId, 3)) })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Import herdr machines from here") }, onClick = {
                            menu = false
                            scope.launch {
                                runCatching { MachineImport.discover(session, container.machines.machines.value) }
                                    .onSuccess {
                                        if (it.isEmpty()) context.toast("No saved herdr machines on ${state.machine.label}") else importing = it
                                    }
                                    .onFailure { context.toast(it.message ?: "Couldn't read machines") }
                            }
                        })
                        DropdownMenuItem(text = { Text("Edit machine") }, onClick = { menu = false; nav.navigate(Routes.edit(machineId)) })
                    }
                },
            )
        },
        floatingActionButton = {
            if (state.online) {
                ExtendedFloatingActionButton(onClick = { showNew = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("New") })
            }
        },
    ) { padding ->
        if (snap == null) {
            EmptyState(state.status.label(), state.status.detail() ?: "Waiting for ${state.machine.label}…")
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!state.online) {
                item { Text("Offline · showing the last known state", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
            }
            if (state.hasBoard) {
                item(key = "coordinator") { CoordinatorCard(state.machine.label, showMachine = false) { nav.navigate(Routes.coordinator(machineId)) } }
            }
            if (snap.workspaces.isEmpty()) {
                item { Text("No workspaces yet. Tap New to create one.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(snap.workspaces, key = { it.workspaceId }) { ws ->
                WorkspaceCard(
                    ws,
                    snap,
                    selectedTabId = selectedTab[ws.workspaceId] ?: ws.activeTabId,
                    onSelectTab = { selectedTab[ws.workspaceId] = it },
                    onOpenPane = ::openPane,
                    onTerminal = { nav.navigate(Routes.terminal(machineId, it.paneId)) },
                )
            }
        }
    }

    if (showNew && snap != null) {
        NewSheet(session, snap, onDismiss = { showNew = false })
    }

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
}

@Composable
private fun WorkspaceCard(
    ws: WorkspaceInfo,
    snap: SessionSnapshot,
    selectedTabId: String,
    onSelectTab: (String) -> Unit,
    onOpenPane: (PaneInfo) -> Unit,
    onTerminal: (PaneInfo) -> Unit,
) {
    val tabs = snap.tabs.filter { it.workspaceId == ws.workspaceId }
    val tabId = tabs.firstOrNull { it.tabId == selectedTabId }?.tabId ?: tabs.firstOrNull()?.tabId
    val panes = snap.panes.filter { it.tabId == tabId }
    val layout = tabId?.let { snap.layout(it) }
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(ws.agentStatus.color())
                Spacer(Modifier.width(10.dp))
                Text(ws.label.ifBlank { ws.workspaceId }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                ws.worktree?.takeIf { it.isLinkedWorktree }?.let { Pill("worktree") }
                Spacer(Modifier.width(6.dp))
                Pill("${ws.tabCount} tab${if (ws.tabCount == 1) "" else "s"}")
            }
            if (tabs.size > 1) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(tabs, key = { it.tabId }) { t ->
                        FilterChip(selected = t.tabId == tabId, onClick = { onSelectTab(t.tabId) }, label = { Text(t.label.ifBlank { t.tabId }) })
                    }
                }
            }
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (layout != null && layout.panes.size > 1) {
                    LayoutMap(layout, snap, Modifier.size(width = 88.dp, height = 60.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    panes.forEach { pane ->
                        val agent = snap.agents.firstOrNull { it.paneId == pane.paneId }
                        val status = agent?.agentStatus ?: pane.agentStatus
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { onOpenPane(pane) }.padding(vertical = 6.dp),
                        ) {
                            StatusDot(if (agent == null) Color(0xFF5B6560) else status.color(), size = 8)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                buildString {
                                    append(agent?.displayName ?: pane.displayName)
                                    if (agent != null) append(" · ").append(agent.statusLabel)
                                    else (pane.foregroundCwd ?: pane.cwd)?.let { append(" · ").append(it.replace(Regex("^/(home|Users)/[^/]+"), "~")) }
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { onTerminal(pane) }) { Text("Terminal") }
                        }
                    }
                }
            }
        }
    }
}

/** A thumbnail of the tab's split layout, from herdr's pane rects. */
@Composable
private fun LayoutMap(layout: TabLayout, snap: SessionSnapshot, modifier: Modifier) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    val fill = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier) {
        val area = layout.area
        if (area.width <= 0 || area.height <= 0) return@Canvas
        val sx = size.width / area.width
        val sy = size.height / area.height
        layout.panes.forEach { p ->
            val agent = snap.agents.firstOrNull { it.paneId == p.paneId }
            val color = agent?.agentStatus?.color()
            val topLeft = Offset((p.rect.x - area.x) * sx + 1.5f, (p.rect.y - area.y) * sy + 1.5f)
            val sz = Size(p.rect.width * sx - 3f, p.rect.height * sy - 3f)
            drawRoundRect(color?.copy(alpha = 0.25f) ?: fill, topLeft, sz, CornerRadius(4f))
            drawRoundRect(color ?: outline, topLeft, sz, CornerRadius(4f), style = Stroke(width = 2f))
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
    AlertDialog(
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            TextButton(onClick = { onImport(candidates.filter { chosen[it.id] == true && !it.alreadyAdded }, via) }) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
