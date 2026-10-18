package com.makefunstuff.herdr.ui.routines

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.makefunstuff.herdr.core.board.AgentChoices
import com.makefunstuff.herdr.core.board.Automation
import com.makefunstuff.herdr.core.board.AutomationDraft
import com.makefunstuff.herdr.core.board.AutomationWords
import com.makefunstuff.herdr.core.board.BoardEvent
import com.makefunstuff.herdr.core.board.BoardTask
import com.makefunstuff.herdr.core.board.Schedule
import com.makefunstuff.herdr.core.board.Trigger
import com.makefunstuff.herdr.core.host.HostState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.design.BottomActionBar
import com.makefunstuff.herdr.ui.design.ErrorMessage
import com.makefunstuff.herdr.ui.design.FieldLabel
import com.makefunstuff.herdr.ui.design.HerdrChip
import com.makefunstuff.herdr.ui.design.HerdrField
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.InfoLine
import com.makefunstuff.herdr.ui.design.PrimaryButton
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.design.SegmentedPills
import com.makefunstuff.herdr.ui.design.SwitchRow
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.launch

/** A routine being made ([old] null, on [machineId], or any board's when [chooseMachine]) or changed. */
internal data class Editing(val machineId: String, val old: Automation?, val chooseMachine: Boolean = false)

/** The form's kinds of schedule, in its order. */
private enum class Kind(val label: String) { Every("Every"), Daily("Daily"), Days("On days"), Cron("Cron") }

/**
 * Making or changing a routine, full screen: its name (its tasks' title), what each task is to do,
 * the repo (fixed once made: the board keeps an automation where it was made), when it runs (a
 * schedule in the machine's time zone, or something that happens on the board), research only, and
 * the agent its tasks get. Saved with the board's own `automations add` / `change` and `set
 * worker.agent --automation`; a refusal shows the board's words and keeps the form.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RoutineEditor(
    e: Editing,
    machines: List<HostState>,
    shelves: Map<String, Shelf>,
    tasks: Map<String, List<BoardTask>>,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val old = e.old
    val start = old?.let(AutomationDraft::of)
    var machineId by remember { mutableStateOf(e.machineId) }
    var name by remember { mutableStateOf(start?.name.orEmpty()) }
    var prompt by remember { mutableStateOf(start?.prompt.orEmpty()) }
    var repo by remember { mutableStateOf(start?.repo.orEmpty()) }
    var research by remember { mutableStateOf(start?.research ?: false) }
    var agent by remember { mutableStateOf(start?.agent.orEmpty()) }
    // The trigger's parts. Untouched, an automation keeps the trigger it has, whatever its text.
    val oldTrigger = old?.trigger
    val oldSchedule = (oldTrigger as? Trigger.Schedule)?.let { Schedule.of(it.text) }
    var touched by remember { mutableStateOf(false) }
    var onEvent by remember { mutableStateOf(oldTrigger is Trigger.Event) }
    var kind by remember {
        mutableStateOf(
            when (oldSchedule) {
                is Schedule.Every -> Kind.Every
                is Schedule.Daily -> Kind.Daily
                is Schedule.Cron -> Kind.Cron
                else -> Kind.Days
            },
        )
    }
    var count by remember { mutableStateOf((oldSchedule as? Schedule.Every)?.count?.toString() ?: "2") }
    var hours by remember { mutableStateOf((oldSchedule as? Schedule.Every)?.hours ?: true) }
    var time by remember { mutableStateOf((oldSchedule as? Schedule.Daily)?.time ?: (oldSchedule as? Schedule.Days)?.time ?: "07:00") }
    var days by remember { mutableStateOf((oldSchedule as? Schedule.Days)?.days ?: listOf("mon")) }
    var cron by remember { mutableStateOf((oldSchedule as? Schedule.Cron)?.fields ?: "0 9 * * 1-5") }
    var event by remember { mutableStateOf((oldTrigger as? Trigger.Event)?.event ?: BoardEvent.Merged) }
    var everyRepo by remember { mutableStateOf((oldTrigger as? Trigger.Event)?.everyRepo ?: false) }
    var saving by remember { mutableStateOf(false) }
    var refusal by remember { mutableStateOf<String?>(null) }
    fun touch() { touched = true; refusal = null }

    val schedule: Schedule = when (kind) {
        Kind.Every -> Schedule.Every(count.trim().toIntOrNull() ?: 0, hours)
        Kind.Daily -> Schedule.Daily(time)
        Kind.Days -> Schedule.Days(days, time)
        Kind.Cron -> Schedule.Cron(cron)
    }
    val trigger: Trigger? = when {
        old != null && !touched -> oldTrigger
        onEvent -> Trigger.Event(event, everyRepo)
        else -> schedule.text()?.let { Trigger.Schedule(it) }
    }
    val label = machines.firstOrNull { it.machine.id == machineId }?.machine?.label ?: machineId
    val repos = remember(machineId, shelves, tasks) {
        (tasks[machineId].orEmpty().filter { it.machine.isEmpty() }.map { it.repo } + shelves[machineId]?.list.orEmpty().map { it.repo })
            .filter { it.isNotEmpty() }.distinct().sorted()
    }
    val choices by produceState<AgentChoices?>(null, machineId) {
        value = runCatching { container.hosts.session(machineId)?.awaitBoard()?.agents() }.getOrNull()
    }
    val agents = (listOfNotNull(choices?.worker) + choices?.workerFallback.orEmpty() + listOf(agent)).filter { it.isNotEmpty() }.distinct()
    val ready = name.isNotBlank() && prompt.isNotBlank() && trigger != null && (old != null || repo.isNotBlank()) && !saving

    fun save() {
        val t = trigger ?: return
        val draft = AutomationDraft(name, prompt, repo, t, research, agent)
        val session = container.hosts.session(machineId) ?: return
        saving = true
        refusal = null
        scope.launch {
            runCatching {
                val board = session.awaitBoard()
                if (old == null) board.addAutomation(draft) else board.changeAutomation(old, draft)
            }.onSuccess { onSaved(it) }.onFailure { refusal = it.message ?: "The board refused" }
            saving = false
        }
    }

    val c = Herdr.colors
    Scaffold(
        containerColor = c.ground,
        topBar = { HerdrTopBar(if (old == null) "New routine" else "Edit routine", subtitle = "$label board", onBack = onDismiss, backLabel = "Cancel") },
        bottomBar = {
            BottomActionBar {
                QuietButton(onClick = onDismiss) { Text("Cancel") }
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                PrimaryButton(if (saving) "Saving…" else if (old == null) "Add routine" else "Save", enabled = ready) { save() }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.screen, vertical = Space.m),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            refusal?.let { ErrorMessage(it) }
            if (e.chooseMachine && old == null && machines.size > 1) {
                FieldLabel("Machine")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    machines.forEach { h -> HerdrChip(h.machine.label, selected = machineId == h.machine.id, onClick = { machineId = h.machine.id; repo = ""; agent = "" }) }
                }
            }
            HerdrField(
                name, { name = it; refusal = null }, label = "Name", singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Bump dependencies") },
                supportingText = { Text("Each task it adds is called this.") },
            )
            HerdrField(
                prompt, { prompt = it; refusal = null }, label = "What each task is to do", minLines = 4, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Update outdated dependencies, run the tests and open a pull request.") },
            )
            if (old == null) {
                HerdrField(repo, { repo = it; refusal = null }, label = "Repo (name or path)", singleLine = true, modifier = Modifier.fillMaxWidth())
                if (repos.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    repos.forEach { r -> HerdrChip(r, selected = repo == r, onClick = { repo = r; refusal = null }) }
                }
            } else {
                Column {
                    InfoLine("Repo", old.repo)
                    Text("A routine stays in the repo it was made in.", style = MaterialTheme.typography.bodySmall, color = c.muted)
                }
            }

            FieldLabel("When")
            SegmentedPills(listOf("On a schedule", "On a board event"), if (onEvent) 1 else 0, { onEvent = it == 1; touch() })
            if (!onEvent) {
                SegmentedPills(Kind.entries.map { it.label }, kind.ordinal, { kind = Kind.entries[it]; touch() })
                when (kind) {
                    Kind.Every -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        HerdrField(
                            count, { count = it.filter(Char::isDigit).take(4); touch() }, singleLine = true, modifier = Modifier.width(96.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), label = "Every",
                        )
                        Column(Modifier.padding(top = Space.l)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                                HerdrChip("minutes", selected = !hours, onClick = { hours = false; touch() })
                                HerdrChip("hours", selected = hours, onClick = { hours = true; touch() })
                            }
                        }
                    }
                    Kind.Daily -> TimeField(time) { time = it; touch() }
                    Kind.Days -> {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                            Schedule.DAYS.forEach { d ->
                                val on = d in days
                                HerdrChip(d.replaceFirstChar { it.uppercase() }, selected = on, onClick = { days = if (on) days - d else days + d; touch() })
                            }
                        }
                        TimeField(time) { time = it; touch() }
                    }
                    Kind.Cron -> HerdrField(
                        cron, { cron = it; touch() }, label = "Cron", singleLine = true, modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text("Five fields: minute, hour, day of month, month, day of week.") },
                    )
                }
            } else {
                Column {
                    BoardEvent.entries.forEach { ev ->
                        com.makefunstuff.herdr.ui.design.ListRow(
                            ev.label, modifier = Modifier.padding(horizontal = 0.dp),
                            leading = { androidx.compose.material3.RadioButton(selected = event == ev, onClick = null) },
                            onClick = { event = ev; touch() },
                        )
                    }
                }
                SwitchRow("In any repo", if (everyRepo) "A task of any repo on this board" else "Only a task of ${repo.ifBlank { old?.repo ?: "its repo" }}", everyRepo) { everyRepo = it; touch() }
            }
            Text(
                trigger?.let { t ->
                    "Runs " + AutomationWords.inSentence(t) +
                        if (t is Trigger.Schedule) ", in $label's time zone." else "."
                } ?: "Not a schedule yet.",
                style = MaterialTheme.typography.bodyMedium, color = if (trigger == null) c.red else c.muted,
            )

            FieldLabel("Its tasks")
            SwitchRow("Research only", "A report instead of code changes", research) { research = it; refusal = null }
            FieldLabel("Agent")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                HerdrChip(choices?.worker?.takeIf { it.isNotEmpty() }?.let { "Board's own · $it" } ?: "Board's own", selected = agent.isEmpty(), onClick = { agent = "" })
                agents.filter { it != choices?.worker || agent == it }.forEach { a -> HerdrChip(a, selected = agent == a, onClick = { agent = a }) }
            }
            Text(
                "Each run adds a task to the board and puts an agent on it at once. A run is skipped while the task of the last one is still being worked on.",
                style = MaterialTheme.typography.bodySmall, color = c.muted,
            )
        }
    }
}

/** A time of day, typed: `07:00`; red while it isn't one. */
@Composable
private fun TimeField(value: String, onChange: (String) -> Unit) {
    HerdrField(
        value, { onChange(it.filter { ch -> ch.isDigit() || ch == ':' }.take(5)) }, label = "At", singleLine = true,
        modifier = Modifier.width(120.dp), isError = Schedule.clock(value) == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        placeholder = { Text("07:00") },
    )
}
