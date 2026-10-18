package com.makefunstuff.herdr.ui.machines

import com.makefunstuff.herdr.ui.design.SegmentedPills
import com.makefunstuff.herdr.ui.design.FieldLabel
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import com.makefunstuff.herdr.ui.design.HerdrField
import com.makefunstuff.herdr.ui.design.HerdrChip
import com.makefunstuff.herdr.ui.design.PrimaryButton
import com.makefunstuff.herdr.ui.design.HerdrSheet
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.makefunstuff.herdr.core.api.SessionSnapshot
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.core.start.AgentLaunch
import com.makefunstuff.herdr.core.start.StartWork
import com.makefunstuff.herdr.ui.common.toast
import kotlinx.coroutines.launch

/**
 * Start an agent, or create a workspace, tab or split. An agent gets what it should do first, as
 * Start work asks it, and the agents offered are the ones the machine has installed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewSheet(session: HostSession, snap: SessionSnapshot, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(0) }
    var workspaceId by remember { mutableStateOf(snap.workspaces.firstOrNull { it.focused }?.workspaceId ?: snap.workspaces.firstOrNull()?.workspaceId) }
    // The agents installed there, as herdr finds their programs; the usual list when it can't say.
    val kinds by produceState(StartWork.KINDS, session.machine.id) {
        runCatching { StartWork.installed(session.awaitApi(10_000).integrations()) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { value = it }
    }
    var picked by remember { mutableStateOf<String?>(null) }
    val kind = picked?.takeIf { it in kinds } ?: kinds.first()
    var name by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var cwd by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun act(block: suspend () -> Unit) {
        busy = true
        scope.launch {
            runCatching { block() }
                .onSuccess { session.refresh(); onDismiss() }
                .onFailure { context.toast(it.message ?: "That didn't work") }
            busy = false
        }
    }

    // New panes go next to the workspace's focused pane in its active tab.
    fun anchorPane(): String? {
        val ws = snap.workspace(workspaceId ?: return null) ?: return null
        return snap.layout(ws.activeTabId)?.focusedPaneId?.ifEmpty { null }
            ?: snap.panes.firstOrNull { it.tabId == ws.activeTabId }?.paneId
    }

    HerdrSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Space.xl).padding(top = Space.xs, bottom = Space.l).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            val modes = listOf("Agent", "Workspace", "Tab", "Split")
            SegmentedPills(modes, mode, { mode = it })
            if (mode != 1 && snap.workspaces.isNotEmpty()) {
                FieldLabel("Workspace")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    items(snap.workspaces, key = { it.workspaceId }) { ws ->
                        HerdrChip(
                            selected = ws.workspaceId == workspaceId,
                            onClick = { workspaceId = ws.workspaceId },
                            label = { Text(ws.label.ifBlank { ws.workspaceId }) },
                        )
                    }
                }
            }
            when (mode) {
                0 -> {
                    HerdrField(
                        prompt, { prompt = it }, label = "What should it do?", minLines = 2, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Optional: its first message") },
                    )
                    FieldLabel("Agent")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        items(kinds) { k -> HerdrChip(selected = k == kind, onClick = { picked = k }, label = { Text(k) }) }
                    }
                    HerdrField(
                        value = name,
                        onValueChange = { name = it.filter { c -> c.isLetterOrDigit() || c == '-' || c == '_' } },
                        label = "Name",
                        // The name it gets when none is typed: its first words.
                        placeholder = { Text(StartWork.agentName(prompt, kind, snap.agents.mapNotNull { it.name })) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Starts in a new split next to the workspace's focused pane.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Herdr.colors.muted,
                    )
                    PrimaryButton(
                        onClick = {
                            val pane = anchorPane() ?: return@PrimaryButton context.toast("Pick a workspace first")
                            val taken = snap.agents.mapNotNull { it.name }
                            val n = name.ifBlank { StartWork.agentName(prompt, kind, taken) }
                            act { AgentLaunch.beside(session.awaitApi(), pane, kind, n, prompt) }
                        },
                        enabled = !busy && workspaceId != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (busy) "Starting…" else "Start $kind") }
                }
                1 -> {
                    HerdrField(label, { label = it }, label = "Name", singleLine = true, modifier = Modifier.fillMaxWidth())
                    HerdrField(
                        cwd, { cwd = it },
                        label = "Folder (absolute path, optional)",
                        placeholder = { Text("/home/you/src/project") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PrimaryButton(
                        onClick = { act { session.awaitApi().createWorkspace(label.ifBlank { null }, cwd.ifBlank { null }) } },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Create workspace") }
                }
                2 -> {
                    HerdrField(label, { label = it }, label = "Tab name", singleLine = true, modifier = Modifier.fillMaxWidth())
                    PrimaryButton(
                        onClick = { act { session.awaitApi().createTab(workspaceId!!, label.ifBlank { null }) } },
                        enabled = !busy && workspaceId != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Create tab") }
                }
                else -> {
                    Text(
                        "Splits the workspace's focused pane and opens a shell.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Herdr.colors.muted,
                    )
                    listOf("right" to "Split right", "down" to "Split down").forEach { (dir, text) ->
                        PrimaryButton(
                            onClick = {
                                val pane = anchorPane() ?: return@PrimaryButton context.toast("Pick a workspace first")
                                act { session.awaitApi().splitPane(pane, dir) }
                            },
                            enabled = !busy && workspaceId != null,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(text) }
                    }
                }
            }
        }
    }
}
