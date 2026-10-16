package com.makefunstuff.herdr.ui.board

import com.makefunstuff.herdr.ui.design.HerdrDialog
import com.makefunstuff.herdr.ui.design.HerdrMenu
import com.makefunstuff.herdr.ui.design.HerdrMenuItem
import com.makefunstuff.herdr.ui.design.HerdrSheet
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.makefunstuff.herdr.core.chat.Markdown
import com.makefunstuff.herdr.ui.common.MarkdownText
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.Pill
import com.makefunstuff.herdr.ui.common.detail
import com.makefunstuff.herdr.ui.common.label
import com.makefunstuff.herdr.ui.common.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

fun BoardStatus.color(): Color = Color(0xFF000000L or tone.rgb)

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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
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
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    if (searching) {
                        OutlinedTextField(
                            value = query, onValueChange = { query = it }, singleLine = true,
                            placeholder = { Text("Search tasks") }, modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Column {
                            Text("Board")
                            Text(
                                "${state.machine.label} · ${if (archived) "archived" else "${tasks.count { it.needsYou }} need you"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { if (searching) query = ""; searching = !searching }) {
                        Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, "Search")
                    }
                    IconButton(onClick = { nav.navigate(Routes.coordinator(machineId)) }) { Icon(Icons.Filled.Email, "Coordinator chat") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Filter and more") }
                        HerdrMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            HerdrMenuItem(text = { Text(if (archived) "Hide archived" else "Show archived") }, onClick = { archived = !archived; menu = false })
                            HerdrMenuItem(text = { Text("Refresh") }, leadingIcon = { Icon(Icons.Filled.Refresh, null) }, onClick = { readNow.trySend(Unit); menu = false })
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (state.boardPath != null) FloatingActionButton(onClick = { pending = Pending.Add }) { Icon(Icons.Filled.Add, "Add a task") }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            busy?.let { b ->
                LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Running: $b" })
                Text(b + "…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            if (repos.size > 1 || machines.isNotEmpty() || repo.isNotEmpty() || machine.isNotEmpty()) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    (repos + machines).forEach { name ->
                        val isMachine = name in machines
                        val on = if (isMachine) machine == name else repo == name
                        FilterChip(
                            selected = on,
                            onClick = { if (isMachine) machine = if (on) "" else name else repo = if (on) "" else name },
                            label = { Text(if (isMachine) "⇄ $name" else name) },
                        )
                    }
                }
            }
            when {
                !state.online -> EmptyState(state.status.label(), state.status.detail() ?: "Waiting for ${state.machine.label}…")
                state.boardPath == null -> EmptyState("No work board here", "herdr-projects isn't installed on ${state.machine.label}.")
                !loaded -> EmptyState("Loading…", "Reading the board on ${state.machine.label}.")
                problem != null && tasks.isEmpty() -> EmptyState("The board isn't answering", problem.orEmpty())
                else -> PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = { scope.launch { refreshing = true; readNow.trySend(Unit); delay(900); refreshing = false } },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) { LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 88.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    problem?.let { p -> item(key = "problem") { Text(p, color = BoardStatus.Error.color(), style = MaterialTheme.typography.bodySmall) } }
                    if (groups.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                if (query.isNotBlank()) "No task matches “$query”." else if (archived) "Nothing archived." else "No tasks yet. Add one with +.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                    groups.forEach { (title, list) ->
                        val collapsible = title == "Done"
                        val open = !collapsible || showDone
                        item(key = "h-$title") {
                            Text(
                                (if (collapsible) (if (open) "▾ " else "▸ ") else "") + "$title · ${list.size}",
                                style = MaterialTheme.typography.titleSmall,
                                color = if (title == "Needs you") BoardStatus.Error.color() else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                    .then(if (collapsible) Modifier.clickable(onClickLabel = if (open) "Hide done tasks" else "Show done tasks") { showDone = !showDone } else Modifier)
                                    .heightIn(min = 40.dp).wrapContentHeight(Alignment.CenterVertically)
                                    .semantics { heading() },
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
        BoardPanel("The board said no", onDismiss = { refusal = null }, accent = MaterialTheme.colorScheme.error,
            buttons = { Button(onClick = { refusal = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("OK") } }) {
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskRow(t: BoardTask, onOpen: () -> Unit, onMenu: () -> Unit) {
    val color = t.status.color()
    val quiet = t.status == BoardStatus.Done || t.status == BoardStatus.Archived
    val doing = remember(t.msg) { Markdown.plain(t.msg) }
    Card(
        modifier = Modifier.fillMaxWidth()
            .combinedClickable(onClickLabel = "Open details", onLongClickLabel = "Actions", onClick = onOpen, onLongClick = onMenu)
            .semantics(mergeDescendants = true) {
                contentDescription = "${t.name}, ${t.status.label}${if (t.needsYou) ", needs you" else ""}, ${t.title}"
            },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (t.needsYou) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainer,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (t.needsYou) 2.dp else 0.dp),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(if (t.needsYou) 6.dp else 4.dp).fillMaxHeight().background(color))
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(8.dp))
                    Pill(t.status.label, color = color)
                    if (t.research) { Spacer(Modifier.width(6.dp)); Pill("research") }
                    Spacer(Modifier.weight(1f))
                    if (t.agent.isNotEmpty()) Text(t.agent, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp))
                }
                Text(
                    t.title,
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    color = if (quiet) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (doing.isNotEmpty() && !quiet) {
                    Text(doing, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val p = t.progress
                if (p != null && !quiet) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(progress = { p / 100f }, color = color, modifier = Modifier.weight(1f))
                        Text("  $p%" + (t.eta?.let { " · ${it}m left" } ?: ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else if (t.eta != null && !quiet) {
                    Text("${t.eta}m left", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (t.pr.isNotEmpty()) {
                    Text("PR " + t.prNumber.let { if (it.isEmpty()) t.pr else "#$it" }, style = MaterialTheme.typography.labelSmall, color = BoardStatus.Done.color())
                }
            }
        }
    }
}
