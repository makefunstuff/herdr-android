package com.makefunstuff.herdr.ui.manage

import com.makefunstuff.herdr.ui.design.HerdrDialog
import com.makefunstuff.herdr.ui.design.HerdrMenu
import com.makefunstuff.herdr.ui.design.HerdrMenuItem
import com.makefunstuff.herdr.ui.design.HerdrSheet
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.api.HerdrApi
import com.makefunstuff.herdr.core.api.NotificationResult
import com.makefunstuff.herdr.core.api.PaneDestination
import com.makefunstuff.herdr.core.api.PaneInfo
import com.makefunstuff.herdr.core.api.PaneProcessInfo
import com.makefunstuff.herdr.core.api.SessionSnapshot
import com.makefunstuff.herdr.core.api.TabInfo
import com.makefunstuff.herdr.core.api.WorkspaceInfo
import com.makefunstuff.herdr.core.api.moveIndex
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.ui.common.copyToClipboard
import com.makefunstuff.herdr.ui.common.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Runs one management call and tells the person what happened: herdr's own words when it refuses
 * (a plain sentence), the toast when the call fails, a refresh so the screen catches up.
 */
fun manage(
    context: Context,
    scope: CoroutineScope,
    session: HostSession,
    onDone: () -> Unit,
    block: suspend (HerdrApi) -> String?,
) {
    scope.launch {
        val note = runCatching { block(session.awaitApi()) }
            .getOrElse { it.message ?: "herdr didn't answer" }
        session.refresh()
        if (!note.isNullOrBlank()) context.toast(note)
        onDone()
    }
}

/** The shell every management sheet sits in: a title, a one-line subtitle, and a scrolling list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageSheet(
    title: String,
    subtitle: String? = null,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    HerdrSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 28.dp),
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

/** One row of a sheet: an optional icon, a label, an optional second line, and a tap. */
@Composable
fun ManageRow(
    label: String,
    icon: ImageVector? = null,
    detail: String? = null,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 13.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(label, color = tint, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A heading inside a sheet, to group rows. */
@Composable
fun ManageHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 2.dp),
    )
}

/** Renames something. An empty box clears the name. */
@Composable
fun RenameDialog(
    title: String,
    initial: String,
    label: String = "Name",
    allowClear: Boolean = true,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    HerdrDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(label) },
                supportingText = if (allowClear) ({ Text("Leave empty to clear the name") }) else null,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onRename(text.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Confirms something that closes or removes what is there. */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    HerdrDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun pathShort(path: String?): String? =
    path?.replace(Regex("^/(home|Users)/[^/]+"), "~")

// Workspaces

@Composable
fun WorkspaceActionsSheet(
    session: HostSession,
    ws: WorkspaceInfo,
    snap: SessionSnapshot,
    meta: Map<String, String>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rename by remember { mutableStateOf(false) }
    var metadata by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    val label = ws.label.ifBlank { ws.workspaceId }
    val order = snap.workspaces.map { it.workspaceId }
    val index = order.indexOf(ws.workspaceId)
    val up = moveIndex(order, ws.workspaceId, index - 1)
    val down = moveIndex(order, ws.workspaceId, index + 1)

    ManageSheet(label, "Workspace ${ws.number} · ${ws.tabCount} tab${if (ws.tabCount == 1) "" else "s"} · ${ws.paneCount} pane${if (ws.paneCount == 1) "" else "s"}", onDismiss) {
        if (!ws.focused) {
            ManageRow("Focus in herdr", icon = Icons.Filled.Check) {
                manage(context, scope, session, onDismiss) { it.focusWorkspace(ws.workspaceId); null }
            }
        }
        ManageRow("Rename", icon = Icons.Filled.Edit) { rename = true }
        ManageRow(
            "Metadata",
            icon = Icons.Filled.Info,
            detail = meta.entries.joinToString(" · ") { "${it.key}: ${it.value}" }.ifBlank { "Nothing reported" },
            enabled = meta.isNotEmpty(),
        ) { metadata = true }
        ManageHeading("Reorder")
        ManageRow("Move up", detail = "Earlier in the workspace list", enabled = up != null) {
            manage(context, scope, session, onDismiss) { it.moveWorkspace(ws.workspaceId, up!!); null }
        }
        ManageRow("Move down", detail = "Later in the workspace list", enabled = down != null) {
            manage(context, scope, session, onDismiss) { it.moveWorkspace(ws.workspaceId, down!!); null }
        }
        ManageHeading("Danger")
        ManageRow("Close workspace", icon = Icons.Filled.Delete, destructive = true) { confirmClose = true }
    }

    if (rename) {
        RenameDialog("Rename workspace", ws.label, onDismiss = { rename = false }) { name ->
            rename = false
            if (name != ws.label) manage(context, scope, session, onDismiss) { it.renameWorkspace(ws.workspaceId, name); null }
        }
    }
    if (metadata) {
        MetadataSheet(label, meta, onDismiss = { metadata = false })
    }
    if (confirmClose) {
        ConfirmDialog(
            "Close $label?",
            "Its tabs, panes and any agents running in them close too.",
            "Close workspace",
            onDismiss = { confirmClose = false },
        ) {
            confirmClose = false
            manage(context, scope, session, onDismiss) { it.closeWorkspace(ws.workspaceId); null }
        }
    }
}

@Composable
private fun MetadataSheet(title: String, meta: Map<String, String>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ManageSheet("$title metadata", "Reported by herdr integrations", onDismiss) {
        meta.forEach { (key, value) ->
            ManageRow(key, detail = value) { context.copyToClipboard(key, value, "Copied $key") }
        }
    }
}

// Tabs

@Composable
fun TabActionsSheet(
    session: HostSession,
    tab: TabInfo,
    snap: SessionSnapshot,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rename by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    val label = tab.label.ifBlank { tab.tabId }
    val order = snap.tabs.filter { it.workspaceId == tab.workspaceId }.map { it.tabId }
    val index = order.indexOf(tab.tabId)
    val up = moveIndex(order, tab.tabId, index - 1)
    val down = moveIndex(order, tab.tabId, index + 1)

    ManageSheet(label, "Tab ${tab.number} · ${tab.paneCount} pane${if (tab.paneCount == 1) "" else "s"}", onDismiss) {
        if (!tab.focused) {
            ManageRow("Focus in herdr", icon = Icons.Filled.Check) {
                manage(context, scope, session, onDismiss) { it.focusTab(tab.tabId); null }
            }
        }
        ManageRow("Rename", icon = Icons.Filled.Edit) { rename = true }
        val canClose = order.size > 1
        ManageHeading("Reorder")
        ManageRow("Move left", enabled = up != null) {
            manage(context, scope, session, onDismiss) { it.moveTab(tab.tabId, up!!); null }
        }
        ManageRow("Move right", enabled = down != null) {
            manage(context, scope, session, onDismiss) { it.moveTab(tab.tabId, down!!); null }
        }
        ManageHeading("Danger")
        ManageRow(
            "Close tab",
            icon = Icons.Filled.Delete,
            detail = if (canClose) null else "A workspace keeps its last tab",
            destructive = true,
            enabled = canClose,
        ) { confirmClose = true }
    }

    if (rename) {
        RenameDialog("Rename tab", tab.label, onDismiss = { rename = false }) { name ->
            rename = false
            if (name != tab.label) manage(context, scope, session, onDismiss) { it.renameTab(tab.tabId, name); null }
        }
    }
    if (confirmClose) {
        ConfirmDialog(
            "Close $label?",
            "Its panes and any agents running in them close too.",
            "Close tab",
            onDismiss = { confirmClose = false },
        ) {
            confirmClose = false
            manage(context, scope, session, onDismiss) { it.closeTab(tab.tabId); null }
        }
    }
}

// Panes

private sealed interface PaneScreen {
    data object Actions : PaneScreen
    data object Move : PaneScreen
    data object Swap : PaneScreen
    data object Process : PaneScreen
}

@Composable
fun PaneActionsSheet(
    session: HostSession,
    pane: PaneInfo,
    snap: SessionSnapshot,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf<PaneScreen>(PaneScreen.Actions) }
    var rename by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    val label = pane.displayName
    val layout = snap.layout(pane.tabId)
    val zoomed = layout?.zoomed == true
    val siblings = snap.panes.filter { it.tabId == pane.tabId }
    val open = siblings.size > 1
    val tabs = snap.tabs.filter { it.workspaceId == pane.workspaceId && it.tabId != pane.tabId }

    val title = when (screen) {
        PaneScreen.Move -> "Move $label"
        PaneScreen.Swap -> "Swap $label"
        PaneScreen.Process -> "Running in $label"
        PaneScreen.Actions -> label
    }
    ManageSheet(title, snap.location(pane.workspaceId, pane.tabId) + (pathShort(pane.cwd)?.let { " · $it" } ?: ""), onDismiss) {
        when (screen) {
            PaneScreen.Actions -> {
                if (!pane.focused) {
                    ManageRow("Focus in herdr", icon = Icons.Filled.Check) {
                        manage(context, scope, session, onDismiss) { it.focusPane(pane.paneId); null }
                    }
                }
                ManageRow("Rename", icon = Icons.Filled.Edit) { rename = true }
                ManageRow(
                    if (zoomed) "Unzoom" else "Zoom to fit",
                    detail = if (zoomed) "Show the whole layout again" else "Fill the tab with this pane",
                    enabled = open,
                ) {
                    manage(context, scope, session, onDismiss) { it.zoomPane(pane.paneId).refusal }
                }
                ManageRow("What's running", icon = Icons.Filled.Info) { screen = PaneScreen.Process }
                ManageRow("Clear screen and scrollback", icon = Icons.Filled.Clear) { confirmClear = true }
                ManageHeading("Layout")
                ManageRow("Move to another tab or workspace", enabled = true) { screen = PaneScreen.Move }
                ManageRow("Swap with another pane", enabled = open) { screen = PaneScreen.Swap }
                if (open) {
                    ManageRow("Focus left", enabled = true) { manage(context, scope, session, onDismiss) { it.focusDirection(pane.paneId, "left").refusal } }
                    ManageRow("Focus right", enabled = true) { manage(context, scope, session, onDismiss) { it.focusDirection(pane.paneId, "right").refusal } }
                    ManageRow("Focus up", enabled = true) { manage(context, scope, session, onDismiss) { it.focusDirection(pane.paneId, "up").refusal } }
                    ManageRow("Focus down", enabled = true) { manage(context, scope, session, onDismiss) { it.focusDirection(pane.paneId, "down").refusal } }
                }
                ManageHeading("Danger")
                ManageRow("Close pane", icon = Icons.Filled.Delete, destructive = true) { confirmClose = true }
            }

            PaneScreen.Move -> {
                ManageRow("Back", icon = Icons.Filled.Close) { screen = PaneScreen.Actions }
                ManageHeading("Into a tab")
                if (tabs.isEmpty()) ManageRow("No other tabs", enabled = false)
                tabs.forEach { t ->
                    ManageRow(t.label.ifBlank { t.tabId }, detail = "Split into this tab") {
                        manage(context, scope, session, onDismiss) {
                            it.movePane(pane.paneId, PaneDestination.Tab(t.tabId)).refusal
                        }
                    }
                }
                ManageHeading("Into something new")
                ManageRow("A new tab here") {
                    manage(context, scope, session, onDismiss) {
                        it.movePane(pane.paneId, PaneDestination.NewTab(workspaceId = pane.workspaceId)).refusal
                    }
                }
                ManageRow("A new workspace") {
                    manage(context, scope, session, onDismiss) {
                        it.movePane(pane.paneId, PaneDestination.NewWorkspace()).refusal
                    }
                }
            }

            PaneScreen.Swap -> {
                ManageRow("Back", icon = Icons.Filled.Close) { screen = PaneScreen.Actions }
                ManageHeading("With the pane next to it")
                listOf("left", "right", "up", "down").forEach { direction ->
                    ManageRow("Swap $direction") {
                        manage(context, scope, session, onDismiss) { it.swapPane(pane.paneId, direction = direction).refusal }
                    }
                }
                ManageHeading("With a pane in this tab")
                siblings.filter { it.paneId != pane.paneId }.forEach { other ->
                    ManageRow(other.displayName) {
                        manage(context, scope, session, onDismiss) { it.swapPane(pane.paneId, targetPaneId = other.paneId).refusal }
                    }
                }
            }

            PaneScreen.Process -> {
                ManageRow("Back", icon = Icons.Filled.Close) { screen = PaneScreen.Actions }
                ProcessInfo(session, pane)
            }
        }
    }

    if (rename) {
        RenameDialog("Rename pane", pane.label.orEmpty(), onDismiss = { rename = false }) { name ->
            rename = false
            if (name != pane.label.orEmpty()) {
                manage(context, scope, session, onDismiss) { it.renamePane(pane.paneId, name); null }
            }
        }
    }
    if (confirmClear) {
        ConfirmDialog(
            "Clear $label?",
            "herdr wipes the screen and the scrollback. What is running keeps running.",
            "Clear",
            onDismiss = { confirmClear = false },
        ) {
            confirmClear = false
            manage(context, scope, session, onDismiss) { it.clearPane(pane.paneId); "Cleared." }
        }
    }
    if (confirmClose) {
        ConfirmDialog(
            "Close $label?",
            "Anything running in the pane exits.",
            "Close pane",
            onDismiss = { confirmClose = false },
        ) {
            confirmClose = false
            manage(context, scope, session, onDismiss) { it.closePane(pane.paneId); null }
        }
    }
}

@Composable
private fun ProcessInfo(session: HostSession, pane: PaneInfo) {
    val context = LocalContext.current
    var info by remember { mutableStateOf<PaneProcessInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(pane.paneId, pane.revision) {
        loading = true
        runCatching { session.awaitApi().processInfo(pane.paneId) }
            .onSuccess { info = it }
            .onFailure { error = it.message ?: "Couldn't read the pane" }
        loading = false
    }
    if (loading) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(14.dp))
            Text("Reading the pane…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else if (error != null) {
        ManageRow("Couldn't read it", icon = Icons.Filled.Info, detail = error)
    } else {
        val i = info!!
        ManageRow("Shell", detail = "pid ${i.shellPid ?: "?"}" + (i.tty?.let { " · $it" } ?: ""))
        ManageHeading("Foreground")
        if (i.processes.isEmpty()) {
            ManageRow("Nothing but the shell")
        }
        i.processes.forEach { p ->
            ManageRow(p.name, detail = listOfNotNull(p.command, pathShort(p.cwd)).joinToString(" · ")) {
                context.copyToClipboard(p.name, listOfNotNull(p.command, p.cwd).joinToString("\n"), "Copied the command")
            }
        }
    }
}

// Agents

@Composable
fun AgentActionsSheet(
    session: HostSession,
    agent: AgentInfo?,
    paneLabel: String,
    onDismiss: () -> Unit,
    onTerminal: (() -> Unit)? = null,
    onToggleKeys: (() -> Unit)? = null,
    showKeys: Boolean = false,
    onKey: ((List<String>) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val target = agent?.paneId
    var rename by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var explain by remember { mutableStateOf(false) }
    val label = agent?.let { it.name?.takeIf { n -> n.isNotBlank() } ?: it.displayName } ?: paneLabel

    ManageSheet(label, agent?.let { "${it.statusLabel} · ${it.agent ?: "terminal"}" } ?: "No agent in this pane", onDismiss) {
        if (target != null) {
            ManageRow("Rename agent", icon = Icons.Filled.Edit) { rename = true }
            ManageRow("Focus in herdr", icon = Icons.Filled.Check) {
                manage(context, scope, session, onDismiss) { it.focusAgent(target); null }
            }
            ManageRow("Why this state?", icon = Icons.Filled.Info) { explain = true }
            ManageRow("Details", icon = Icons.Filled.Info) { details = true }
        }
        if (onTerminal != null) ManageRow("Open terminal", icon = Icons.Filled.Check) { onTerminal() }
        if (onToggleKeys != null) {
            ManageRow(if (showKeys) "Hide keys" else "Show keys") { onToggleKeys() }
        }
        if (onKey != null) {
            ManageHeading("Send keys")
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Enter" to "enter", "Esc" to "esc", "Tab" to "tab", "Ctrl+C" to "ctrl+c").forEach { (text, key) ->
                    TextButton(onClick = { onKey(listOf(key)) }) { Text(text) }
                }
            }
        }
    }

    if (rename && target != null) {
        RenameDialog("Rename agent", agent?.name.orEmpty(), onDismiss = { rename = false }) { name ->
            rename = false
            if (name != agent?.name.orEmpty()) {
                manage(context, scope, session, onDismiss) { it.renameAgent(target, name.ifBlank { null }); null }
            }
        }
    }
    if (details && target != null) {
        DetailSheet("Agent details", onDismiss = { details = false }) {
            AgentDetailRows(agent)
        }
    }
    if (explain && target != null) {
        ExplainSheet(session, target, onDismiss = { explain = false })
    }
}

@Composable
private fun AgentDetailRows(agent: AgentInfo?) {
    val context = LocalContext.current
    ManageRow("Name", detail = agent?.name?.takeIf { it.isNotBlank() } ?: "(none)")
    ManageRow("Agent", detail = agent?.agent ?: "(unknown)")
    ManageRow("State", detail = agent?.let { if (it.statusLabel == it.agentStatusRaw) it.statusLabel else "${it.statusLabel} (${it.agentStatusRaw})" } ?: "(unknown)")
    ManageRow("Pane", detail = agent?.paneId.orEmpty()) { context.copyToClipboard("pane id", agent?.paneId.orEmpty()) }
    ManageRow("Workspace · tab", detail = listOfNotNull(agent?.workspaceId, agent?.tabId).joinToString(" · "))
    ManageRow("Working dir", detail = pathShort(agent?.cwd) ?: "(unknown)")
    agent?.terminalId?.takeIf { it.isNotBlank() }?.let { ManageRow("Terminal", detail = it) }
    agent?.agentSession?.let { s ->
        ManageRow("Transcript", detail = "${s.agent} · ${s.kind}: ${s.value}") { context.copyToClipboard("session", s.value, "Copied the session") }
    }
    agent?.subtitle?.takeIf { it.isNotBlank() }?.let { ManageRow("Doing", detail = it) }
}

@Composable
private fun ExplainSheet(session: HostSession, target: String, onDismiss: () -> Unit) {
    var summary by remember { mutableStateOf<String?>(null) }
    var extra by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(target) {
        runCatching { session.awaitApi().explainAgent(target) }
            .onSuccess { e ->
                summary = e.summary
                extra = buildList {
                    e.matchedRule?.let { add("Matched rule" to it.replace('_', ' ')) }
                    e.warning?.let { add("Warning" to it) }
                    e.manifest?.let { add("Integration" to it) }
                    e.rules.filter { it.matched }.forEach { rule ->
                        add(
                            rule.id.replace('_', ' ') to listOfNotNull(rule.state, rule.region.ifBlank { null }, rule.preview.ifBlank { null })
                                .joinToString(" · "),
                        )
                    }
                }
            }
            .onFailure { error = it.message ?: "herdr couldn't explain it" }
        loading = false
    }
    DetailSheet("Why this state?", onDismiss = onDismiss) {
        when {
            loading -> ManageRow("Asking herdr…")
            error != null -> ManageRow("Couldn't read it", detail = error)
            else -> {
                Text(
                    summary.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
                extra.forEach { (k, v) -> ManageRow(k, detail = v) }
            }
        }
    }
}

/** A read-only sheet with a title and however many rows the caller wants. */
@Composable
private fun DetailSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ManageSheet(title, null, onDismiss) { content() }
}

// Notifications

@Composable
fun NotifySheet(session: HostSession, machineLabel: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var sound by remember { mutableStateOf("none") }

    ManageSheet("Notify herdr on $machineLabel", "Pops up on the desktop running herdr", onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = body, onValueChange = { body = it }, label = { Text("Body") }, maxLines = 3, modifier = Modifier.fillMaxWidth())
        }
        ManageHeading("Sound")
        ManageRow("None", icon = if (sound == "none") Icons.Filled.Check else null) { sound = "none" }
        ManageRow("Done", icon = if (sound == "done") Icons.Filled.Check else null) { sound = "done" }
        ManageRow("Request", icon = if (sound == "request") Icons.Filled.Check else null) { sound = "request" }
        ManageRow("Send", icon = Icons.Filled.Notifications, enabled = title.isNotBlank()) {
            manage(context, scope, session, onDismiss) {
                val result: NotificationResult = it.showNotification(title, body, sound)
                result.message
            }
        }
    }
}

