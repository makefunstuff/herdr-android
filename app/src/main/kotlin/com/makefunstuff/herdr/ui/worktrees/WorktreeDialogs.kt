package com.makefunstuff.herdr.ui.worktrees

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.makefunstuff.herdr.core.api.WorktreeErrors
import com.makefunstuff.herdr.core.api.WorktreeOpened
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.ui.common.toast
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Names a repository for herdr: by a workspace open in it, or by a folder inside it. */
data class RepoTarget(val workspaceId: String?, val cwd: String?, val trusted: Boolean = false)

/** A home-relative path, shorter on a phone screen. */
fun String.shortPath(): String = replace(Regex("^/(home|Users)/[^/]+"), "~")

/** Create a worktree of [repo], opened as a new workspace. [branches] are offered as starting points. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateWorktreeSheet(
    session: HostSession,
    repoName: String,
    repo: RepoTarget,
    branches: List<String>,
    onDismiss: () -> Unit,
    onCreated: (WorktreeOpened) -> Unit = {},
    onNeedsTrust: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var branch by remember { mutableStateOf("") }
    var base by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("New worktree of $repoName", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                branch, { branch = it.filterNot(Char::isWhitespace) },
                label = { Text("New branch") },
                placeholder = { Text("herdr picks a name if empty") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (branches.isNotEmpty()) {
                Text("Start from", style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(branches) { b -> FilterChip(selected = b == base, onClick = { base = if (base == b) "" else b }, label = { Text(b) }) }
                }
            }
            OutlinedTextField(
                base, { base = it.trim() },
                label = { Text("Base (branch, tag or commit, optional)") },
                placeholder = { Text("the current checkout") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(label, { label = it }, label = { Text("Workspace name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                path, { path = it.trim() },
                label = { Text("Folder (absolute path, optional)") },
                placeholder = { Text("herdr's worktree folder") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    busy = true
                    scope.launch {
                        runCatching {
                            session.awaitApi().createWorktree(
                                workspaceId = repo.workspaceId,
                                cwd = repo.cwd,
                                branch = branch.ifBlank { null },
                                base = base.ifBlank { null },
                                path = path.ifBlank { null },
                                label = label.ifBlank { null },
                                trustRepository = repo.trusted,
                            )
                        }.onSuccess {
                            session.refresh()
                            context.toast("Created ${it.worktree.title} as workspace ${it.workspace.label.ifBlank { it.workspace.workspaceId }}")
                            onCreated(it)
                            onDismiss()
                        }.onFailure {
                            if (WorktreeErrors.needsTrust(it)) onNeedsTrust() else context.toast(WorktreeErrors.describe(it))
                        }
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "Creating…" else "Create worktree") }
        }
    }
}

/** What to remove: a checkout, and the workspace it's open in when it is. */
data class RemoveRequest(val title: String, val path: String, val workspaceId: String?, val repo: RepoTarget)

/**
 * Removes a linked worktree after the user confirms, and again before throwing away uncommitted
 * changes. herdr removes worktrees by workspace, so a checkout that isn't open is opened first,
 * and closed again if the removal doesn't go ahead.
 */
@Composable
fun RemoveWorktreeFlow(session: HostSession, request: RemoveRequest, onDone: (removed: Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    // Set once herdr refused because of changes: the workspace to force, and whether we opened it.
    var dirty by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    suspend fun closeIfOpened(workspaceId: String, openedHere: Boolean) {
        if (openedHere) withContext(NonCancellable) { runCatching { session.awaitApi().closeWorkspace(workspaceId) } }
    }

    fun remove(force: Boolean) {
        busy = true
        scope.launch {
            var workspaceId = dirty?.first ?: request.workspaceId
            var openedHere = dirty?.second ?: false
            val result = runCatching {
                val api = session.awaitApi()
                if (workspaceId == null) {
                    val opened = api.openWorktree(request.repo.workspaceId, request.repo.cwd, path = request.path, trustRepository = request.repo.trusted)
                    workspaceId = opened.workspace.workspaceId
                    openedHere = !opened.alreadyOpen
                }
                api.removeWorktree(workspaceId!!, force = force, trustRepository = request.repo.trusted)
            }
            busy = false
            result.onSuccess {
                session.refresh()
                context.toast("Removed ${request.title}")
                onDone(true)
            }.onFailure { e ->
                val ws = workspaceId
                if (WorktreeErrors.needsForce(e) && ws != null && !force) {
                    dirty = ws to openedHere
                } else {
                    if (ws != null) closeIfOpened(ws, openedHere)
                    session.refresh()
                    context.toast(WorktreeErrors.describe(e))
                    onDone(false)
                }
            }
        }
    }

    val pending = dirty
    if (pending == null) {
        AlertDialog(
            onDismissRequest = { if (!busy) onDone(false) },
            title = { Text("Remove ${request.title}?") },
            text = {
                Text(
                    buildString {
                        append("Deletes the checkout at ${request.path.shortPath()}")
                        append(if (request.workspaceId != null) " and closes its workspace" else "")
                        append(". The branch and its commits stay in the repository.")
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { remove(force = false) },
                    enabled = !busy,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(if (busy) "Removing…" else "Remove") }
            },
            dismissButton = { TextButton(onClick = { onDone(false) }, enabled = !busy) { Text("Cancel") } },
        )
    } else {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("${request.title} has changes") },
            text = { Text("It has modified or untracked files that aren't committed. Removing it anyway deletes them for good.") },
            confirmButton = {
                TextButton(
                    onClick = { remove(force = true) },
                    enabled = !busy,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(if (busy) "Removing…" else "Delete changes and remove") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            closeIfOpened(pending.first, pending.second)
                            session.refresh()
                            onDone(false)
                        }
                    },
                    enabled = !busy,
                ) { Text("Keep it") }
            },
        )
    }
}

/** Asks before passing herdr's per-request Git trust for a repository Git flagged as unsafe. */
@Composable
fun TrustRepositoryDialog(repoName: String, onTrust: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Trust $repoName?") },
        text = {
            Text(
                "Git won't work in this repository yet, usually because another user owns the folder. " +
                    "Only trust it if you know where it came from: its Git settings and hooks can run commands on the machine. " +
                    "Trust lasts until you leave this screen.",
            )
        },
        confirmButton = { TextButton(onClick = onTrust) { Text("Trust") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
