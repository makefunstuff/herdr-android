package com.makefunstuff.herdr.ui.board

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
        scope.launch {
            runCatching { session.awaitBoard().act(action, task.id, text, letter) }
                .onSuccess { context.toast(it.ifEmpty { "Done" }) }
                .onFailure { context.toast(it.message ?: "The board refused") }
            readNow.trySend(Unit)
        }
    }

    /** Offers an action: words first, a confirmation for the ones that can't be undone, else just runs it. */
    fun choose(task: BoardTask, action: BoardAction) {
        pending = when {
            action == BoardAction.Tell || action == BoardAction.SendPlanBack -> Pending.Words(task, action)
            action == BoardAction.ApproveAnyway -> Pending.Anyway(task)
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
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(if (archived) "Hide archived" else "Show archived") }, onClick = { archived = !archived; menu = false })
                            DropdownMenuItem(text = { Text("Refresh") }, leadingIcon = { Icon(Icons.Filled.Refresh, null) }, onClick = { readNow.trySend(Unit); menu = false })
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
                else -> LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
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
                        item(key = "h-$title") {
                            Text(
                                "$title · ${list.size}",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (title == "Needs you") BoardStatus.Error.color() else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        items(list, key = { it.id }) { t ->
                            TaskRow(t, onOpen = { openId = t.id }, onMenu = { actionsFor = t })
                        }
                    }
                }
            }
        }
    }

    val open = tasks.firstOrNull { it.id == openId }
    if (openId != null && open != null) {
        TaskSheet(session, open, onDismiss = { openId = null }, onAction = { a -> choose(open, a) }, onPick = { letter -> pending = Pending.Confirm(open, BoardAction.Pick, letter) },
            onEdit = { pending = Pending.Edit(open) })
    }
    actionsFor?.let { t ->
        ActionsSheet(t, onDismiss = { actionsFor = null }, onAction = { a -> actionsFor = null; choose(t, a) }, onEdit = { actionsFor = null; pending = Pending.Edit(t) }, onOpen = { actionsFor = null; openId = t.id })
    }
    BoardDialogs(
        pending = pending, session = session, repos = repos, onDismiss = { pending = null },
        onWords = { p, text -> pending = null; perform(p.task, p.action, text) },
        onConfirm = { p -> pending = null; perform(p.task, p.action, letter = p.letter) },
        onAnyway = { p -> pending = null; perform(p.task, BoardAction.ApproveAnyway) },
        onSaved = { pending = null; readNow.trySend(Unit) },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskRow(t: BoardTask, onOpen: () -> Unit, onMenu: () -> Unit) {
    val color = t.status.color()
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = onMenu),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (t.needsYou) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(5.dp).fillMaxHeight().background(color))
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Pill(t.status.label, color = color)
                    if (t.research) { Spacer(Modifier.width(6.dp)); Pill("research") }
                    Spacer(Modifier.weight(1f))
                    if (t.agent.isNotEmpty()) Text(t.agent, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    t.title + if (t.research) " [research]" else "",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
                )
                if (t.msg.isNotEmpty()) {
                    Text(t.msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                val p = t.progress
                if (p != null && t.status != BoardStatus.Done) {
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(progress = { p / 100f }, color = color, modifier = Modifier.weight(1f))
                        Text("  $p%" + (t.eta?.let { " · ${it}m left" } ?: ""), style = MaterialTheme.typography.labelSmall, color = color)
                    }
                } else if (t.eta != null && t.status != BoardStatus.Done) {
                    Text("${t.eta}m left", style = MaterialTheme.typography.labelSmall, color = color)
                }
                if (t.pr.isNotEmpty()) {
                    Text("PR ${t.prNumber.ifEmpty { "" }.let { if (it.isEmpty()) t.pr else "#$it" }}", style = MaterialTheme.typography.labelSmall, color = BoardStatus.Done.color(), modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}
