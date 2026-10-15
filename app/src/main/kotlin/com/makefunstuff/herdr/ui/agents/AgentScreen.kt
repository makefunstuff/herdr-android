package com.makefunstuff.herdr.ui.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.Pill
import com.makefunstuff.herdr.ui.common.toast
import com.makefunstuff.herdr.ui.theme.color
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** An agent without the terminal: its recent output, quick keys, and a prompt box. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(nav: NavController, machineId: String, paneId: String) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = container.hosts.session(machineId)
    if (session == null) {
        EmptyState("Machine not found", "It may have been removed.")
        return
    }
    val state by session.state.collectAsStateWithLifecycle()
    val snapshot = state.snapshot
    val agent = snapshot?.agents?.firstOrNull { it.paneId == paneId }
    val pane = snapshot?.pane(paneId)
    val status = agent?.agentStatus ?: pane?.agentStatus ?: AgentStatus.Unknown
    var output by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    val scroll = rememberScrollState()

    // Refresh output while the screen is open; faster while the agent is working.
    LaunchedEffect(paneId, state.online) {
        while (true) {
            runCatching { output = session.awaitApi().readPane(paneId, source = "recent", lines = 120).text.trimEnd() }
            delay(if (status == AgentStatus.Working) 1500 else 3000)
        }
    }
    LaunchedEffect(output) { scroll.scrollTo(scroll.maxValue) }

    fun keys(vararg k: String) {
        scope.launch {
            runCatching { session.awaitApi().sendKeys(paneId, k.toList()) }
                .onFailure { context.toast(it.message ?: "Couldn't send keys") }
            session.refresh()
        }
    }

    fun send() {
        val text = draft.trim()
        if (text.isEmpty()) return
        scope.launch {
            runCatching {
                val api = session.awaitApi()
                if (agent != null) {
                    api.prompt(paneId, text)
                } else {
                    api.sendText(paneId, text)
                    api.sendKeys(paneId, listOf("enter"))
                }
            }.onSuccess { draft = "" }.onFailure { context.toast(it.message ?: "Couldn't send") }
            session.refresh()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                title = {
                    Column {
                        Text(
                            agent?.let { a -> a.displayName + (a.subtitle?.let { " · $it" } ?: "") } ?: pane?.displayName ?: paneId,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${state.machine.label} › " + (snapshot?.let { s -> pane?.let { s.location(it.workspaceId, it.tabId) } } ?: paneId),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { nav.navigate(Routes.terminal(machineId, paneId)) }) { Text("Terminal") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(agent?.statusLabel ?: status.wire, color = status.color())
                if (!state.online) Pill("offline · cached")
            }
            Text(
                output.ifEmpty { if (state.online) "Loading…" else "Not connected" },
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = Color(0xFFCFD8D3),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF0B0D0C), RoundedCornerShape(10.dp))
                    .verticalScroll(scroll)
                    .horizontalScroll(rememberScrollState())
                    .padding(10.dp),
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("1", "2", "3", "y", "n").forEach { k -> AssistChip(onClick = { keys(k) }, label = { Text(k) }) }
                AssistChip(onClick = { keys("enter") }, label = { Text("Enter") })
                AssistChip(onClick = { keys("esc") }, label = { Text("Esc") })
                AssistChip(onClick = { keys("shift+tab") }, label = { Text("⇧Tab") })
                AssistChip(onClick = { keys("ctrl+c") }, label = { Text("Ctrl+C") })
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text(if (agent != null) "Reply to ${agent.displayName}" else "Run a command") },
                    modifier = Modifier.weight(1f),
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    shape = RoundedCornerShape(24.dp),
                )
                FilledIconButton(onClick = { send() }, enabled = draft.isNotBlank(), modifier = Modifier.padding(start = 8.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send")
                }
            }
        }
    }
}
