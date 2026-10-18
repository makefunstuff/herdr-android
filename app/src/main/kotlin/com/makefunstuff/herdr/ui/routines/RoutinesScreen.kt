package com.makefunstuff.herdr.ui.routines

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.LocalPanes
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.core.board.Automation
import com.makefunstuff.herdr.core.board.AutomationWords
import com.makefunstuff.herdr.core.board.BoardClient
import com.makefunstuff.herdr.core.board.BoardTask
import com.makefunstuff.herdr.core.board.Outcome
import com.makefunstuff.herdr.core.board.Trigger
import com.makefunstuff.herdr.core.home.Home
import com.makefunstuff.herdr.core.home.HomeAgent
import com.makefunstuff.herdr.core.host.HostState
import com.makefunstuff.herdr.ui.board.BoardPanel
import com.makefunstuff.herdr.ui.board.BusyLine
import com.makefunstuff.herdr.ui.board.PanelCancel
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.detail
import com.makefunstuff.herdr.ui.common.label
import com.makefunstuff.herdr.ui.common.toast
import com.makefunstuff.herdr.ui.design.DangerButton
import com.makefunstuff.herdr.ui.design.ErrorMessage
import com.makefunstuff.herdr.ui.design.HerdrFab
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.IconTile
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.design.SectionHeading
import com.makefunstuff.herdr.ui.design.StateBlock
import com.makefunstuff.herdr.ui.design.switchColors
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** One machine's automations as last read, or why they couldn't be. */
internal data class Shelf(val list: List<Automation>? = null, val problem: String? = null)

/** A routine and the machine whose board has it. */
internal data class Ref(val machineId: String, val label: String, val automation: Automation)

/** The colour of how a routine's last run went. */
@Composable
internal fun Outcome.Mood.color(): Color = Herdr.colors.let { c ->
    when (this) {
        Outcome.Mood.Good -> c.done
        Outcome.Mood.Working -> c.working
        Outcome.Mood.Stuck -> c.red
        Outcome.Mood.Quiet -> c.idle
    }
}

/**
 * The board's automations, as routines: each with its trigger in plain words, how its last run went,
 * a pause switch and Run now; a tap opens it (its firings, edit, remove), + makes one. With
 * [machineId] one machine's board, else every connected board's, each card saying its machine.
 * Everything goes through the board's own `herdr-projects automations` commands.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutinesScreen(nav: NavController, machineId: String?) {
    val container = LocalContainer.current
    val panes = LocalPanes.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val hosts by container.hosts.hosts.collectAsStateWithLifecycle()
    val lists by container.boards.lists.collectAsStateWithLifecycle()
    val one = machineId?.let { id -> hosts.firstOrNull { it.machine.id == id } }
    if (machineId != null && one == null) {
        EmptyState("Machine not found", "It may have been removed.")
        return
    }
    val machines = if (one != null) listOf(one).filter { it.online && it.hasBoard } else hosts.filter { it.online && it.hasBoard }
    val ids = machines.map { it.machine.id }
    var shelves by remember { mutableStateOf<Map<String, Shelf>>(emptyMap()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var openKey by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Editing?>(null) }
    var removing by remember { mutableStateOf<Ref?>(null) }
    val readNow = remember { Channel<Unit>(Channel.CONFLATED) }

    // Read while in front: every board's automations, and its tasks (how the last runs went), sooner after an action.
    LaunchedEffect(ids.joinToString(",")) {
        if (ids.isEmpty()) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                coroutineScope {
                    ids.map { id ->
                        async {
                            val session = container.hosts.session(id) ?: return@async
                            launch { runCatching { container.boards.readOne(id, freshMs = 10_000) } }
                            val shelf = try {
                                Shelf(session.awaitBoard().automations())
                            } catch (e: CancellationException) {
                                throw e
                            } catch (t: Throwable) {
                                Shelf(shelves[id]?.list, t.message ?: "Couldn't read the routines")
                            }
                            shelves = shelves + (id to shelf)
                        }
                    }.awaitAll()
                }
                withTimeoutOrNull(20_000L) { readNow.receive() }
            }
        }
    }

    val refs = remember(shelves, machines) {
        machines.flatMap { h -> shelves[h.machine.id]?.list.orEmpty().map { Ref(h.machine.id, h.machine.label, it) } }
    }
    val tasks = remember(lists) { lists.mapValues { it.value.tasks } }
    fun task(mid: String, id: String): BoardTask? = tasks[mid]?.firstOrNull { it.id == id }

    /** The pane of the agent at work on [taskId], when its chat is open on the machine. */
    fun chatOf(mid: String, taskId: String): String? {
        val t = task(mid, taskId) ?: return null
        val host = hosts.firstOrNull { it.machine.id == mid } ?: return null
        return HomeAgent.of(listOf(host)).firstOrNull { Home.taskOf(it, listOf(t)) != null }?.agent?.paneId
    }

    fun openTask(mid: String, taskId: String) {
        val pane = chatOf(mid, taskId)
        if (pane != null) panes.open(nav, Routes.agent(mid, pane)) else panes.openBoard(nav, mid, taskId, alone = true)
    }

    fun act(ref: Ref, what: String, block: suspend BoardClient.() -> String) {
        if (busy != null) return
        val session = container.hosts.session(ref.machineId) ?: return
        busy = "$what · ${ref.automation.name}"
        scope.launch {
            runCatching { session.awaitBoard().block() }
                .onSuccess { context.toast(it.ifEmpty { "Done" }) }
                .onFailure { context.toast(it.message ?: "The board refused") }
            busy = null
            readNow.trySend(Unit)
        }
    }

    val now = container.clock()
    val subtitle = one?.let { "${it.machine.label} board" } ?: if (machines.size == 1) "${machines[0].machine.label} board" else "Every machine's board"
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Herdr.colors.ground,
            topBar = { HerdrTopBar("Routines", subtitle = subtitle, onBack = { nav.popBackStack() }) },
            floatingActionButton = {
                if (machines.isNotEmpty()) HerdrFab("New routine", icon = HerdrIcons.Plus) { editing = Editing(machineId ?: machines.first().machine.id, null, chooseMachine = machineId == null) }
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                busy?.let { BusyLine(it) }
                when {
                    one != null && !one.online -> EmptyState(one.status.label(), one.status.detail() ?: "Waiting for ${one.machine.label}…", icon = HerdrIcons.Monitor)
                    one != null && !one.hasBoard -> EmptyState("No work board here", "herdr-projects isn't installed on ${one.machine.label}.", icon = HerdrIcons.SquareKanban)
                    machines.isEmpty() -> EmptyState("No work board online", "Routines live on a machine's work board (herdr-projects).", icon = HerdrIcons.SquareKanban)
                    ids.none { it in shelves } -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingState("Reading the routines") }
                    else -> PullToRefreshBox(
                        isRefreshing = refreshing,
                        onRefresh = { scope.launch { refreshing = true; readNow.trySend(Unit); delay(900); refreshing = false } },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    ) {
                        RoutineList(
                            refs, machines, shelves, across = one == null && machines.size > 1, now = now,
                            task = ::task, chatOf = ::chatOf,
                            onOpen = { openKey = "${it.machineId}/${it.automation.id}" },
                            onTurn = { r, on -> act(r, if (on) "Turning on" else "Pausing") { turnAutomation(r.automation.id, on) } },
                            onRun = { r -> act(r, "Running") { runAutomation(r.automation.id) } },
                            onTask = ::openTask,
                            onRetry = { readNow.trySend(Unit) },
                        )
                    }
                }
            }
        }

        val open = openKey?.let { k -> refs.firstOrNull { "${it.machineId}/${it.automation.id}" == k } }
        if (open != null) {
            RoutineDetail(
                open, now = now, busy = busy, task = { task(open.machineId, it) }, chatOf = { chatOf(open.machineId, it) },
                onBack = { openKey = null },
                onTurn = { on -> act(open, if (on) "Turning on" else "Pausing") { turnAutomation(open.automation.id, on) } },
                onRun = { act(open, "Running") { runAutomation(open.automation.id) } },
                onEdit = { editing = Editing(open.machineId, open.automation) },
                onRemove = { removing = open },
                onTask = { openTask(open.machineId, it) },
            )
        }
        editing?.let { e ->
            RoutineEditor(
                e, machines = machines, shelves = shelves, tasks = tasks,
                onDismiss = { editing = null },
                onSaved = { said -> editing = null; context.toast(said.ifEmpty { "Saved" }); readNow.trySend(Unit) },
            )
        }
        removing?.let { r ->
            BoardPanel(
                "Remove “${r.automation.name}”?", onDismiss = { removing = null },
                buttons = {
                    PanelCancel { removing = null }
                    DangerButton("Remove") {
                        removing = null
                        openKey = null
                        act(r, "Removing") { removeAutomation(r.automation.id) }
                    }
                },
            ) {
                Text(
                    "It stops firing and its firings are forgotten. The tasks it made stay on the board.",
                    style = MaterialTheme.typography.bodyMedium, color = Herdr.colors.muted,
                )
            }
        }
    }
}

/** The routines in three groups: on a schedule, on a board event, paused. */
@Composable
private fun RoutineList(
    refs: List<Ref>,
    machines: List<HostState>,
    shelves: Map<String, Shelf>,
    across: Boolean,
    now: Long,
    task: (String, String) -> BoardTask?,
    chatOf: (String, String) -> String?,
    onOpen: (Ref) -> Unit,
    onTurn: (Ref, Boolean) -> Unit,
    onRun: (Ref) -> Unit,
    onTask: (String, String) -> Unit,
    onRetry: () -> Unit,
) {
    val groups = listOf(
        "On a schedule" to refs.filter { it.automation.on && it.automation.trigger is Trigger.Schedule },
        "On board events" to refs.filter { it.automation.on && it.automation.trigger is Trigger.Event },
        "Paused" to refs.filter { !it.automation.on },
    ).filter { it.second.isNotEmpty() }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Space.screen, end = Space.screen, top = Space.xs, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        machines.forEach { h ->
            shelves[h.machine.id]?.problem?.let { p ->
                item(key = "problem-${h.machine.id}") { ErrorMessage(if (across) "${h.machine.label}: $p" else p, onRetry = onRetry) }
            }
        }
        if (groups.isEmpty()) {
            item(key = "empty") {
                StateBlock(
                    HerdrIcons.CalendarClock, "No routines yet",
                    "A routine adds a task on a schedule (every Monday at 07:00) or when something happens on the board, and puts an agent on it. Make one with New routine.",
                    Modifier.padding(top = Space.xxl),
                )
            }
        }
        groups.forEachIndexed { i, (title, list) ->
            item(key = "h-$title") { SectionHeading(title, top = if (i == 0) Space.s else Space.l) }
            items(list, key = { "${it.machineId}/${it.automation.id}" }) { r ->
                RoutineCard(
                    r, across, now, task(r.machineId, r.automation.last?.task.orEmpty()),
                    hasChat = r.automation.last?.task?.let { chatOf(r.machineId, it) } != null,
                    onOpen = { onOpen(r) }, onTurn = { onTurn(r, it) }, onRun = { onRun(r) }, onTask = { onTask(r.machineId, it) },
                )
            }
        }
    }
}

/** What a routine's subtitle says: its trigger, its repo, its machine across boards, research only. */
internal fun Ref.about(across: Boolean): String = listOfNotNull(
    AutomationWords.trigger(automation.trigger),
    automation.repo.takeIf { it.isNotEmpty() && !(automation.trigger is Trigger.Event && (automation.trigger as Trigger.Event).everyRepo) },
    label.takeIf { across },
    "research only".takeIf { automation.research },
).joinToString(" · ")

/** The last run's line, with where a tap goes when it went wrong. */
internal fun Outcome.line(hasChat: Boolean): String =
    if (mood == Outcome.Mood.Stuck && task.isNotEmpty()) "$text · ${if (hasChat) "open its chat" else "open it"}" else text

/**
 * A routine as a card, as the board's row says it: its kind's icon, name, trigger and repo; the pause
 * switch; how its last run went (a tap opens that task, its chat when its agent is there) and Run now.
 */
@Composable
private fun RoutineCard(
    r: Ref,
    across: Boolean,
    now: Long,
    lastTask: BoardTask?,
    hasChat: Boolean,
    onOpen: () -> Unit,
    onTurn: (Boolean) -> Unit,
    onRun: () -> Unit,
    onTask: (String) -> Unit,
) {
    val c = Herdr.colors
    val a = r.automation
    val outcome = Outcome.of(a, lastTask, now)
    val about = r.about(across)
    val line = outcome.line(hasChat)
    QuietCard(
        Modifier.semantics {
            customActions = listOfNotNull(
                CustomAccessibilityAction(if (a.on) "Pause" else "Turn on") { onTurn(!a.on); true },
                CustomAccessibilityAction("Run now") { onRun(); true }.takeIf { a.on },
                CustomAccessibilityAction(if (hasChat) "Open its chat" else "Open its last task") { onTask(outcome.task); true }.takeIf { outcome.task.isNotEmpty() },
            )
        },
        onClick = onOpen, onClickLabel = "Open routine",
    ) {
        Row(
            Modifier.padding(start = Space.l, end = Space.m, top = Space.m).semantics(mergeDescendants = true) {
                contentDescription = "${a.name}, ${if (a.on) "on" else "paused"}, $about"
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(if (a.trigger is Trigger.Event) HerdrIcons.Zap else HerdrIcons.Clock, tint = if (a.on) null else c.off)
            Column(Modifier.weight(1f).padding(horizontal = Space.m)) {
                Text(a.name, style = MaterialTheme.typography.titleMedium, color = if (a.on) c.strong else c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(about, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Switch(
                checked = a.on, onCheckedChange = onTurn, colors = switchColors(),
                modifier = Modifier.semantics { contentDescription = "On"; stateDescription = if (a.on) "On" else "Paused" },
            )
        }
        Row(Modifier.padding(start = Space.l, end = Space.xs, top = Space.xs, bottom = Space.xs), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).heightIn(min = 40.dp)
                    .then(if (outcome.task.isNotEmpty()) Modifier.clickable(onClickLabel = if (hasChat) "Open its chat" else "Open its task") { onTask(outcome.task) } else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(7.dp).padding(0.dp)) { com.makefunstuff.herdr.ui.common.StatusDot(outcome.mood.color(), size = 7) }
                Spacer(Modifier.width(Space.s))
                Text(
                    line, style = MaterialTheme.typography.bodySmall,
                    color = if (outcome.mood == Outcome.Mood.Stuck) c.red else c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            QuietButton(onClick = onRun, enabled = a.on, color = c.accent) {
                Icon(HerdrIcons.Play, null, modifier = Modifier.size(16.dp))
                Text("Run now")
            }
        }
    }
}
