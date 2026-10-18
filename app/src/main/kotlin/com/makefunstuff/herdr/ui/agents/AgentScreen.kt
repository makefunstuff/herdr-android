package com.makefunstuff.herdr.ui.agents

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import com.makefunstuff.herdr.core.chat.BlockedPrompt
import com.makefunstuff.herdr.core.chat.OptionRole
import com.makefunstuff.herdr.core.guard.Guarded
import com.makefunstuff.herdr.ui.common.LocalGuard
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import com.makefunstuff.herdr.ui.design.typingInShortWindow
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
import com.makefunstuff.herdr.ui.code.CodeSpot
import com.makefunstuff.herdr.ui.quick.QuickPlace
import com.makefunstuff.herdr.ui.quick.QuickSheets
import com.makefunstuff.herdr.bubble.BubbleChat
import com.makefunstuff.herdr.bubble.LocalBubble
import com.makefunstuff.herdr.ui.common.rememberFloat
import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.chat.AgentChat
import com.makefunstuff.herdr.core.chat.AgentInput
import com.makefunstuff.herdr.core.push.Alerts
import com.makefunstuff.herdr.core.chat.ChatItem
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.toast
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.ChatComposer
import com.makefunstuff.herdr.ui.design.ChatSurface
import com.makefunstuff.herdr.ui.design.ComposerIconButton
import com.makefunstuff.herdr.ui.common.rememberAttacher
import com.makefunstuff.herdr.ui.images.AttachMenu
import com.makefunstuff.herdr.ui.images.ChatImages
import com.makefunstuff.herdr.ui.images.ComposerTray
import com.makefunstuff.herdr.ui.images.LocalChatImages
import com.makefunstuff.herdr.ui.images.rememberImageTray
import androidx.compose.runtime.CompositionLocalProvider
import com.makefunstuff.herdr.core.host.Attachments
import com.makefunstuff.herdr.ui.common.rememberDraft
import com.makefunstuff.herdr.data.DraftStore
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
@OptIn(ExperimentalLayoutApi::class)
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

    val name = agent?.let { it.name?.takeIf { n -> n.isNotBlank() } ?: it.displayName } ?: pane?.displayName ?: paneId
    // Floating over other apps as a bubble, from here; in that bubble, back to the app.
    val bubbleChat = remember(machineId, paneId) { BubbleChat.Agent(machineId, paneId) }
    val float = rememberFloat(bubbleChat, name, host.machine.label)
    val inBubble = LocalBubble.current != null

    // The chat is open: its notification has done its job.
    LaunchedEffect(machineId, paneId) { container.inbox.dismiss(Alerts.agentTag(machineId, paneId)) }
    // A finish shown here is seen: the Agents screen no longer lists it as new.
    val finished = agent?.takeIf { it.agentStatus == AgentStatus.Done }?.let { it.completionSeq ?: it.stateChangeSeq }
    LaunchedEffect(machineId, paneId, finished) { finished?.let { container.seen.mark(machineId, paneId, it) } }

    val chat = remember(session, paneId) { AgentChat(session, paneId, scope) }
    DisposableEffect(chat) {
        chat.start()
        onDispose { chat.close() }
    }
    val state by chat.state.collectAsStateWithLifecycle()

    // Kept per chat across navigation, rotation and restarts; cleared only once the message is sent.
    var draft by rememberDraft(DraftStore.agent(machineId, paneId))
    var sending by remember { mutableStateOf(false) }
    var showKeys by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var quick by remember { mutableStateOf<QuickPlace?>(null) }
    val listState = rememberLazyListState()
    // Pictures for the agent wait in the composer (and are copied to its machine) until the message goes.
    val tray = rememberImageTray(DraftStore.agent(machineId, paneId), machineId, session, host.online)
    // A file for the agent: copied to its machine, then named at the start of the draft.
    val attach = rememberAttacher(session, onAttached = { path -> draft = Attachments.withMention(draft, path) }, onImage = tray::add)
    var attachMenu by remember { mutableStateOf(false) }

    fun keys(k: List<String>) {
        scope.launch {
            runCatching { AgentInput.keys(session.awaitApi(), paneId, k, named = agent != null) }.onFailure { context.toast(it.message ?: "Couldn't send keys") }
            session.refresh()
        }
    }

    // The question docked over the composer: an answer on its way, a "No, tell it…" being written,
    // the reply after Amend, and whether it stays open while the keyboard is up.
    val guard = LocalGuard.current
    val view = LocalView.current
    val composerFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var answering by remember { mutableStateOf(false) }
    var telling by remember { mutableStateOf<Telling?>(null) }
    var amending by remember { mutableStateOf(false) }
    var cardOpen by remember { mutableStateOf(false) }
    val prompt = state.prompt
    // A new question (or none): what was begun for the last one is over.
    LaunchedEffect(prompt?.question, status) {
        if (status != AgentStatus.Blocked || telling?.prompt?.same(prompt) == false) telling = null
        if (status != AgentStatus.Blocked) amending = false
        cardOpen = false
    }

    fun reply() {
        composerFocus.requestFocus()
        keyboard?.show()
    }

    /** Carries out [block] on the question shown, which it checks is still on the agent's screen. */
    fun answer(block: suspend (com.makefunstuff.herdr.core.api.HerdrApi) -> Unit) {
        if (answering) return
        answering = true
        // A light confirmation under the thumb.
        view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP)
        scope.launch {
            runCatching { block(session.awaitApi()) }.onFailure { context.toast(it.message ?: "Couldn't answer") }
            answering = false
            session.refresh()
        }
    }

    fun press(p: PromptPress) {
        val asked = prompt
        val named = agent != null
        when (p) {
            is PromptPress.Option -> {
                if (asked == null) return
                val choose = { answer { api -> AgentInput.choose(api, paneId, asked, p.index, named) } }
                // "Yes, and don't ask again" changes what the agent may do for good: the fingerprint first, when that is on.
                if (asked.role(p.index) == OptionRole.Always) guard.run(Guarded.AlwaysAllow, "${asked.shortLabel(p.index)}?", command(asked), choose)
                else choose()
            }
            is PromptPress.Tell -> if (asked != null) { telling = Telling(asked, p.index); reply() }
            is PromptPress.Ticks -> if (asked != null) answer { api -> AgentInput.tick(api, paneId, asked, p.indices, named) }
            is PromptPress.Key -> {
                keys(listOf(p.key))
                if (p.reply) { amending = true; reply() }
            }
            is PromptPress.Keys -> keys(p.keys)
        }
    }

    fun send() {
        val typed = draft
        val pictures = tray.images
        // The pictures are named in front of what you typed.
        val text = tray.message(typed)
        if (text.isEmpty() || sending || tray.waiting) return
        sending = true
        val blocked = status == AgentStatus.Blocked
        // What the words answer: "No, tell it…" (the no, then the words as the next prompt), the question's own
        // "Type something." row, or (after Amend, or for a question with neither) typed into its screen.
        val tell = telling?.takeIf { blocked }
        val own = prompt?.takeIf { blocked && tell == null && !amending && it.customIndex != null && !it.multiSelect }
        // Counted before the send, so a transcript that is quicker than this call still confirms it.
        if (!blocked || tell != null) chat.addPending(text)
        // What you just said is the newest line: bring the chat to its end, also if you had scrolled up.
        scope.launch { listState.animateScrollToItem(0) }
        scope.launch {
            runCatching {
                val api = session.awaitApi()
                when {
                    tell != null -> AgentInput.refuseAndTell(api, paneId, tell.prompt, tell.index, text, named = agent != null)
                    own != null -> AgentInput.typeAnswer(api, paneId, own, text, named = agent != null)
                    else -> AgentInput.send(api, paneId, text, named = agent != null, blocked = blocked)
                }
            }.onSuccess {
                draft = DraftStore.afterSend(draft, typed)
                tray.sent(pictures)
                telling = null
                amending = false
            }.onFailure {
                if (!blocked || tell != null) chat.dropPending(text)
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
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex <= 1 } }
    // Stay pinned to the newest message unless the reader scrolled up.
    LaunchedEffect(rows.lastOrNull()?.key, rows.size, status) {
        if (atBottom) listState.animateScrollToItem(0)
    }

    val typing = typingInShortWindow()
    val imeUp = WindowInsets.isImeVisible
    val guardState by container.guard.state.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = Herdr.colors.ground,
        topBar = {
            if (!typing) HerdrTopBar(
                name,
                subtitle = listOfNotNull(
                    agent?.agent?.takeIf { it != agent.name },
                    host.machine.label,
                    snapshot?.let { s -> pane?.let { s.location(it.workspaceId, it.tabId) } },
                ).joinToString(" · "),
                onBack = if (inBubble) null else ({ nav.popBackStack() }),
            ) {
                QuietButton(onClick = { nav.navigate(Routes.terminal(machineId, paneId)) }) { Text("Terminal") }
                BarIcon(HerdrIcons.Ellipsis, "More", { menu = true })
            }
        },
    ) { padding ->
        // The top bar's inset comes from the scaffold; the bottom follows the keyboard or the nav bar.
        Column(Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize().imePadding().navigationBarsPadding()) {
            if (!typing) StatusStrip(status, agent?.statusLabel, activity, online = host.online)
            ChatSurface(
                Modifier.weight(1f).fillMaxWidth(),
                composer = {
                    ChatComposer(
                        draft = draft,
                        onDraft = { draft = it },
                        placeholder = when {
                            status == AgentStatus.Blocked && telling != null -> "Tell it what to do instead…"
                            status == AgentStatus.Blocked && amending -> "Add to your answer…"
                            status == AgentStatus.Blocked && prompt?.customIndex != null && !prompt.multiSelect -> "Type your own answer…"
                            status == AgentStatus.Blocked -> "Type an answer…"
                            agent != null -> "Message ${agent.name?.takeIf { it.isNotBlank() } ?: agent.displayName}…"
                            else -> "Type into the pane…"
                        },
                        onSend = ::send,
                        sending = sending,
                        holdSend = attach.uploading || tray.waiting,
                        status = when {
                            sending -> "Sending…"
                            !host.online -> "Not connected"
                            attach.uploading -> "Uploading file…"
                            tray.waiting -> "Copying pictures…"
                            else -> null
                        },
                        leading = {
                            ComposerIconButton(HerdrIcons.Plus, "Attach files", enabled = !attach.uploading, onClick = { attachMenu = true })
                        },
                        attachments = { ComposerTray(tray) },
                        attached = !tray.isEmpty,
                        stop = if (status == AgentStatus.Working) ({ keys(listOf("esc")) }) else null,
                        floating = true,
                        focusRequester = composerFocus,
                        above = if (showKeys || status == AgentStatus.Blocked) ({
                            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                                if (status == AgentStatus.Blocked) {
                                    // Docked over the composer, so it can't scroll away; folded to a line while you write.
                                    val fold = telling != null || (imeUp && !cardOpen)
                                    if (fold) {
                                        PromptBar(
                                            prompt?.question ?: "Waiting for your answer",
                                            telling = telling != null,
                                            onOpen = { if (telling == null) cardOpen = true },
                                            onCancelTell = { telling = null },
                                        )
                                    } else {
                                        PromptCard(
                                            prompt, state.screen, answering = answering,
                                            alwaysGuarded = guardState.asks(Guarded.AlwaysAllow) && container.guard.unlock.canAsk(),
                                            onPress = ::press,
                                        )
                                    }
                                }
                                if (showKeys) KeyRow(onKeys = ::keys)
                            }
                        }) else null,
                    )
                },
            ) { bottom ->
                when {
                    agent == null && pane == null && snapshot != null -> Box(Modifier.padding(bottom = bottom)) {
                        EmptyState("This agent is gone", "Its pane closed, or the agent exited.", icon = HerdrIcons.Bot)
                    }
                    rows.isEmpty() && state.source == AgentChat.Source.Loading -> Box(Modifier.fillMaxSize().padding(bottom = bottom)) {
                        if (host.online) LoadingState("Loading the conversation", Modifier.align(Alignment.Center))
                        else EmptyState("Not connected", host.machine.label, icon = HerdrIcons.Monitor)
                    }
                    rows.isEmpty() -> Box(Modifier.padding(bottom = bottom)) {
                        EmptyState("No messages yet", "Send a prompt to get started.", icon = HerdrIcons.MessagesSquare)
                    }
                    else -> CompositionLocalProvider(LocalChatImages provides ChatImages(machineId, session, container.images, host.online)) { LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        // The composer floats over the list's end: its height stays free under the newest entry.
                        contentPadding = PaddingValues(start = Space.screen, end = Space.screen, top = Space.m, bottom = bottom + Space.xs),
                        verticalArrangement = Arrangement.spacedBy(Space.m, Alignment.Bottom),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        // The newest entry: what the agent is doing (its question is docked over the composer).
                        if (status == AgentStatus.Working) item(key = "working") { WorkingLine(activity) }
                        items(rows.asReversed(), key = { it.key }) { ChatRowView(it) }
                    } }
                }
                if (!atBottom) {
                    Box(Modifier.align(Alignment.BottomEnd).padding(end = Space.m, bottom = bottom + Space.xs)) {
                        RoundAction(HerdrIcons.ArrowDown, "Newest", primary = false) { scope.launch { listState.animateScrollToItem(0) } }
                    }
                }
            }
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
            onFloat = float?.let { f -> { menu = false; f() } },
            onOpenApp = if (inBubble) ({ menu = false; container.bubbles.openApp(bubbleChat) }) else null,
            onCode = (agent?.cwd ?: pane?.foregroundCwd ?: pane?.cwd)?.ifBlank { null }?.takeIf { !inBubble }?.let { dir ->
                { menu = false; nav.navigate(Routes.codeAt(CodeSpot(machineId, dir, pane = paneId))) }
            },
            onActivity = if (inBubble) null else ({ menu = false; nav.navigate(Routes.activity(machineId, pane = paneId)) }),
            onQuick = if (inBubble) null else ({
                menu = false
                val name = agent?.let { it.name?.takeIf { n -> n.isNotBlank() } ?: it.displayName }
                quick = QuickPlace(
                    machineId, (agent?.cwd ?: pane?.foregroundCwd ?: pane?.cwd).orEmpty(), label = name ?: pane?.displayName.orEmpty(),
                    workspaceId = agent?.workspaceId ?: pane?.workspaceId, pane = paneId, agentName = name,
                )
            }),
        )
    }
    quick?.let { place -> QuickSheets(nav, place, onDismiss = { quick = null }) }
    if (attachMenu) AttachMenu(attach, tray, online = host.online, onDismiss = { attachMenu = false })
}

/** The command a question is about, for the fingerprint's prompt: codex's `$ …` line, or the line under Claude's tool header. */
private fun command(p: BlockedPrompt): String =
    p.details.firstOrNull { it.startsWith("$ ") }
        ?: p.details.getOrNull(1)?.takeIf { p.details[0].split(' ').size <= 3 }
        ?: p.question

/** "No, tell it…" being written: the option to press once the words go, on this question. */
private class Telling(val prompt: BlockedPrompt, val index: Int)

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
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        listOf(
            "Enter" to "enter", "Esc" to "esc", "↑" to "up", "↓" to "down", "Tab" to "tab", "⇧Tab" to "shift+tab",
            "1" to "1", "2" to "2", "3" to "3", "y" to "y", "n" to "n", "Ctrl+C" to "ctrl+c",
        ).forEach { (label, key) -> KeyButton(label) { onKeys(listOf(key)) } }
    }
}
