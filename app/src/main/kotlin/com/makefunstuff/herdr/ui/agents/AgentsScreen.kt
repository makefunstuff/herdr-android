package com.makefunstuff.herdr.ui.agents

import com.makefunstuff.herdr.core.chat.OptionRole
import com.makefunstuff.herdr.core.guard.Guarded

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.makefunstuff.herdr.LocalPanes
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.core.board.Approvals
import com.makefunstuff.herdr.core.board.BoardAction
import com.makefunstuff.herdr.core.chat.AgentInput
import com.makefunstuff.herdr.core.chat.BlockedPrompt
import com.makefunstuff.herdr.core.home.AgentRow
import com.makefunstuff.herdr.core.home.Home
import com.makefunstuff.herdr.core.home.HomeAgent
import com.makefunstuff.herdr.core.home.Waiting
import com.makefunstuff.herdr.core.push.Alerts
import com.makefunstuff.herdr.ui.board.BoardPanel
import com.makefunstuff.herdr.ui.board.PanelCancel
import com.makefunstuff.herdr.ui.board.ReadBoards
import com.makefunstuff.herdr.ui.board.rememberBoardProblems
import com.makefunstuff.herdr.ui.board.rememberBoards
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.MarkdownText
import com.makefunstuff.herdr.ui.common.StatusDot
import com.makefunstuff.herdr.ui.common.toast
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.ErrorMessage
import com.makefunstuff.herdr.ui.design.HerdrFab
import com.makefunstuff.herdr.ui.design.HerdrField
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrMenu
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.MenuRow
import com.makefunstuff.herdr.ui.design.PrimaryButton
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.design.SectionHeading
import com.makefunstuff.herdr.ui.manage.AgentActionsSheet
import com.makefunstuff.herdr.ui.manage.NoticeRow
import com.makefunstuff.herdr.ui.manage.NoticesSheet
import com.makefunstuff.herdr.ui.share.Ask
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Rows a resting section shows before "more". */
private const val FOLDED = 4

/** A card as it stood when it was answered here: it stays hidden until this changes. */
private fun sig(w: Waiting): String = when (w) {
    is Waiting.Question -> "${w.agent.agent.stateChangeSeq}"
    is Waiting.Task -> w.task.let { t -> listOf(t.state, t.stage, t.kind, t.msg, t.reviewRound, t.reviewPassed, t.approvalBlocked).joinToString("|") }
}

/** What is being said back from a card: an answer to an agent's question, or words for a task's agent. */
private sealed interface Reply {
    val item: Waiting
    data class ToAgent(override val item: Waiting.Question) : Reply
    data class ToTask(override val item: Waiting.Task) : Reply
}

/** What the board or the machine said no with; [review] when it refused a plain approve, whose review card can approve anyway. */
private data class Refusal(val words: String, val review: Waiting.Task? = null)

/**
 * The home screen: everything that waits for you, across machines, first, each card with its
 * answers (Yes, No and Reply for a question; Approve and merge, Fix, Reply for a task); then the
 * agents at work as one line each, those that finished (new until seen) and the rest. Ask opens
 * the Coordinator. On a wide window it is the left pane ([com.makefunstuff.herdr.Panes]): what it
 * opens shows beside it, chosen here. [filterState] and [listState] come from above, so they stay
 * as the list moves between the two layouts.
 */
@Composable
fun AgentsScreen(
    nav: NavController,
    filterState: MutableState<String?> = rememberSaveable { mutableStateOf(null) },
    listState: LazyListState = rememberLazyListState(),
) {
    val container = LocalContainer.current
    val panes = LocalPanes.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hosts by container.hosts.hosts.collectAsStateWithLifecycle()
    val seen by container.seen.marks.collectAsStateWithLifecycle()
    val boards = rememberBoards()
    val boardProblems = rememberBoardProblems()
    val readNow = remember { Channel<Unit>(Channel.CONFLATED) }
    ReadBoards(readNow)
    var chosen by filterState
    // A machine filter for one that's gone (taken out of the app) shows all.
    val filter = chosen?.takeIf { id -> hosts.any { it.machine.id == id } }
    // Beside a wide window's list: what is open in the middle, to show it chosen here.
    val entry by nav.currentBackStackEntryAsState()
    val inMiddle = if (panes.wide) entry?.let(::opened) else null
    var menuAgent by remember { mutableStateOf<HomeAgent?>(null) }
    var showNotices by remember { mutableStateOf(false) }
    var filterMenu by remember { mutableStateOf(false) }
    var reply by remember { mutableStateOf<Reply?>(null) }
    var refusal by remember { mutableStateOf<Refusal?>(null) }
    var allDone by rememberSaveable { mutableStateOf(false) }
    var allIdle by rememberSaveable { mutableStateOf(false) }
    // Cards answered here, by key, with how they stood; and the ones whose answer is on its way.
    val handled = remember { mutableStateMapOf<String, String>() }
    val busy = remember { mutableStateMapOf<String, String>() }
    val unread = hosts.sumOf { it.unreadNotices }
    val noticeRows = remember(hosts) {
        hosts.flatMap { h -> h.notices.map { NoticeRow(h.machine.id, h.machine.label, it) } }.sortedByDescending { it.notice.at }
    }
    val agents = remember(hosts) { HomeAgent.of(hosts) }
    val home = remember(agents, boards, seen, filter) {
        Home.of(agents.filter { filter == null || it.machineId == filter }, boards.filter { filter == null || it.machineId == filter }, seen)
    }
    val needsYou = home.needsYou.filter { handled[it.key] != sig(it) }
    val showMachine = hosts.size > 1 && filter == null
    val anyBoard = hosts.any { it.hasBoard }

    val guard = com.makefunstuff.herdr.ui.common.LocalGuard.current

    fun press(q: Waiting.Question, prompt: BlockedPrompt, index: Int) {
        if (q.key in busy) return
        val label = prompt.options[index].label
        busy[q.key] = "Answering: $label"
        scope.launch {
            runCatching {
                val session = container.hosts.session(q.machineId) ?: error("This machine isn't in the app any more")
                AgentInput.choose(session.awaitApi(10_000), q.agent.agent.paneId, prompt.question, index, label, prompt.details)
                session.refresh()
            }
                .onSuccess {
                    handled[q.key] = sig(q)
                    container.inbox.dismiss(Alerts.agentTag(q.machineId, q.agent.agent.paneId))
                    context.toast("Answered: $label")
                }
                .onFailure { context.toast(it.message ?: "Couldn't answer") }
            busy.remove(q.key)
        }
    }

    /** An answer from the card; a permanent "always allow" asks for the fingerprint first, as on the chat's card and the notification. */
    fun answer(q: Waiting.Question, prompt: BlockedPrompt, index: Int) {
        if (prompt.role(index) == OptionRole.Always) {
            guard.run(Guarded.AlwaysAllow, "${prompt.shortLabel(index)}?", prompt.details.firstOrNull { it.startsWith("$ ") } ?: prompt.question) { press(q, prompt, index) }
        } else press(q, prompt, index)
    }

    fun say(r: Reply, text: String) {
        val w = r.item
        busy[w.key] = "Sending"
        scope.launch {
            runCatching {
                val session = container.hosts.session(w.machineId) ?: error("This machine isn't in the app any more")
                when (r) {
                    is Reply.ToAgent -> {
                        AgentInput.send(session.awaitApi(10_000), r.item.agent.agent.paneId, text, named = true, blocked = true)
                        session.refresh()
                        "Sent: $text"
                    }
                    is Reply.ToTask -> session.awaitBoard().act(BoardAction.Tell, r.item.task.id, text).ifEmpty { "Sent: $text" }
                }
            }
                .onSuccess { handled[w.key] = sig(w); context.toast(it) }
                .onFailure { refusal = Refusal(it.message ?: "Couldn't send that") }
            busy.remove(w.key)
            readNow.trySend(Unit)
        }
    }

    fun carryOut(w: Waiting.Task, action: BoardAction, label: String) {
        if (w.key in busy) return
        busy[w.key] = "$label · ${w.task.name}"
        scope.launch {
            runCatching { (container.hosts.session(w.machineId) ?: error("This machine isn't in the app any more")).awaitBoard().act(action, w.task.id) }
                .onSuccess {
                    handled[w.key] = sig(w)
                    container.inbox.dismiss(Alerts.taskTag(w.machineId, w.task.id))
                    context.toast(it.ifEmpty { "Done" })
                }
                // A failed check (a review, the local check, GitHub's) stops a plain approve: its review card says what failed and offers Approve anyway.
                .onFailure { refusal = Refusal(it.message ?: "The board refused", w.takeIf { action == BoardAction.Approve }) }
            busy.remove(w.key)
            readNow.trySend(Unit)
        }
    }

    fun act(w: Waiting.Task, action: BoardAction, label: String) {
        if (w.key in busy) return
        if (action == BoardAction.Tell) { reply = Reply.ToTask(w); return }
        // A merge can't be taken back: the fingerprint first, when that is on.
        if (action == BoardAction.Approve || action == BoardAction.ApproveAnyway) {
            return guard.run(Guarded.Merge, "Approve and merge ${w.task.name}?", w.task.title) { carryOut(w, action, label) }
        }
        carryOut(w, action, label)
    }

    /** Finished work opens on its review card (what changed, the review, the checks); any other task on its board. */
    fun openTask(w: Waiting.Task) {
        if (Approvals.waits(w.task)) panes.open(nav, Routes.review(w.machineId, w.task.id)) else panes.openBoard(nav, w.machineId, w.task.id)
    }

    fun openAgent(a: HomeAgent) = panes.open(nav, Routes.agent(a.machineId, a.agent.paneId))

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Herdr.colors.ground,
        topBar = {
            HerdrTopBar("Agents") {
                BarIcon(HerdrIcons.Newspaper, "Brief: what happened while you were away", { panes.open(nav, Routes.BRIEF) })
                BarIcon(
                    HerdrIcons.Bell, "Notifications",
                    {
                        hosts.forEach { container.hosts.session(it.machine.id)?.markNoticesRead() }
                        showNotices = true
                    },
                    badge = unread,
                )
            }
        },
        floatingActionButton = {
            // The Coordinator: the one used last, or the machine to ask on (AskScreen decides).
            if (anyBoard) HerdrFab("Ask", "Ask the Coordinator", icon = HerdrIcons.MessagesSquare) { panes.open(nav, Routes.ask(Ask.COORDINATOR)) }
        },
    ) { padding ->
        when {
            hosts.isEmpty() -> EmptyState(
                "No machines yet",
                "Add a machine that runs herdr. Its agents show up here, with the ones waiting on you first.",
                icon = HerdrIcons.Bot,
            ) { PrimaryButton("Add machine") { nav.navigate(Routes.edit()) } }

            agents.isEmpty() && boards.isEmpty() -> EmptyState(
                if (hosts.any { it.online }) "No agents running" else "Not connected yet",
                if (hosts.any { it.online }) {
                    "Start an agent in herdr, or open a machine to start one from here."
                } else {
                    "Check the Machines tab for connection details."
                },
                icon = if (hosts.any { it.online }) HerdrIcons.Bot else HerdrIcons.Monitor,
            )

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.padding(padding),
                // Room under the last row for the Ask button.
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                item(key = "machines") {
                    MachinesLine(
                        total = hosts.size, online = hosts.count { it.online },
                        filter = hosts.firstOrNull { it.machine.id == filter }?.machine?.label,
                        onFilter = if (hosts.size > 1) ({ filterMenu = true }) else null,
                    ) {
                        HerdrMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }, title = "Show") {
                            MenuRow("All machines", HerdrIcons.Layers, { chosen = null; filterMenu = false })
                            hosts.forEach { h ->
                                MenuRow(
                                    h.machine.label + if (h.online) "" else " · offline",
                                    if (h.machine.id == filter) HerdrIcons.Check else HerdrIcons.Monitor,
                                    { chosen = h.machine.id; filterMenu = false },
                                )
                            }
                        }
                    }
                }
                boardProblems.filter { (label, _) -> filter == null || hosts.any { it.machine.id == filter && it.machine.label == label } }.forEach { (label, p) ->
                    item(key = "problem/$label") {
                        ErrorMessage("$label's board: $p", Modifier.padding(horizontal = Space.screen, vertical = Space.xs), onRetry = { readNow.trySend(Unit) })
                    }
                }
                item(key = "h-needs") {
                    SectionHeading(
                        if (needsYou.isEmpty()) "Needs you" else "Needs you · ${needsYou.size}",
                        Modifier.padding(horizontal = Space.screen + Space.xs), top = Space.s,
                        color = if (needsYou.isEmpty()) Herdr.colors.muted else Herdr.colors.red,
                    )
                }
                if (needsYou.isEmpty()) {
                    item(key = "none-waiting") {
                        Row(Modifier.padding(horizontal = Space.screen + Space.xs, vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                            Icon(HerdrIcons.CircleCheck, null, tint = Herdr.colors.done, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(Space.s))
                            Text("Nothing waits for you", style = MaterialTheme.typography.bodyMedium, color = Herdr.colors.muted)
                        }
                    }
                }
                items(needsYou, key = { it.key }) { w ->
                    when (w) {
                        is Waiting.Question -> QuestionCard(
                            w, showMachine, selected = inMiddle == w.agent.key, busy = busy[w.key],
                            onOpen = { openAgent(w.agent) }, onMenu = { menuAgent = w.agent },
                            onChoose = { prompt, i -> answer(w, prompt, i) },
                            onReply = { reply = Reply.ToAgent(w) },
                        )
                        is Waiting.Task -> {
                            val a = Home.answer(w.task)
                            TaskCard(
                                w, showMachine,
                                selected = inMiddle == "review/${w.machineId}/${w.task.id}",
                                busy = busy[w.key], answer = a,
                                onOpen = { openTask(w) },
                                onAnswer = { a?.let { (action, label) -> act(w, action, label) } },
                            )
                        }
                    }
                }
                section("Working", home.working, showAll = true, onMore = {}, inMiddle, showMachine, ::openAgent) { menuAgent = it }
                section(
                    "Done", home.done, showAll = allDone, onMore = { allDone = !allDone }, inMiddle, showMachine, ::openAgent,
                    fresh = home.fresh,
                    onSeen = { container.seen.set(Home.seenAll(seen, home, agents)) },
                ) { menuAgent = it }
                section("Idle", home.idle, showAll = allIdle, onMore = { allIdle = !allIdle }, inMiddle, showMachine, ::openAgent) { menuAgent = it }
            }
        }
    }

    menuAgent?.let { a ->
        container.hosts.session(a.machineId)?.let { session ->
            AgentActionsSheet(
                session = session,
                agent = a.agent,
                paneLabel = a.agent.paneId,
                onDismiss = { menuAgent = null },
                onTerminal = { menuAgent = null; panes.open(nav, Routes.terminal(a.machineId, a.agent.paneId)) },
            )
        }
    }
    reply?.let { r ->
        var text by remember(r) { mutableStateOf("") }
        val dismiss = { reply = null }
        BoardPanel(
            when (r) {
                is Reply.ToAgent -> "Answer ${r.item.task?.name ?: r.item.agent.agent.displayName}"
                is Reply.ToTask -> "Tell ${r.item.task.name}'s agent"
            },
            onDismiss = dismiss,
            buttons = {
                PanelCancel(dismiss)
                PrimaryButton("Send", enabled = text.isNotBlank()) { reply = null; say(r, text.trim()) }
            },
        ) {
            (r as? Reply.ToAgent)?.let { q -> rememberAsked(q.item.agent)?.question }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = Herdr.colors.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            HerdrField(text, { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 3, placeholder = { Text("Your answer") })
        }
    }
    refusal?.let { r ->
        BoardPanel(
            "That didn't go through", onDismiss = { refusal = null }, accent = Herdr.colors.red,
            buttons = {
                if (r.review == null) PrimaryButton("OK") { refusal = null }
                else {
                    PanelCancel { refusal = null }
                    PrimaryButton("Open its review card") { refusal = null; openTask(r.review) }
                }
            },
        ) {
            MarkdownText(r.words)
            if (r.review != null) {
                Text("Its review card says what failed, and can approve it anyway.", style = MaterialTheme.typography.bodyMedium, color = Herdr.colors.muted)
            }
        }
    }
    if (showNotices) {
        NoticesSheet(
            rows = noticeRows,
            showMachine = hosts.size > 1,
            onOpenAgent = { mid, pane -> showNotices = false; panes.open(nav, Routes.agent(mid, pane)) },
            onClear = { hosts.forEach { container.hosts.session(it.machine.id)?.clearNotices() } },
            onDismiss = { showNotices = false },
        )
    }
}

/** A section of one-line agents under its heading; a long resting one folds after [FOLDED] rows. */
private fun LazyListScope.section(
    title: String,
    rows: List<AgentRow>,
    showAll: Boolean,
    onMore: () -> Unit,
    inMiddle: String?,
    showMachine: Boolean,
    onOpen: (HomeAgent) -> Unit,
    fresh: Int = 0,
    onSeen: (() -> Unit)? = null,
    onMenu: (HomeAgent) -> Unit,
) {
    if (rows.isEmpty()) return
    item(key = "h-$title") {
        SectionHeading(
            "$title · ${rows.size}" + if (fresh > 0) " · $fresh new" else "",
            Modifier.padding(start = Space.screen + Space.xs, end = Space.s), top = Space.l,
            trailing = if (fresh > 0 && onSeen != null) ({ QuietButton(onClick = onSeen, color = Herdr.colors.accent) { Text("Mark seen") } }) else null,
        )
    }
    val shown = if (showAll || rows.size <= FOLDED + 1) rows else rows.take(FOLDED)
    items(shown, key = { "${title}/${it.agent.key}" }) { row ->
        AgentLine(row, showMachine, selected = inMiddle == row.agent.key, onOpen = { onOpen(row.agent) }, onMenu = { onMenu(row.agent) })
    }
    if (rows.size > FOLDED + 1) {
        item(key = "more-$title") {
            Text(
                if (showAll) "Show fewer" else "${rows.size - FOLDED} more",
                style = MaterialTheme.typography.labelLarge, color = Herdr.colors.accent,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onMore).padding(horizontal = Space.screen + Space.xs, vertical = Space.m),
            )
        }
    }
}

/** One line for the machines: how many are online, and which one the list shows (a menu to choose). */
@Composable
private fun MachinesLine(total: Int, online: Int, filter: String?, onFilter: (() -> Unit)?, menu: @Composable () -> Unit) {
    val c = Herdr.colors
    Row(
        Modifier.fillMaxWidth().padding(start = Space.screen + Space.xs, end = Space.s).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(if (online > 0) c.done else c.idle)
        Spacer(Modifier.width(Space.s))
        Text(
            when {
                total == 1 -> if (online == 1) "1 machine · online" else "1 machine · offline"
                else -> "$total machines · $online online"
            },
            style = MaterialTheme.typography.bodyMedium, color = c.muted, modifier = Modifier.weight(1f), maxLines = 1,
        )
        if (onFilter != null) Box {
            QuietButton(onClick = onFilter) {
                Text(filter ?: "All machines", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = Space.xs))
                Icon(HerdrIcons.ChevronDown, "Choose the machine", Modifier.size(18.dp))
            }
            menu()
        }
    }
}

/** What the middle of a wide window shows, as the list names it: `machine/pane` for an agent's chat or terminal, else the route. */
private fun opened(entry: NavBackStackEntry): String? {
    val args = entry.arguments
    return when (entry.destination.route) {
        Routes.AGENT, Routes.TERMINAL -> "${args?.getString("mid")}/${Uri.decode(args?.getString("pane").orEmpty())}"
        Routes.REVIEW -> "review/${args?.getString("mid")?.let(Uri::decode)}/${args?.getString("task")?.let(Uri::decode)}"
        else -> null
    }
}

/** Picks the line most likely to be the agent's question from the bottom of its screen. */
object PromptGuess {
    private val noise = Regex("^[\\s│╭╮╰╯─┃━┏┓┗┛>❯›·•|_=+-]*$")

    fun question(screen: String): String? {
        val lines = screen.lines()
            .map { it.trim().trim('│', '┃').trim() }
            .filter { it.isNotEmpty() && !noise.matches(it) }
        if (lines.isEmpty()) return null
        return lines.lastOrNull { it.endsWith("?") } ?: lines.last()
    }
}
