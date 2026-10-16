package com.makefunstuff.herdr.ui.coordinator

import com.makefunstuff.herdr.ui.design.BarTitle
import com.makefunstuff.herdr.ui.common.MarkdownText
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.core.board.ChatLine
import com.makefunstuff.herdr.core.board.CoordinatorChat
import com.makefunstuff.herdr.core.chat.SentMatch
import com.makefunstuff.herdr.core.board.CoordinatorView
import com.makefunstuff.herdr.core.board.Said
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.Pill
import com.makefunstuff.herdr.ui.common.detail
import com.makefunstuff.herdr.ui.common.label
import com.makefunstuff.herdr.ui.common.toast
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
    var draft by rememberSaveable { mutableStateOf("") }
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
    LaunchedEffect(lines.size, lines.lastOrNull()?.text?.length) {
        if (lines.isNotEmpty()) list.animateScrollToItem(lines.size)
    }
    val coordinatorPane = state.snapshot?.let { s ->
        s.agents.firstOrNull { a -> s.tab(a.tabId)?.label == "Coordinator" }?.paneId
    }

    fun send(delegate: Boolean) {
        val text = draft.trim()
        if (text.isEmpty() || sending) return
        sending = true
        // Counted before the send, so a read that comes in while it runs can't be missed.
        val before = SentMatch.count(text, talk.filter { it.you }.map { it.text })
        val tracked = view != null
        if (tracked) { outbox = SentMatch.Pending(text, before); outboxSeen = false }
        scope.launch {
            runCatching { session.awaitBoard().send(text, delegate) }
                .onSuccess { msg ->
                    draft = ""
                    // With no Coordinator set up there, the board files the words as a task instead.
                    if (!tracked) context.toast(msg.ifEmpty { "Sent" })
                }
                .onFailure { if (tracked) outbox = null; context.toast(it.message ?: "Couldn't send") }
            sending = false
            readNow.trySend(Unit)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    BarTitle("Coordinator", "${state.machine.label} · " + (v?.let { stateWords(it) } ?: if (state.online) "work board" else state.status.label()))
                },
                actions = {
                    if (coordinatorPane != null) {
                        TextButton(onClick = { nav.navigate(Routes.terminal(machineId, coordinatorPane)) }) { Text("Terminal") }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            when {
                !state.online && v == null -> {
                    Box(Modifier.weight(1f)) { EmptyState(state.status.label(), state.status.detail() ?: "Waiting for ${state.machine.label}…") }
                }
                state.online && state.boardPath == null -> {
                    Box(Modifier.weight(1f)) { EmptyState("No work board here", "herdr-projects isn't installed on ${state.machine.label}.") }
                }
                !loaded -> Box(Modifier.weight(1f)) { EmptyState("Loading…", "Reading the board on ${state.machine.label}.") }
                else -> {
                    LazyColumn(
                        state = list,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (lines.isEmpty()) {
                            item {
                                Text(
                                    if (v == null) "No Coordinator is set up on ${host.ifEmpty { state.machine.label }}: what you send is added to the board as a task."
                                    else "Nothing said yet. Tell the Coordinator what needs doing and it turns it into tasks.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        }
                        itemsIndexed(lines, key = { i, l -> "$i/${l.you}" }) { _, line -> Bubble(line) }
                        item(key = "foot") { Foot(v, tooOld, problem, onTerminal = coordinatorPane?.let { p -> { nav.navigate(Routes.terminal(machineId, p)) } }) }
                    }
                }
            }
            Composer(
                draft = draft,
                onDraft = { draft = it },
                enabled = state.online && state.boardPath != null && !sending,
                hint = if (v == null && loaded) "Add a task on ${state.machine.label}" else "Message the Coordinator",
                onSend = { send(false) },
                onSendAndStart = { send(true) },
            )
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

@Composable
private fun Bubble(line: ChatLine) {
    val mine = line.you
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            Surface(
                color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(
                    topStart = 18.dp,
                    topEnd = 18.dp,
                    bottomStart = if (mine) 18.dp else 4.dp,
                    bottomEnd = if (mine) 4.dp else 18.dp,
                ),
                modifier = Modifier.widthIn(max = 320.dp).alpha(if (line.pending) 0.7f else 1f),
            ) {
                MarkdownText(line.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
            if (line.pending) {
                Text(
                    "Sent · waiting for the Coordinator",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, end = 6.dp),
                )
            }
        }
    }
}

/** Under the conversation: that it's working, that it waits for you on its screen, or what went wrong. */
@Composable
private fun Foot(v: CoordinatorView?, tooOld: Boolean, problem: String?, onTerminal: (() -> Unit)?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (v != null && (v.state == "working" || v.state == "starting" || v.queue > 0) && v.error.isEmpty()) Working()
        if (v?.state == "blocked") {
            Notice(
                "The Coordinator shows a dialog on its screen that waits for your answer: it is in herdr's tab \"Coordinator\".",
                Color(0xFFE2B64A),
                action = onTerminal?.let { "Open its terminal" to it },
            )
        }
        if (v != null && !v.readable) {
            Notice("Its program keeps no conversation the board can read: only its latest message shows here.", MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!v?.error.isNullOrEmpty()) Notice("The Coordinator could not be started: ${v?.error}", Color(0xFFEF7B70))
        if (tooOld) Notice("This machine's herdr-projects is too old to send its Coordinator. Update it there (herdr-projects update).", Color(0xFFE2B64A))
        if (problem != null) Notice(problem, Color(0xFFEF7B70))
    }
}

@Composable
private fun Working() {
    val t = rememberInfiniteTransition(label = "working")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "dots")
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
        Pill("Coordinator is working…", color = Color(0xFFE2B64A).copy(alpha = a))
    }
}

@Composable
private fun Notice(text: String, color: Color, action: Pair<String, () -> Unit>? = null) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(text, style = MaterialTheme.typography.bodySmall, color = color)
            action?.let { (label, go) -> TextButton(onClick = go) { Text(label) } }
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraft: (String) -> Unit,
    enabled: Boolean,
    hint: String,
    onSend: () -> Unit,
    onSendAndStart: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraft,
                    placeholder = { Text(hint) },
                    modifier = Modifier.weight(1f),
                    maxLines = 6,
                    shape = RoundedCornerShape(24.dp),
                )
                FilledIconButton(onClick = onSend, enabled = enabled && draft.isNotBlank(), modifier = Modifier.padding(start = 8.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send")
                }
            }
            if (draft.isNotBlank()) {
                // The board's Tab: what the Coordinator adds from this message gets an agent at once.
                TextButton(onClick = onSendAndStart, enabled = enabled, modifier = Modifier.align(Alignment.End)) {
                    Text("Send + start agents")
                }
            }
        }
    }
}
