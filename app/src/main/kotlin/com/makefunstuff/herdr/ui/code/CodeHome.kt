package com.makefunstuff.herdr.ui.code

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.core.code.Checkout
import com.makefunstuff.herdr.core.code.Discovery
import com.makefunstuff.herdr.core.host.HostState
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.StatusDot
import com.makefunstuff.herdr.ui.common.color
import com.makefunstuff.herdr.ui.common.label
import com.makefunstuff.herdr.ui.design.AgentMark
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.ErrorMessage
import com.makefunstuff.herdr.ui.design.HerdrDialog
import com.makefunstuff.herdr.ui.design.HerdrField
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.IconTile
import com.makefunstuff.herdr.ui.design.ListRow
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.design.RowDivider
import com.makefunstuff.herdr.ui.design.SectionHeading
import com.makefunstuff.herdr.ui.theme.CodeStyle
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import com.makefunstuff.herdr.ui.theme.color

/** Code, from the Machines tab: which machine's. */
@Composable
fun CodeMachinesScreen(nav: NavController) {
    val container = LocalContainer.current
    val hosts by container.hosts.hosts.collectAsStateWithLifecycle()
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Herdr.colors.ground,
        topBar = { HerdrTopBar("Code", subtitle = "Choose a machine", onBack = { nav.popBackStack() }) },
    ) { padding ->
        if (hosts.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize()) {
                EmptyState("No machines yet", "Add a machine that runs herdr to read the code on it.", icon = HerdrIcons.Monitor)
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(Space.screen),
        ) {
            item {
                QuietCard {
                    hosts.forEachIndexed { i, h ->
                        if (i > 0) RowDivider(inset = 68.dp)
                        val repos = h.snapshot?.workspaces?.mapNotNull { it.worktree?.repoName?.ifBlank { null } }?.distinct().orEmpty()
                        ListRow(
                            h.machine.label,
                            subtitle = if (h.online && repos.isNotEmpty()) repos.joinToString(", ") else h.status.label(),
                            leading = { IconTile(HerdrIcons.Monitor, dot = h.status.color()) },
                            chevron = true,
                            onClickLabel = "Show its code",
                            onClick = { nav.navigate(Routes.codeRepos(h.machine.id)) },
                        )
                    }
                }
            }
        }
    }
}

/** Every folder herdr knows on the machine: its workspaces', panes' and agents' folders. */
private fun HostState.folders(): List<String> {
    val snap = snapshot ?: return emptyList()
    return buildList {
        snap.workspaces.forEach { ws -> ws.worktree?.let { add(it.checkoutPath); add(it.repoRoot) } }
        snap.panes.forEach { p -> p.foregroundCwd?.let(::add); p.cwd?.let(::add) }
        snap.agents.forEach { a -> a.cwd?.let(::add) }
    }.filter { it.isNotBlank() }.distinct().sorted()
}

/**
 * A machine's code: each repository its workspaces and agents are in, with its checkouts (the main
 * one and the worktrees, the agents at work in each), and the folders outside Git. Any other folder
 * opens by its path.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CodeReposScreen(nav: NavController, machineId: String) {
    val container = LocalContainer.current
    val session = container.hosts.session(machineId)
    if (session == null) {
        EmptyState("Machine not found", "It may have been removed.")
        return
    }
    val host by session.state.collectAsStateWithLifecycle()
    val folders = remember(host.snapshot) { host.folders() }
    var again by remember { mutableIntStateOf(0) }
    var opening by remember { mutableStateOf(false) }
    val found = if (host.online) rememberLoad("repos:$machineId:${folders.hashCode()}", container.code.cache, again) { session.code().discover(folders) } else null
    val c = Herdr.colors

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = c.ground,
        topBar = {
            HerdrTopBar("Code", subtitle = host.machine.label, onBack = { nav.popBackStack() }) {
                BarIcon(HerdrIcons.RefreshCw, "Look again", { again++ })
                BarIcon(HerdrIcons.FolderOpen, "Open a folder", { opening = true })
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                found == null -> EmptyState(
                    if (host.snapshot == null) "Not connected" else "Not connected now", host.status.label(), icon = HerdrIcons.Monitor,
                )
                found is Load.Loading -> LoadingState("Finding the repositories", Modifier.align(Alignment.Center))
                found is Load.Failed -> ErrorMessage(found.message, Modifier.padding(Space.screen)) { again++ }
                found is Load.Ready -> Repos(nav, host, found.value, folders)
            }
        }
    }

    if (opening) OpenFolder(onDismiss = { opening = false }) { dir ->
        opening = false
        nav.navigate(Routes.codeAt(CodeSpot(machineId, dir)))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Repos(nav: NavController, host: HostState, found: Discovery, folders: List<String>) {
    val machineId = host.machine.id
    val outside = folders.filter { it !in found.topOf }.filter { f -> found.repos.none { r -> r.checkouts.any { f.startsWith(it.path) } } }
    if (found.repos.isEmpty() && outside.isEmpty()) {
        EmptyState(
            "No code open here",
            "No workspace on ${host.machine.label} is in a folder yet. Open one by its path.",
            icon = HerdrIcons.FolderGit2,
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Space.screen, end = Space.screen, bottom = Space.xxl)) {
        found.repos.sortedBy { it.name.lowercase() }.forEach { repo ->
            item(key = "r:${repo.main.path}") {
                SectionHeading(repo.name, top = Space.l, trailing = { CountPill("${repo.checkouts.size}") })
                QuietCard {
                    repo.checkouts.forEachIndexed { i, co ->
                        if (i > 0) RowDivider(inset = 68.dp)
                        CheckoutRow(host, co) { nav.navigate(Routes.codeAt(CodeSpot(machineId, co.path))) }
                    }
                }
            }
        }
        if (outside.isNotEmpty()) item(key = "outside") {
            SectionHeading("Folders outside Git", top = Space.l)
            QuietCard {
                outside.forEachIndexed { i, f ->
                    if (i > 0) RowDivider(inset = 68.dp)
                    ListRow(
                        f.trimEnd('/').substringAfterLast('/'), subtitle = host.shortPath(f),
                        leading = { IconTile(HerdrIcons.Folder) }, chevron = true, onClickLabel = "Open the folder",
                        onClick = { nav.navigate(Routes.codeAt(CodeSpot(machineId, f))) },
                    )
                }
            }
        }
    }
}

/** A checkout: its branch, where it is, the agents at work in it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CheckoutRow(host: HostState, co: Checkout, onClick: () -> Unit) {
    val c = Herdr.colors
    val agents = host.agentsIn(co.path)
    ListRow(
        co.branch ?: "detached at ${co.head.take(7)}",
        subtitle = host.shortPath(co.path),
        meta = if (co.main) "main checkout" else null,
        leading = { IconTile(if (co.main) HerdrIcons.FolderGit2 else HerdrIcons.GitBranch) },
        chevron = true,
        enabled = !co.prunable,
        below = if (agents.isEmpty()) null else ({
            FlowRow(Modifier.padding(top = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                agents.forEach { a ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                        AgentMark(a.agent ?: a.displayName, size = 16.dp)
                        Text(a.shownName, style = MaterialTheme.typography.labelMedium, color = c.text)
                        StatusDot(a.agentStatus.color(), size = 6)
                    }
                }
            }
        }),
        onClickLabel = "Open the checkout",
        onClick = onClick,
    )
}

@Composable
private fun OpenFolder(onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    var path by remember { mutableStateOf("~/") }
    val ok = path.trim().let { it.startsWith("/") || it.startsWith("~") } && path.trim().length > 1
    HerdrDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open a folder") },
        text = {
            HerdrField(
                path, { path = it }, singleLine = true, label = "Folder on the machine", textStyle = CodeStyle,
                supportingText = { Text("A full path, or one from your home folder (~/src/app).") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = { if (ok) onOpen(path.trim()) }),
            )
        },
        dismissButton = { QuietButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = { QuietButton(onClick = { onOpen(path.trim()) }, enabled = ok) { Text("Open") } },
    )
}
