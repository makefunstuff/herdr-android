package com.makefunstuff.herdr.ui.worktrees

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.core.api.GitWorktree
import com.makefunstuff.herdr.core.api.SessionSnapshot
import com.makefunstuff.herdr.core.api.WorktreeErrors
import com.makefunstuff.herdr.core.api.WorktreeList
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.Pill
import com.makefunstuff.herdr.ui.common.StatusDot
import com.makefunstuff.herdr.ui.common.copyToClipboard
import com.makefunstuff.herdr.ui.common.toast
import com.makefunstuff.herdr.ui.theme.color
import kotlinx.coroutines.launch

/** A repository on the machine, and how to name it to herdr. */
private data class Repo(val key: String, val name: String, val root: String, val workspaceId: String?) {
    val target: RepoTarget get() = RepoTarget(workspaceId, if (workspaceId == null) root else null)
}

private sealed interface Listing {
    data object Loading : Listing
    data class Loaded(val list: WorktreeList) : Listing
    data class Failed(val message: String, val needsTrust: Boolean) : Listing
}

/** Repositories that workspaces on the machine are open in, keyed by herdr's repo key. */
private fun SessionSnapshot.repos(): Map<String, Repo> =
    workspaces.filter { it.worktree != null }.groupBy { it.worktree!!.repoKey }.mapValues { (key, list) ->
        val wt = list.first().worktree!!
        // Prefer the workspace on the main checkout, so new worktrees branch from there by default.
        val ws = list.firstOrNull { it.worktree?.checkoutPath == wt.repoRoot } ?: list.first()
        Repo(key, wt.repoName.ifBlank { wt.repoRoot.substringAfterLast('/') }, wt.repoRoot, ws.workspaceId)
    }

/** The pane to show for a workspace: the focused pane of its active tab. */
fun SessionSnapshot.entryPane(workspaceId: String): String? {
    val ws = workspace(workspaceId) ?: return null
    return layout(ws.activeTabId)?.focusedPaneId?.ifEmpty { null }
        ?: panes.firstOrNull { it.tabId == ws.activeTabId }?.paneId
        ?: panes.firstOrNull { it.workspaceId == workspaceId }?.paneId
}

/** The folder a workspace's active pane is in, for resolving the repository it belongs to. */
fun SessionSnapshot.workspaceFolder(workspaceId: String): String? {
    val paneId = entryPane(workspaceId) ?: return null
    val pane = panes.firstOrNull { it.paneId == paneId } ?: return null
    return pane.foregroundCwd?.ifBlank { null } ?: pane.cwd?.ifBlank { null }
}

fun NavController.openPane(snap: SessionSnapshot, machineId: String, paneId: String) {
    val hasAgent = snap.agents.any { it.paneId == paneId }
    navigate(if (hasAgent) Routes.agent(machineId, paneId) else Routes.terminal(machineId, paneId))
}

/**
 * A machine's Git worktrees: every repository its workspaces are open in, or only the one of
 * [workspaceId]. Lists each repository's checkouts and opens, creates and removes them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorktreesScreen(nav: NavController, machineId: String, workspaceId: String?) {
    val container = LocalContainer.current
    val session = container.hosts.session(machineId)
    if (session == null) {
        EmptyState("Machine not found", "It may have been removed.")
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by session.state.collectAsStateWithLifecycle()
    val snap = state.snapshot

    // Repositories stay listed while on this screen, even after their last workspace closes.
    val known = remember { mutableStateMapOf<String, Repo>() }
    val listings = remember { mutableStateMapOf<String, Listing>() }
    val trusted = remember { mutableStateMapOf<String, Boolean>() }
    var onlyRepo by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }

    var creating by remember { mutableStateOf<Repo?>(null) }
    var removing by remember { mutableStateOf<RemoveRequest?>(null) }
    var actionsFor by remember { mutableStateOf<Pair<Repo, GitWorktree>?>(null) }
    var trusting by remember { mutableStateOf<Repo?>(null) }
    var addingPath by remember { mutableStateOf(false) }
    var trustingPath by remember { mutableStateOf<String?>(null) }

    // What changes a listing: workspaces opening, closing or moving between checkouts.
    val fingerprint = snap?.workspaces?.map { it.workspaceId to it.worktree }
    // Workspaces in a Git folder that herdr doesn't (yet) report as a worktree-backed checkout.
    val plainFolders = snap?.let { s ->
        s.workspaces.filter { it.worktree == null }.mapNotNull { ws ->
            s.workspaceFolder(ws.workspaceId)?.let { ws.workspaceId to it }
        }
    }.orEmpty()

    LaunchedEffect(fingerprint) {
        val current = snap?.repos() ?: return@LaunchedEffect
        if (workspaceId != null && onlyRepo == null) {
            onlyRepo = snap.workspace(workspaceId)?.worktree?.repoKey
        }
        current.forEach { (key, repo) -> known[key] = repo }
        // A repository whose workspaces all closed is still reachable by its folder.
        known.keys.filter { it !in current && known[it]?.workspaceId != null }.forEach { known[it] = known[it]!!.copy(workspaceId = null) }
    }

    // Ask herdr to resolve the repository behind a plain workspace, so its worktrees are reachable
    // from the workspace too, not only from checkouts herdr already calls worktrees.
    LaunchedEffect(plainFolders, state.online) {
        if (!state.online) return@LaunchedEffect
        plainFolders.forEach { (wsId, folder) ->
            val list = runCatching { session.awaitApi().worktrees(cwd = folder) }.getOrNull() ?: return@forEach
            val source = list.source
            if (source.repoKey.isEmpty()) return@forEach
            if (known[source.repoKey] == null) {
                known[source.repoKey] = Repo(
                    source.repoKey,
                    source.repoName.ifBlank { source.repoRoot.substringAfterLast('/') },
                    source.repoRoot,
                    source.sourceWorkspaceId ?: wsId,
                )
            }
            if (listings[source.repoKey] == null) listings[source.repoKey] = Listing.Loaded(list)
            if (workspaceId != null && onlyRepo == null && (wsId == workspaceId || source.sourceWorkspaceId == workspaceId)) {
                onlyRepo = source.repoKey
            }
        }
    }

    val visible = known.values.filter { onlyRepo == null || it.key == onlyRepo }.sortedBy { it.name.lowercase() }

    suspend fun load(repo: Repo) {
        if (listings[repo.key] !is Listing.Loaded) listings[repo.key] = Listing.Loading
        val target = repo.target.copy(trusted = trusted[repo.key] == true)
        listings[repo.key] = runCatching { session.awaitApi().worktrees(target.workspaceId, target.cwd, target.trusted) }.fold(
            onSuccess = { Listing.Loaded(it) },
            onFailure = { Listing.Failed(WorktreeErrors.describe(it), WorktreeErrors.needsTrust(it)) },
        )
    }

    LaunchedEffect(fingerprint, refreshTick, visible.map { it.key to it.workspaceId }, trusted.toMap()) {
        if (!state.online) return@LaunchedEffect
        visible.forEach { launch { load(it) } }
    }

    fun targetOf(repo: Repo) = repo.target.copy(trusted = trusted[repo.key] == true)

    fun open(repo: Repo, wt: GitWorktree) {
        val ws = wt.openWorkspaceId
        if (ws != null && snap != null) {
            val pane = snap.entryPane(ws) ?: return context.toast("That workspace has no panes")
            nav.openPane(snap, machineId, pane)
            return
        }
        scope.launch {
            val t = targetOf(repo)
            runCatching { session.awaitApi().openWorktree(t.workspaceId, t.cwd, path = wt.path, trustRepository = t.trusted) }
                .onSuccess {
                    session.refresh()
                    context.toast("Opened ${wt.title} as workspace ${it.workspace.label.ifBlank { it.workspace.workspaceId }}")
                    refreshTick++
                }
                .onFailure { if (WorktreeErrors.needsTrust(it)) trusting = repo else context.toast(WorktreeErrors.describe(it)) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    Column {
                        Text(if (onlyRepo != null) known[onlyRepo]?.name ?: "Worktrees" else "Worktrees")
                        Text(
                            if (onlyRepo != null) "Worktrees · ${state.machine.label}" else state.machine.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { session.refresh(); refreshTick++ }) { Icon(Icons.Filled.Refresh, "Refresh") }
                },
            )
        },
    ) { padding ->
        if (snap == null || (!state.online && visible.isEmpty())) {
            EmptyState(if (state.online) "Loading…" else "Offline", "Worktrees show once ${state.machine.label} is connected.")
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.online) {
                item { Text("Offline · showing the last known state", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
            }
            if (visible.isEmpty()) {
                item {
                    Text(
                        "No workspace here is in a Git repository. Open a repository by its folder to see its worktrees.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(visible, key = { it.key }) { repo ->
                RepoCard(
                    repo = repo,
                    listing = listings[repo.key] ?: Listing.Loading,
                    snap = snap,
                    onNew = { creating = repo },
                    onTrust = { trusting = repo },
                    onOpen = { open(repo, it) },
                    onActions = { actionsFor = repo to it },
                )
            }
            if (onlyRepo == null) {
                item {
                    OutlinedButton(onClick = { addingPath = true }, modifier = Modifier.fillMaxWidth()) { Text("Open a repository by folder…") }
                }
            }
        }
    }

    creating?.let { repo ->
        val branches = (listings[repo.key] as? Listing.Loaded)?.list?.worktrees?.mapNotNull { it.branch }.orEmpty()
        CreateWorktreeSheet(
            session, repo.name, targetOf(repo), branches,
            onDismiss = { creating = null },
            onCreated = { refreshTick++ },
            onNeedsTrust = { creating = null; trusting = repo },
        )
    }

    actionsFor?.let { (repo, wt) ->
        WorktreeActionsSheet(
            wt,
            onDismiss = { actionsFor = null },
            onOpen = { actionsFor = null; open(repo, wt) },
            onCopy = { actionsFor = null; context.copyToClipboard("Worktree path", wt.path, "Path copied") },
            onRemove = {
                actionsFor = null
                removing = RemoveRequest(wt.title, wt.path, wt.openWorkspaceId, targetOf(repo))
            },
        )
    }

    removing?.let { req ->
        RemoveWorktreeFlow(session, req) { removing = null; refreshTick++ }
    }

    trusting?.let { repo ->
        TrustRepositoryDialog(
            repo.name,
            onTrust = { trusted[repo.key] = true; trusting = null },
            onDismiss = { trusting = null },
        )
    }

    fun addRepo(path: String, trust: Boolean) {
        scope.launch {
            runCatching { session.awaitApi().worktrees(cwd = path, trustRepository = trust) }
                .onSuccess { list ->
                    val s = list.source
                    known[s.repoKey] = known[s.repoKey] ?: Repo(s.repoKey, s.repoName.ifBlank { s.repoRoot.substringAfterLast('/') }, s.repoRoot, s.sourceWorkspaceId)
                    if (trust) trusted[s.repoKey] = true
                    listings[s.repoKey] = Listing.Loaded(list)
                    addingPath = false
                }
                .onFailure {
                    if (WorktreeErrors.needsTrust(it) && !trust) trustingPath = path else context.toast(WorktreeErrors.describe(it))
                }
        }
    }

    if (addingPath) {
        AddRepoDialog(onDismiss = { addingPath = false }, onAdd = { addRepo(it, trust = false) })
    }

    trustingPath?.let { path ->
        TrustRepositoryDialog(
            path.shortPath(),
            onTrust = { trustingPath = null; addRepo(path, trust = true) },
            onDismiss = { trustingPath = null },
        )
    }
}

@Composable
private fun RepoCard(
    repo: Repo,
    listing: Listing,
    snap: SessionSnapshot,
    onNew: () -> Unit,
    onTrust: () -> Unit,
    onOpen: (GitWorktree) -> Unit,
    onActions: (GitWorktree) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(vertical = 10.dp)) {
            Row(Modifier.padding(start = 14.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(repo.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        repo.root.shortPath(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = onNew, enabled = listing !is Listing.Failed) {
                    Icon(Icons.Filled.Add, null)
                    Spacer(Modifier.width(4.dp))
                    Text("New")
                }
            }
            when (listing) {
                Listing.Loading -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is Listing.Failed -> Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(listing.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    if (listing.needsTrust) OutlinedButton(onClick = onTrust) { Text("Trust this repository…") }
                }
                is Listing.Loaded -> listing.list.worktrees.forEachIndexed { i, wt ->
                    if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 14.dp))
                    WorktreeRow(wt, snap, onClick = { onOpen(wt) }, onLongClick = { onActions(wt) })
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WorktreeRow(wt: GitWorktree, snap: SessionSnapshot, onClick: () -> Unit, onLongClick: () -> Unit) {
    val ws = wt.openWorkspaceId?.let { snap.workspace(it) }
    ListItem(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        leadingContent = ws?.let { { StatusDot(it.agentStatus.color()) } },
        headlineContent = { Text(wt.title, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(wt.path.shortPath(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!wt.isLinkedWorktree) Pill("main checkout")
                    if (ws != null) Pill("open · ${ws.label.ifBlank { ws.workspaceId }}")
                    if (wt.isPrunable) Pill("missing", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        trailingContent = { IconButton(onClick = onLongClick) { Icon(Icons.Filled.MoreVert, "Actions for ${wt.title}") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorktreeActionsSheet(wt: GitWorktree, onDismiss: () -> Unit, onOpen: () -> Unit, onCopy: () -> Unit, onRemove: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp).navigationBarsPadding()) {
            Text(wt.title, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 20.dp))
            Text(
                wt.path.shortPath(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            SheetAction(if (wt.isOpen) "Go to workspace" else "Open as workspace", onClick = onOpen)
            SheetAction("Copy path", onClick = onCopy)
            if (wt.isLinkedWorktree) {
                SheetAction("Remove worktree…", danger = true, onClick = onRemove)
            } else {
                Text(
                    "The repository's main checkout can't be removed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun SheetAction(text: String, danger: Boolean = false, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(text, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun AddRepoDialog(onDismiss: () -> Unit, onAdd: (path: String) -> Unit) {
    var path by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open a repository") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    path, { path = it.trim() },
                    label = { Text("Folder inside the repository") },
                    placeholder = { Text("/home/you/src/project") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "An absolute path on the machine. Lists its worktrees without opening anything.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(path) }, enabled = path.startsWith("/")) { Text("Show worktrees") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
