package com.makefunstuff.herdr.ui.agents

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.api.HerdrApiException
import com.makefunstuff.herdr.core.chat.AgentChat
import com.makefunstuff.herdr.core.chat.ChatItem
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.StatusDot
import com.makefunstuff.herdr.ui.common.toast
import com.makefunstuff.herdr.ui.theme.color
import com.makefunstuff.herdr.ui.theme.title
import kotlinx.coroutines.launch

/**
 * An agent as a chat: its conversation, its state, its questions with their choices, and a box to
 * send the next prompt. The terminal is one tap away for anything the chat can't do.
 */
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
    val host by session.state.collectAsStateWithLifecycle()
    val snapshot = host.snapshot
    val agent = snapshot?.agents?.firstOrNull { it.paneId == paneId }
    val pane = snapshot?.pane(paneId)
    val status = agent?.agentStatus ?: pane?.agentStatus ?: AgentStatus.Unknown

    // What the agent is working on: herdr's title for it, or the title the agent gives its terminal.
    val activity = agent?.let { it.title?.takeIf { t -> t.isNotBlank() } ?: it.terminalTitle?.takeIf { t -> t.isNotBlank() } }

    val chat = remember(session, paneId) { AgentChat(session, paneId, scope) }
    DisposableEffect(chat) {
        chat.start()
        onDispose { chat.close() }
    }
    val state by chat.state.collectAsStateWithLifecycle()

    var draft by rememberSaveable { mutableStateOf("") }
    var showKeys by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    fun keys(k: List<String>) {
        scope.launch {
            runCatching { session.awaitApi().sendKeys(paneId, k) }
                .onFailure { context.toast(it.message ?: "Couldn't send keys") }
            session.refresh()
        }
    }

    fun send() {
        val text = draft.trim()
        if (text.isEmpty()) return
        val blocked = status == AgentStatus.Blocked
        scope.launch {
            runCatching {
                val api = session.awaitApi()
                suspend fun typeIt() {
                    api.sendText(paneId, text)
                    api.sendKeys(paneId, listOf("enter"))
                }
                if (agent == null || blocked) {
                    typeIt()
                } else {
                    try {
                        api.prompt(paneId, text)
                    } catch (e: HerdrApiException) {
                        // It became blocked between the snapshot and the send: answer the question instead.
                        if (e.code == "agent_blocked") typeIt() else throw e
                    }
                }
            }.onSuccess {
                draft = ""
                if (!blocked) chat.addPending(text)
            }.onFailure { context.toast(it.message ?: "Couldn't send") }
            session.refresh()
        }
    }

    val rows = remember(state.items, state.pending, state.source, state.screen, state.truncated) {
        buildList {
            if (state.truncated) add(ChatRow.Info("truncated", "Earlier messages aren't loaded"))
            when (state.source) {
                AgentChat.Source.Transcript -> addAll(chatRows(state.items))
                AgentChat.Source.Screen -> {
                    add(ChatRow.Info("screen-note", "No transcript for this agent, so this is its screen"))
                    // TUIs pad with blank rows; one blank line is enough on a phone.
                    val screen = state.screen.lines().takeLast(200).joinToString("\n").replace(Regex("\n\\s*\n(\\s*\n)+"), "\n\n")
                    if (screen.isNotBlank()) add(ChatRow.Screen(screen))
                }
                AgentChat.Source.Loading -> {}
            }
            state.pending.forEachIndexed { i, p -> add(ChatRow.User(ChatItem.User("$i", p), pending = true)) }
        }
    }
    val listState = rememberLazyListState()
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex <= 1 } }
    // Stay pinned to the newest message unless the reader scrolled up.
    LaunchedEffect(rows.lastOrNull()?.key, rows.size, status) {
        if (atBottom) listState.animateScrollToItem(0)
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
                            agent?.let { it.name?.takeIf { n -> n.isNotBlank() } ?: it.displayName } ?: pane?.displayName ?: paneId,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOfNotNull(
                                agent?.agent?.takeIf { it != agent.name },
                                host.machine.label,
                                snapshot?.let { s -> pane?.let { s.location(it.workspaceId, it.tabId) } },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { nav.navigate(Routes.terminal(machineId, paneId)) }) { Text("Terminal") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(if (showKeys) "Hide keys" else "Show keys") }, onClick = { menu = false; showKeys = !showKeys })
                        DropdownMenuItem(text = { Text("Interrupt (Esc)") }, onClick = { menu = false; keys(listOf("esc")) })
                        DropdownMenuItem(text = { Text("Ctrl+C") }, onClick = { menu = false; keys(listOf("ctrl+c")) })
                    }
                },
            )
        },
    ) { padding ->
        // The top bar's inset comes from the scaffold; the bottom follows the keyboard or the nav bar.
        Column(Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize().imePadding().navigationBarsPadding()) {
            StatusStrip(status, agent?.statusLabel, activity, online = host.online)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    agent == null && pane == null && snapshot != null ->
                        EmptyState("This agent is gone", "Its pane closed, or the agent exited.")
                    rows.isEmpty() && state.source == AgentChat.Source.Loading ->
                        EmptyState(if (host.online) "Loading the conversation…" else "Not connected", host.machine.label)
                    rows.isEmpty() ->
                        EmptyState("No messages yet", "Send a prompt to get started.")
                    else -> LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        // The newest entry: the question the agent is waiting on, or what it is doing.
                        if (status == AgentStatus.Blocked) item(key = "blocked") { BlockedCard(state.prompt, state.screen, onKeys = ::keys) }
                        if (status == AgentStatus.Working) item(key = "working") { WorkingLine(activity) }
                        items(rows.asReversed(), key = { it.key }) { ChatRowView(it) }
                    }
                }
                if (!atBottom) {
                    SmallFloatingActionButton(
                        onClick = { scope.launch { listState.animateScrollToItem(0) } },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                    ) { Icon(Icons.Filled.KeyboardArrowDown, "Newest") }
                }
            }
            if (showKeys) KeyRow(onKeys = ::keys)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            InputBar(
                draft = draft,
                onDraft = { draft = it },
                placeholder = when {
                    status == AgentStatus.Blocked -> "Type an answer"
                    agent != null -> "Message ${agent.name?.takeIf { it.isNotBlank() } ?: agent.displayName}"
                    else -> "Type into the pane"
                },
                working = status == AgentStatus.Working,
                onSend = ::send,
                onStop = { keys(listOf("esc")) },
            )
        }
    }
}

@Composable
private fun StatusStrip(status: AgentStatus, label: String?, activity: String?, online: Boolean) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            StatusDot(status.color())
            Spacer(Modifier.width(10.dp))
            Text(
                when (status) {
                    AgentStatus.Blocked -> "Waiting for you"
                    AgentStatus.Unknown -> label?.takeIf { it != "unknown" } ?: "Status unknown"
                    else -> status.title()
                },
                style = MaterialTheme.typography.labelLarge,
                color = status.color(),
            )
            if (activity != null && status != AgentStatus.Blocked) {
                Text(
                    " · $activity",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Spacer(Modifier.weight(1f))
            if (!online) Text("offline", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun KeyRow(onKeys: (List<String>) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf(
            "Enter" to "enter", "Esc" to "esc", "↑" to "up", "↓" to "down", "Tab" to "tab", "⇧Tab" to "shift+tab",
            "1" to "1", "2" to "2", "3" to "3", "y" to "y", "n" to "n", "Ctrl+C" to "ctrl+c",
        ).forEach { (label, key) -> KeyButton(label) { onKeys(listOf(key)) } }
    }
}

@Composable
private fun InputBar(
    draft: String,
    onDraft: (String) -> Unit,
    placeholder: String,
    working: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraft,
            placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            modifier = Modifier.weight(1f),
            maxLines = 6,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            shape = RoundedCornerShape(24.dp),
        )
        if (working && draft.isBlank()) {
            FilledTonalButton(onClick = onStop, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)) { Text("Stop") }
        } else {
            FilledIconButton(onClick = onSend, enabled = draft.isNotBlank(), modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send")
            }
        }
    }
}
