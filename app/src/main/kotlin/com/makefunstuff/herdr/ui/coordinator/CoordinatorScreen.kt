package com.makefunstuff.herdr.ui.coordinator

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.core.board.ChatLine
import com.makefunstuff.herdr.core.board.CoordinatorChat
import com.makefunstuff.herdr.core.board.CoordinatorView
import com.makefunstuff.herdr.core.board.Said
import com.makefunstuff.herdr.core.chat.SentMatch
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.MarkdownText
import com.makefunstuff.herdr.ui.common.StatusDot
import com.makefunstuff.herdr.ui.common.detail
import com.makefunstuff.herdr.ui.common.label
import com.makefunstuff.herdr.ui.common.toast
import com.makefunstuff.herdr.ui.design.BubbleShape
import com.makefunstuff.herdr.ui.design.ReplyBubbleShape
import com.makefunstuff.herdr.ui.design.ChatComposer
import com.makefunstuff.herdr.ui.design.ChatSurface
import com.makefunstuff.herdr.ui.design.typingInShortWindow
import com.makefunstuff.herdr.ui.design.ComposerToggle
import com.makefunstuff.herdr.ui.common.rememberDraft
import com.makefunstuff.herdr.data.DraftStore
import androidx.compose.foundation.layout.navigationBarsPadding
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.design.StateBlock
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import com.makefunstuff.herdr.ui.theme.color
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The work board's Coordinator on one machine, as a chat. What it shows and what it sends go the
 * way another machine's board reaches it (`herdr-projects remote list|dispatch`), so the phone and
 * the board's own box share one conversation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoordinatorScreen(nav: NavController, machineId: String) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = container.hosts.session(machineId)
    if (session == null) {
        EmptyState("Machine not found", "It may have been removed.")
        return
    }
    val state by session.state.collectAsStateWithLifecycle()
    var view by remember { mutableStateOf<CoordinatorView?>(null) }
    var talk by remember { mutableStateOf<List<Said>>(emptyList()) }
    var problem by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var tooOld by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf("") }
    /** Sent from here, not yet seen in what the board answers. */
    var outbox by remember { mutableStateOf<SentMatch.Pending?>(null) }
    var outboxSeen by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    // Kept per machine across navigation, rotation and restarts; cleared only once the message is sent.
    var draft by rememberDraft(DraftStore.coordinator(machineId))
    /** The board's Tab for the next message: what the Coordinator adds from it gets an agent at once. */
    var startAgents by rememberSaveable { mutableStateOf(false) }
    val readNow = remember { Channel<Unit>(Channel.CONFLATED) }
    val list = rememberLazyListState()

    // Read while the screen is open: often while the Coordinator works, so its answer comes in as it's written.
    LaunchedEffect(machineId, state.online, state.boardPath) {
        if (!state.online || state.boardPath == null) return@LaunchedEffect
        while (true) {
            try {
                val answer = session.awaitBoard().list()
                host = answer.host
                tooOld = answer.tooOld
                val v = answer.coordinator
                view = v
                if (v != null) {
                    talk = CoordinatorChat.merge(talk, v.talk)
                    val mine = outbox
                    if (mine != null) {
                        if (v.sent.isNotEmpty()) outboxSeen = true
                        val said = talk.filter { it.you }.map { it.text }
                        // Confirmed by one more matching message than before it was sent; or the board took it
                        // in and has since answered, though its conversation doesn't hold it.
                        if (SentMatch.count(mine.text, said) > mine.baseline || (outboxSeen && v.sent.isEmpty())) outbox = null
                    }
                }
                problem = null
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                problem = t.message ?: "Couldn't read the board"
            }
            loaded = true
            val busy = view?.busy == true || outbox != null
            withTimeoutOrNull(if (busy) 1200L else 4000L) { readNow.receive() }
        }
    }

    val v = view
    val lines = remember(v, talk, outbox) {
        val mine = outbox
        val base = v?.let { CoordinatorChat.lines(it, talk, mine?.takeIf { m -> m.text.trim() == it.sent.trim() }?.baseline) }.orEmpty()
        if (mine != null && SentMatch.count(mine.text, base.filter { it.you }.map { it.text }) <= mine.baseline) base + ChatLine(true, mine.text, pending = true) else base
    }
    // The list is reversed, so it stays at its end by itself (new lines, the keyboard, a growing
    // composer); this brings a reader who is just above the end back down to a new answer.
    LaunchedEffect(lines.size, lines.lastOrNull()?.text?.length) {
        if (lines.isNotEmpty() && list.firstVisibleItemIndex <= 1) list.animateScrollToItem(0)
    }
    val coordinatorPane = state.snapshot?.let { s ->
        s.agents.firstOrNull { a -> s.tab(a.tabId)?.label == "Coordinator" }?.paneId
    }

    fun send(delegate: Boolean) {
        val typed = draft
        val text = typed.trim()
        if (text.isEmpty() || sending) return
        sending = true
        // Counted before the send, so a read that comes in while it runs can't be missed.
        val before = SentMatch.count(text, talk.filter { it.you }.map { it.text })
        val tracked = view != null
        if (tracked) { outbox = SentMatch.Pending(text, before); outboxSeen = false }
        scope.launch { list.animateScrollToItem(0) }
        scope.launch {
            runCatching { session.awaitBoard().send(text, delegate) }
                .onSuccess { msg ->
                    draft = DraftStore.afterSend(draft, typed)
                    startAgents = false
                    // With no Coordinator set up there, the board files the words as a task instead.
                    if (!tracked) context.toast(msg.ifEmpty { "Sent" })
                }
                .onFailure { if (tracked) outbox = null; context.toast(it.message ?: "Couldn't send") }
            sending = false
            readNow.trySend(Unit)
        }
    }

    val typing = typingInShortWindow()
    Scaffold(
        containerColor = Herdr.colors.ground,
        topBar = {
            if (!typing) HerdrTopBar(
                "Coordinator",
                subtitle = "${state.machine.label} · " + (v?.let { stateWords(it) } ?: if (state.online) "work board" else state.status.label()),
                onBack = { nav.popBackStack() },
            ) {
                if (coordinatorPane != null) QuietButton(onClick = { nav.navigate(Routes.terminal(machineId, coordinatorPane)) }) { Text("Terminal") }
            }
        },
    ) { padding ->
        // The top bar's inset comes from the scaffold; the bottom follows the keyboard or the nav bar.
        Column(Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize().imePadding().navigationBarsPadding()) {
            ChatSurface(
                Modifier.weight(1f).fillMaxWidth(),
                composer = {
                    CoordinatorComposer(
                        draft = draft,
                        onDraft = { draft = it },
                        enabled = state.online && state.boardPath != null,
                        sending = sending,
                        hint = if (v == null && loaded) "Add a task on ${state.machine.label}…" else "Tell the Coordinator…",
                        startAgents = startAgents,
                        onStartAgents = { startAgents = it },
                        status = when {
                            sending -> "Sending…"
                            !state.online -> "Not connected"
                            else -> null
                        },
                        onSend = ::send,
                    )
                },
            ) { bottom ->
                when {
                    !state.online && v == null -> Box(Modifier.padding(bottom = bottom)) {
                        ChatEmpty(HerdrIcons.Monitor, state.status.label(), state.status.detail() ?: "Waiting for ${state.machine.label}…")
                    }
                    state.online && state.boardPath == null -> Box(Modifier.padding(bottom = bottom)) {
                        ChatEmpty(HerdrIcons.SquareKanban, "No work board here", "herdr-projects isn't installed on ${state.machine.label}.")
                    }
                    !loaded -> Box(Modifier.fillMaxSize().padding(bottom = bottom), contentAlignment = Alignment.Center) {
                        LoadingState("Reading the board")
                    }
                    else -> LazyColumn(
                        state = list,
                        // Newest at the bottom, as the agent's chat: it stays at its end when the keyboard opens
                        // or the message box grows.
                        reverseLayout = true,
                        modifier = Modifier.fillMaxSize(),
                        // The composer floats over the list's end: its height stays free under the newest line.
                        contentPadding = PaddingValues(start = Space.screen, end = Space.screen, top = Space.m, bottom = bottom + Space.xs),
                        verticalArrangement = Arrangement.spacedBy(Space.m, Alignment.Bottom),
                    ) {
                        val foot: @Composable () -> Unit = {
                            Foot(v, tooOld, problem, onTerminal = coordinatorPane?.let { p -> { nav.navigate(Routes.terminal(machineId, p)) } })
                        }
                        if (lines.isEmpty()) {
                            item(key = "empty") {
                                Column(Modifier.fillParentMaxSize()) {
                                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        if (v == null) {
                                            ChatEmpty(
                                                HerdrIcons.MessagesSquare,
                                                "No Coordinator here",
                                                "Nothing on ${host.ifEmpty { state.machine.label }} answers as the Coordinator, so what you send is added to the board as a task.",
                                            )
                                        } else {
                                            ChatEmpty(
                                                HerdrIcons.MessagesSquare,
                                                "Nothing said yet",
                                                "Tell the Coordinator what needs doing and it turns it into tasks.",
                                            )
                                        }
                                    }
                                    foot()
                                }
                            }
                        } else {
                            item(key = "foot") { foot() }
                            val last = lines.lastIndex
                            itemsIndexed(lines.asReversed(), key = { i, l -> "${last - i}/${l.you}" }) { _, line -> Bubble(line) }
                        }
                    }
                }
            }
        }
    }
}

private fun stateWords(v: CoordinatorView): String = when {
    v.error.isNotEmpty() -> "could not start"
    v.queue > 0 && v.state != "working" -> "starting"
    else -> when (v.state) {
        "working" -> "working"
        "starting" -> "starting"
        "blocked" -> "waits for you"
        "idle" -> "idle"
        "exited" -> "stopped · your next message starts it"
        else -> "not running · your next message starts it"
    }
}

/** The screen's own empty state: an icon, a heading and a sentence, centred in the space. */
@Composable
private fun ChatEmpty(icon: ImageVector, title: String, body: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { StateBlock(icon, title, body) }
}

/** Yours on the right on a neutral raised card; the Coordinator's answer as plain prose on the left, as the web UI's chat. */
@Composable
private fun Bubble(line: ChatLine) {
    val mine = line.you
    val c = Herdr.colors
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(start = if (mine) 48.dp else 0.dp, end = if (mine) 0.dp else 32.dp), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            Surface(
                color = if (mine) c.card else Color.Transparent,
                contentColor = c.text,
                shape = if (mine) BubbleShape else ReplyBubbleShape,
                border = if (mine && c.bubbleBorder.alpha > 0f) androidx.compose.foundation.BorderStroke(1.dp, c.bubbleBorder) else null,
                modifier = Modifier.alpha(if (line.pending) 0.72f else 1f),
            ) {
                MarkdownText(
                    line.text,
                    Modifier.padding(horizontal = if (mine) Space.l else Space.xs, vertical = if (mine) Space.m else Space.xs),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (line.pending) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = Space.xs, end = Space.xs),
                ) {
                    StatusDot(c.faint, size = 6)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Sending · waiting for the Coordinator",
                        style = MaterialTheme.typography.labelMedium,
                        color = c.faint,
                    )
                }
            }
        }
    }
}

/** Under the conversation: that it's working, that it waits for you on its screen, or what went wrong. */
@Composable
private fun Foot(v: CoordinatorView?, tooOld: Boolean, problem: String?, onTerminal: (() -> Unit)?) {
    val c = Herdr.colors
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        if (v != null && (v.state == "working" || v.state == "starting" || v.queue > 0) && v.error.isEmpty()) Working()
        if (v?.state == "blocked") {
            Notice(
                "The Coordinator shows a dialog on its screen that waits for your answer. It is in herdr's \"Coordinator\" tab.",
                c.notice,
                action = onTerminal?.let { "Open its terminal" to it },
            )
        }
        if (v != null && !v.readable) {
            Notice("Its program keeps no conversation the board can read, so only its latest message shows here.", null)
        }
        if (!v?.error.isNullOrEmpty()) Notice("The Coordinator could not be started: ${v?.error}", c.red)
        if (tooOld) Notice("This machine's herdr-projects is too old to send its Coordinator. Update it there (herdr-projects update).", c.notice)
        if (problem != null) Notice(problem, c.red)
    }
}

@Composable
private fun Working() {
    val t = rememberInfiniteTransition(label = "working")
    val a by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "dots")
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = Space.xs)) {
        StatusDot(Herdr.colors.working, Modifier.alpha(a), size = 8)
        Spacer(Modifier.width(Space.s))
        Text("Coordinator is working…", style = MaterialTheme.typography.bodySmall, color = Herdr.colors.muted)
    }
}

/** A plain sentence on a tinted tile, in place of a raw error dump; [tint] null is neutral. */
@Composable
private fun Notice(text: String, tint: Color?, action: Pair<String, () -> Unit>? = null) {
    Column {
        com.makefunstuff.herdr.ui.design.Notice(text, color = tint, icon = if (tint == Herdr.colors.red) HerdrIcons.CircleAlert else HerdrIcons.Info)
        if (action != null) QuietButton(onClick = action.second, color = Herdr.colors.accent) { Text(action.first) }
    }
}

/**
 * The Coordinator's composer: the shared [ChatComposer], with the board's Tab as a "Start agents"
 * switch beside Send. Off, Send hands the message to the Coordinator; on (amber), Send also gives
 * an agent to every task the Coordinator adds from it, and Send says so to a screen reader. It
 * turns itself off again once that message is sent.
 */
@Composable
internal fun CoordinatorComposer(
    draft: String,
    onDraft: (String) -> Unit,
    enabled: Boolean,
    hint: String,
    startAgents: Boolean,
    onStartAgents: (Boolean) -> Unit,
    onSend: (startAgents: Boolean) -> Unit,
    sending: Boolean = false,
    status: String? = null,
) {
    ChatComposer(
        draft = draft,
        onDraft = onDraft,
        placeholder = hint,
        onSend = { onSend(startAgents) },
        enabled = enabled,
        sending = sending,
        status = status,
        sendLabel = if (startAgents) "Send and start agents" else "Send",
        floating = true,
        trailing = {
            ComposerToggle("Start agents", HerdrIcons.Zap, startAgents, onStartAgents, enabled = enabled && !sending)
        },
    )
}
