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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.api.HerdrApiException
import com.makefunstuff.herdr.core.chat.AgentChat
import com.makefunstuff.herdr.core.chat.ChatItem
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.toast
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.ChatComposer
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.design.RoundAction
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.common.StatusDot
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import com.makefunstuff.herdr.ui.manage.AgentActionsSheet
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import com.makefunstuff.herdr.ui.theme.color
import com.makefunstuff.herdr.ui.theme.title
import kotlinx.coroutines.launch

/**
 * An agent as a chat: its conversation, its state, its questions with their choices, and a box to
 * send the next prompt. The terminal is one tap away for anything the chat can't do.
 */
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
    var sending by remember { mutableStateOf(false) }
    var showKeys by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    fun keys(k: List<String>) {
        scope.launch {
            runCatching {
                val api = session.awaitApi()
                // agent.send_keys reaches agents herdr started itself; one an integration reported
                // answers "not an active named agent", so its keys go to the pane instead.
                val sent = if (agent != null) runCatching { api.agentSendKeys(paneId, k) } else null
                if (sent == null || sent.isFailure) api.sendKeys(paneId, k)
            }.onFailure { context.toast(it.message ?: "Couldn't send keys") }
            session.refresh()
        }
    }

    fun send() {
        val text = draft.trim()
        if (text.isEmpty() || sending) return
        sending = true
        val blocked = status == AgentStatus.Blocked
        // Counted before the send, so a transcript that is quicker than this call still confirms it.
        if (!blocked) chat.addPending(text)
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
            }.onFailure {
                if (!blocked) chat.dropPending(text)
                context.toast(it.message ?: "Couldn't send")
            }
            sending = false
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
            state.pending.forEachIndexed { i, p -> add(ChatRow.User(ChatItem.User("$i", p.text), pending = true)) }
        }
    }
    val listState = rememberLazyListState()
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex <= 1 } }
    // Stay pinned to the newest message unless the reader scrolled up.
    LaunchedEffect(rows.lastOrNull()?.key, rows.size, status) {
        if (atBottom) listState.animateScrollToItem(0)
    }

    Scaffold(
        containerColor = Herdr.colors.ground,
        topBar = {
            HerdrTopBar(
                agent?.let { it.name?.takeIf { n -> n.isNotBlank() } ?: it.displayName } ?: pane?.displayName ?: paneId,
                subtitle = listOfNotNull(
                    agent?.agent?.takeIf { it != agent.name },
                    host.machine.label,
                    snapshot?.let { s -> pane?.let { s.location(it.workspaceId, it.tabId) } },
                ).joinToString(" · "),
                onBack = { nav.popBackStack() },
            ) {
                QuietButton(onClick = { nav.navigate(Routes.terminal(machineId, paneId)) }) { Text("Terminal") }
                BarIcon(HerdrIcons.MoreVert, "More", { menu = true })
            }
        },
    ) { padding ->
        // The top bar's inset comes from the scaffold; the bottom follows the keyboard or the nav bar.
        Column(Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize().imePadding().navigationBarsPadding()) {
            StatusStrip(status, agent?.statusLabel, activity, online = host.online)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    agent == null && pane == null && snapshot != null ->
                        EmptyState("This agent is gone", "Its pane closed, or the agent exited.", icon = HerdrIcons.SmartToy)
                    rows.isEmpty() && state.source == AgentChat.Source.Loading ->
                        if (host.online) LoadingState("Loading the conversation", Modifier.align(Alignment.Center))
                        else EmptyState("Not connected", host.machine.label, icon = HerdrIcons.Dns)
                    rows.isEmpty() ->
                        EmptyState("No messages yet", "Send a prompt to get started.", icon = HerdrIcons.Forum)
                    else -> LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        contentPadding = PaddingValues(horizontal = Space.screen, vertical = Space.m),
                        verticalArrangement = Arrangement.spacedBy(Space.m),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        // The newest entry: the question the agent is waiting on, or what it is doing.
                        if (status == AgentStatus.Blocked) item(key = "blocked") { BlockedCard(state.prompt, state.screen, onKeys = ::keys) }
                        if (status == AgentStatus.Working) item(key = "working") { WorkingLine(activity) }
                        items(rows.asReversed(), key = { it.key }) { ChatRowView(it) }
                    }
                }
                if (!atBottom) {
                    Box(Modifier.align(Alignment.BottomEnd).padding(Space.m)) {
                        RoundAction(HerdrIcons.ArrowDownward, "Newest") { scope.launch { listState.animateScrollToItem(0) } }
                    }
                }
            }
            if (showKeys) KeyRow(onKeys = ::keys)
            ChatComposer(
                draft = draft,
                onDraft = { draft = it },
                placeholder = when {
                    status == AgentStatus.Blocked -> "Type an answer"
                    agent != null -> "Message ${agent.name?.takeIf { it.isNotBlank() } ?: agent.displayName}"
                    else -> "Type into the pane"
                },
                onSend = ::send,
                stop = if (status == AgentStatus.Working) ({ keys(listOf("esc")) }) else null,
            )
        }
    }

    if (menu) {
        AgentActionsSheet(
            session = session,
            agent = agent,
            paneLabel = pane?.displayName ?: paneId,
            onDismiss = { menu = false },
            onTerminal = { menu = false; nav.navigate(Routes.terminal(machineId, paneId)) },
            onToggleKeys = { menu = false; showKeys = !showKeys },
            showKeys = showKeys,
            onKey = { menu = false; keys(it) },
        )
    }
}

/** Under the bar: the agent's state in its colour, and what it is doing. */
@Composable
private fun StatusStrip(status: AgentStatus, label: String?, activity: String?, online: Boolean) {
    val c = Herdr.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(c.card).padding(horizontal = Space.screen, vertical = Space.s),
    ) {
        StatusDot(status.color())
        Spacer(Modifier.width(Space.s))
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
                color = c.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Spacer(Modifier.weight(1f))
        if (!online) Text("offline", style = MaterialTheme.typography.labelMedium, color = c.faint)
    }
}

@Composable
private fun KeyRow(onKeys: (List<String>) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.m),
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        listOf(
            "Enter" to "enter", "Esc" to "esc", "↑" to "up", "↓" to "down", "Tab" to "tab", "⇧Tab" to "shift+tab",
            "1" to "1", "2" to "2", "3" to "3", "y" to "y", "n" to "n", "Ctrl+C" to "ctrl+c",
        ).forEach { (label, key) -> KeyButton(label) { onKeys(listOf(key)) } }
    }
}
