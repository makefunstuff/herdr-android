package com.makefunstuff.herdr.ui.board

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.core.board.BoardAction
import com.makefunstuff.herdr.core.board.BoardFilter
import com.makefunstuff.herdr.core.board.BoardStatus
import com.makefunstuff.herdr.core.board.BoardTask
import com.makefunstuff.herdr.core.chat.Markdown
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.MarkdownText
import com.makefunstuff.herdr.ui.common.detail
import com.makefunstuff.herdr.ui.common.label
import com.makefunstuff.herdr.ui.common.toast
import com.makefunstuff.herdr.ui.design.BarAdd
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.ErrorMessage
import com.makefunstuff.herdr.ui.design.ErrorState
import com.makefunstuff.herdr.ui.design.HerdrChip
import com.makefunstuff.herdr.ui.design.HerdrField
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrMenu
import com.makefunstuff.herdr.ui.design.HerdrShapes
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.design.MenuRow
import com.makefunstuff.herdr.ui.design.PrimaryButton
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.design.SectionHeading
import com.makefunstuff.herdr.ui.design.StateBlock
import com.makefunstuff.herdr.ui.design.StatusBadge
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import com.makefunstuff.herdr.ui.theme.tone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The board's colour for this status, in the current theme. */
@Composable
@ReadOnlyComposable
fun BoardStatus.color(): Color = Herdr.colors.tone(tone)

/** What a person is part-way through doing to a task: asked for words, or asked to be sure. */
sealed interface Pending {
    data class Words(val task: BoardTask, val action: BoardAction) : Pending
    data class Confirm(val task: BoardTask, val action: BoardAction, val letter: String = "") : Pending
    data class Anyway(val task: BoardTask) : Pending
    data class Edit(val task: BoardTask) : Pending
    data object Add : Pending
}

/**
 * The work board on one machine, as a phone screen: its tasks as `herdr-projects list --json` gives
 * them, read again while the screen is in front, every action the board's CLI has.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardScreen(nav: NavController, machineId: String) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = container.hosts.session(machineId)
    if (session == null) {
        EmptyState("Machine not found", "It may have been removed.")
        return
    }
    val state by session.state.collectAsStateWithLifecycle()
    var tasks by remember { mutableStateOf<List<BoardTask>>(emptyList()) }
    var problem by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var archived by rememberSaveable { mutableStateOf(false) }
    var repo by rememberSaveable { mutableStateOf("") }
    var machine by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var actionsFor by remember { mutableStateOf<BoardTask?>(null) }
    var pending by remember { mutableStateOf<Pending?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var refusal by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var showDone by rememberSaveable { mutableStateOf(false) }
    val readNow = remember { Channel<Unit>(Channel.CONFLATED) }
    val owner = LocalLifecycleOwner.current

    // Read while the screen is in front (a few seconds apart, sooner after an action); nothing while it's backgrounded.
    LaunchedEffect(machineId, state.online, state.boardPath, archived, query) {
        if (!state.online || state.boardPath == null) return@LaunchedEffect
        // A search typed letter by letter waits a moment for the next one.
        if (query.isNotBlank()) delay(350)
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    tasks = session.awaitBoard().tasks(BoardFilter(query = query, archived = archived))
                    problem = null
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    problem = t.message ?: "Couldn't read the board"
                }
                loaded = true
                withTimeoutOrNull(4000L) { readNow.receive() }
            }
        }
    }

    fun perform(task: BoardTask, action: BoardAction, text: String = "", letter: String = "") {
        if (busy != null) return
        busy = "${action.label.removeSuffix("…")} · ${task.name}"
        scope.launch {
            runCatching { session.awaitBoard().act(action, task.id, text, letter) }
                .onSuccess { context.toast(it.ifEmpty { "Done" }) }
                .onFailure { refusal = it.message ?: "The board refused" }
            busy = null
            readNow.trySend(Unit)
        }
    }

    /** Offers an action: words first, a confirmation for the ones that can't be undone, else just runs it. */
    fun choose(task: BoardTask, action: BoardAction) {
        pending = when {
            action == BoardAction.Tell || action == BoardAction.SendPlanBack -> Pending.Words(task, action)
            action == BoardAction.ApproveAnyway -> Pending.Anyway(task)
            busy != null -> null
            action.destructive -> Pending.Confirm(task, action)
            else -> { perform(task, action); null }
        }
    }

    val repos = remember(tasks) { tasks.map { it.repo }.distinct().sorted() }
    val machines = remember(tasks) { tasks.map { it.machine }.filter { it.isNotEmpty() }.distinct().sorted() }
    val shown = remember(tasks, repo, machine) { tasks.filter { BoardFilter(repo = repo, machine = machine).matches(it) } }
    val groups = remember(shown) {
        listOf(
            "Needs you" to shown.filter { it.needsYou },
            "Working" to shown.filter { !it.needsYou && it.status in setOf(BoardStatus.Working, BoardStatus.Reviewing, BoardStatus.Queued) },
            "To do" to shown.filter { !it.needsYou && it.status == BoardStatus.Todo },
            "Done" to shown.filter { !it.needsYou && it.status == BoardStatus.Done },
            "Archived" to shown.filter { it.status == BoardStatus.Archived },
        ).filter { it.second.isNotEmpty() }
    }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = Herdr.colors.ground,
        topBar = {
            HerdrTopBar(
                "Board",
                subtitle = "${state.machine.label} · ${if (archived) "archived" else "${tasks.count { it.needsYou }} need you"}",
                onBack = { nav.popBackStack() },
                titleContent = if (searching) ({
                    HerdrField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        placeholder = { Text("Search tasks") }, modifier = Modifier.fillMaxWidth(),
                        leadingIcon = { Icon(HerdrIcons.Search, null, Modifier.size(20.dp)) },
                        shape = HerdrShapes.Pill,
                    )
                }) else null,
            ) {
                BarIcon(if (searching) HerdrIcons.Close else HerdrIcons.Search, if (searching) "Close search" else "Search", { if (searching) query = ""; searching = !searching })
                if (!searching && state.boardPath != null) BarAdd("Add a task") { pending = Pending.Add }
                BarIcon(HerdrIcons.MoreVert, "Filter and more", { menu = true })
                HerdrMenu(expanded = menu, onDismissRequest = { menu = false }, title = "Board", subtitle = state.machine.label) {
                    MenuRow("Coordinator chat", HerdrIcons.Forum, { menu = false; nav.navigate(Routes.coordinator(machineId)) })
                    MenuRow(if (archived) "Hide archived" else "Show archived", if (archived) HerdrIcons.Unarchive else HerdrIcons.Archive, { archived = !archived; menu = false })
                    MenuRow("Refresh", HerdrIcons.Refresh, { readNow.trySend(Unit); menu = false })
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            busy?.let { b -> BusyLine(b) }
            if (repos.size > 1 || machines.isNotEmpty() || repo.isNotEmpty() || machine.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = Space.screen),
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    item { HerdrChip("All", selected = repo.isEmpty() && machine.isEmpty(), onClick = { repo = ""; machine = "" }) }
                    items(repos + machines) { name ->
                        val isMachine = name in machines
                        val on = if (isMachine) machine == name else repo == name
                        HerdrChip(
                            selected = on,
                            onClick = { if (isMachine) machine = if (on) "" else name else repo = if (on) "" else name },
                            label = { Text(if (isMachine) "⇄ $name" else name, maxLines = 1) },
                        )
                    }
                }
            }
            when {
                !state.online -> EmptyState(state.status.label(), state.status.detail() ?: "Waiting for ${state.machine.label}…", icon = HerdrIcons.Dns)
                state.boardPath == null -> EmptyState("No work board here", "herdr-projects isn't installed on ${state.machine.label}.", icon = HerdrIcons.ViewKanban)
                !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingState("Reading the board on ${state.machine.label}") }
                problem != null && tasks.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ErrorState("The board isn't answering", problem.orEmpty(), onRetry = { readNow.trySend(Unit) })
                }
                else -> PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = { scope.launch { refreshing = true; readNow.trySend(Unit); delay(900); refreshing = false } },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) { LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = Space.screen, end = Space.screen, top = Space.xs, bottom = Space.xl),
                    verticalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    problem?.let { p -> item(key = "problem") { ErrorMessage(p, onRetry = { readNow.trySend(Unit) }) } }
                    if (groups.isEmpty()) {
                        item(key = "empty") {
                            StateBlock(
                                if (query.isNotBlank()) HerdrIcons.Search else HerdrIcons.ViewKanban,
                                if (query.isNotBlank()) "No task matches “$query”" else if (archived) "Nothing archived" else "No tasks yet",
                                if (query.isNotBlank() || archived) null else "Add one with +, or tell the Coordinator what needs doing.",
                                Modifier.padding(top = Space.xxl),
                            )
                        }
                    }
                    groups.forEach { (title, list) ->
                        val collapsible = title == "Done"
                        val open = !collapsible || showDone
                        item(key = "h-$title") {
                            SectionHeading(
                                "$title · ${list.size}",
                                modifier = Modifier
                                    .then(if (collapsible) Modifier.clickable(onClickLabel = if (open) "Hide done tasks" else "Show done tasks") { showDone = !showDone } else Modifier)
                                    .heightIn(min = 48.dp),
                                top = if (title == groups.first().first) Space.xs else Space.m,
                                trailing = if (collapsible) ({
                                    Icon(if (open) HerdrIcons.ExpandLess else HerdrIcons.ExpandMore, null, tint = Herdr.colors.muted, modifier = Modifier.size(20.dp))
                                }) else null,
                            )
                        }
                        if (open) items(list, key = { it.id }) { t ->
                            TaskRow(t, onOpen = { openId = t.id }, onMenu = { actionsFor = t })
                        }
                    }
                } }
            }
        }
    }

    val open = tasks.firstOrNull { it.id == openId }
    if (openId != null && open != null) {
        TaskDetailScreen(session, open, onBack = { openId = null }, busy = busy, onAction = { a -> choose(open, a) }, onPick = { letter -> perform(open, BoardAction.Pick, letter = letter) },
            onMore = { actionsFor = open })
    }
    actionsFor?.let { t ->
        ActionsSheet(t, onDismiss = { actionsFor = null }, onAction = { a -> actionsFor = null; choose(t, a) }, onEdit = { actionsFor = null; pending = Pending.Edit(t) },
            onOpen = if (openId == t.id) null else ({ actionsFor = null; openId = t.id }))
    }
    refusal?.let { r ->
        BoardPanel("The board said no", onDismiss = { refusal = null }, accent = Herdr.colors.red,
            buttons = { PrimaryButton("OK") { refusal = null } }) {
            MarkdownText(r)
        }
    }
    BoardDialogs(
        pending = pending, session = session, repos = repos, onDismiss = { pending = null },
        onWords = { p, text -> pending = null; perform(p.task, p.action, text) },
        onConfirm = { p -> pending = null; perform(p.task, p.action, letter = p.letter) },
        onAnyway = { p -> pending = null; perform(p.task, BoardAction.ApproveAnyway) },
        onSaved = { pending = null; readNow.trySend(Unit) },
    )
    }
}

/** An action running on the board: a thin accent bar and what it is. */
@Composable
internal fun BusyLine(what: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.screen, vertical = Space.xs)) {
        LinearProgressIndicator(
            Modifier.fillMaxWidth().height(3.dp).clip(HerdrShapes.Pill).semantics { contentDescription = "Running: $what" },
            color = Herdr.colors.accent, trackColor = Herdr.colors.field,
        )
        Text("$what…", style = MaterialTheme.typography.labelMedium, color = Herdr.colors.muted, modifier = Modifier.padding(top = Space.xs))
    }
}

/** A thin rounded progress bar in a status colour. */
@Composable
internal fun Progress(p: Int, color: Color, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { p / 100f }, color = color, trackColor = Herdr.colors.field,
        gapSize = 0.dp, drawStopIndicator = {},
        modifier = modifier.height(4.dp).clip(HerdrShapes.Pill),
    )
}

/**
 * A task as a card: its key and status on top, the title, what it is doing, then progress or its
 * pull request. Tasks that wait on you look the same but sit first; the status says why.
 */
@Composable
private fun TaskRow(t: BoardTask, onOpen: () -> Unit, onMenu: () -> Unit) {
    val c = Herdr.colors
    val color = t.status.color()
    val quiet = t.status == BoardStatus.Done || t.status == BoardStatus.Archived
    val doing = remember(t.msg) { Markdown.plain(t.msg) }
    QuietCard(
        Modifier.semantics(mergeDescendants = true) {
            contentDescription = "${t.name}, ${t.status.label}${if (t.needsYou) ", needs you" else ""}, ${t.title}"
        },
        padded = true,
        onClick = onOpen,
        onLongClick = onMenu,
        onClickLabel = "Open details",
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t.name, style = MaterialTheme.typography.titleSmall, color = c.muted)
            if (t.research) Text(" · research", style = MaterialTheme.typography.labelMedium, color = c.faint)
            Spacer(Modifier.weight(1f))
            StatusBadge(t.status.label, color)
        }
        Spacer(Modifier.height(Space.s))
        Text(
            t.title,
            style = MaterialTheme.typography.titleMedium,
            color = if (quiet) c.muted else c.text,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        if (doing.isNotEmpty() && !quiet) {
            Text(doing, style = MaterialTheme.typography.bodyMedium, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Space.xxs))
        }
        val p = t.progress
        val meta = listOfNotNull(
            t.agent.takeIf { it.isNotEmpty() },
            t.pr.takeIf { it.isNotEmpty() }?.let { "PR " + t.prNumber.let { n -> if (n.isEmpty()) it else "#$n" } },
        ).joinToString(" · ")
        if ((p != null || t.eta != null) && !quiet) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Space.m)) {
                if (p != null) Progress(p, color, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
                Text(
                    listOfNotNull(p?.let { "$it%" }, t.eta?.let { "${it}m left" }).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium, color = c.faint, modifier = Modifier.padding(start = Space.m),
                )
            }
        }
        if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelMedium, color = c.faint, modifier = Modifier.padding(top = Space.s))
    }
}
