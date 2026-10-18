package com.makefunstuff.herdr.ui.routines

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.makefunstuff.herdr.core.board.AutomationWords
import com.makefunstuff.herdr.core.board.BoardStatus
import com.makefunstuff.herdr.core.board.BoardTask
import com.makefunstuff.herdr.core.board.Firing
import com.makefunstuff.herdr.core.board.Outcome
import com.makefunstuff.herdr.core.board.Trigger
import com.makefunstuff.herdr.ui.board.BusyLine
import com.makefunstuff.herdr.ui.board.color
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.MarkdownText
import com.makefunstuff.herdr.ui.common.StatusDot
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.ErrorMessage
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrMenu
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.InfoLine
import com.makefunstuff.herdr.ui.design.ListRow
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.design.MenuRow
import com.makefunstuff.herdr.ui.design.PrimaryButton
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.design.RowDivider
import com.makefunstuff.herdr.ui.design.SecondaryButton
import com.makefunstuff.herdr.ui.design.SectionHeading
import com.makefunstuff.herdr.ui.design.SwitchRow
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * One routine, full screen over the list: its switch with when it next fires, how its last run went,
 * Run now and Edit, what each task is told, how its tasks run, and every firing the board kept
 * (`automations show`), each opening the task it made.
 */
@Composable
internal fun RoutineDetail(
    r: Ref,
    now: Long,
    busy: String?,
    task: (String) -> BoardTask?,
    chatOf: (String) -> String?,
    onBack: () -> Unit,
    onTurn: (Boolean) -> Unit,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onTask: (String) -> Unit,
) {
    BackHandler(onBack = onBack)
    val container = LocalContainer.current
    val owner = LocalLifecycleOwner.current
    val a = r.automation
    var firings by remember { mutableStateOf<List<Firing>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    // Read again when the list's copy changes (after Run now, a pause, an edit), and every little while.
    LaunchedEffect(r.machineId, a) {
        val session = container.hosts.session(r.machineId) ?: return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    firings = session.awaitBoard().automation(a.id).second
                    problem = null
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    problem = t.message ?: "Couldn't read its firings"
                }
                delay(15_000)
            }
        }
    }
    val c = Herdr.colors
    val outcome = Outcome.of(a, a.last?.task?.let(task), now)
    val hasChat = outcome.task.isNotEmpty() && chatOf(outcome.task) != null
    Scaffold(
        containerColor = c.ground,
        topBar = {
            HerdrTopBar(a.name, subtitle = r.about(across = false), onBack = onBack) {
                BarIcon(HerdrIcons.Pencil, "Edit", onEdit)
                BarIcon(HerdrIcons.Ellipsis, "More", { menu = true })
                HerdrMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuRow("Remove", HerdrIcons.Trash2, { menu = false; onRemove() }, destructive = true)
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = Space.screen, end = Space.screen, top = Space.s, bottom = Space.xxl),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            busy?.let { b -> item(key = "busy") { BusyLine(b) } }
            item(key = "switch") {
                QuietCard(padded = true) {
                    SwitchRow(
                        if (a.on) "On" else "Paused",
                        when {
                            !a.on && a.offWhy.isNotEmpty() -> a.offWhy
                            !a.on -> "It never fires while paused, and keeps everything."
                            a.next != null -> "Next run ${AutomationWords.until(a.next!!, now)} · ${AutomationWords.trigger(a.trigger)}"
                            a.trigger is Trigger.Event -> "Runs ${AutomationWords.inSentence(a.trigger)}"
                            else -> null
                        },
                        a.on, onChange = onTurn,
                    )
                    Row(
                        Modifier.padding(top = Space.s),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        StatusDot(outcome.mood.color(), size = 7)
                        Text(
                            outcome.text, style = MaterialTheme.typography.bodyMedium,
                            color = if (outcome.mood == Outcome.Mood.Stuck) c.red else c.muted,
                            modifier = Modifier.padding(start = Space.s),
                        )
                    }
                    Row(Modifier.padding(top = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        PrimaryButton(onClick = onRun, enabled = a.on && busy == null) {
                            Icon(HerdrIcons.Play, null, modifier = Modifier.size(16.dp))
                            Text("Run now")
                        }
                        if (outcome.task.isNotEmpty()) SecondaryButton(if (hasChat) "Open its chat" else "Open last task") { onTask(outcome.task) }
                    }
                }
            }
            item(key = "prompt") {
                SectionHeading("What each task is to do", top = Space.l)
                QuietCard(padded = true) { MarkdownText(a.prompt) }
            }
            item(key = "how") {
                SectionHeading("How its tasks run", top = Space.l)
                QuietCard(padded = true) {
                    InfoLine("Repo", a.repo)
                    if (a.folder.isNotEmpty()) InfoLine("Folder", a.folder.replace(Regex("^/home/[^/]+"), "~"), mono = true)
                    InfoLine("Agent", a.agent.ifEmpty { "The board's own" })
                    InfoLine("Kind", if (a.research) "Research only: a report" else "Changes, with a review")
                    a.settings.filterKeys { it != "worker.agent" && it != "research.only" }.forEach { (k, v) -> InfoLine(k, v, mono = true) }
                }
            }
            item(key = "firings-h") {
                SectionHeading(firings?.let { "Firings · ${it.size}" } ?: "Firings", top = Space.l)
                problem?.let { ErrorMessage(it) }
                if (firings == null && problem == null) LoadingState("Reading its firings")
                if (firings?.isEmpty() == true) Text("It hasn't run yet.", style = MaterialTheme.typography.bodyMedium, color = c.muted)
            }
            val list = firings.orEmpty()
            if (list.isNotEmpty()) item(key = "firings") {
                QuietCard {
                    list.forEachIndexed { i, f ->
                        if (i > 0) RowDivider()
                        FiringRow(f, task(f.task), now, onOpen = if (f.task.isNotEmpty()) ({ onTask(f.task) }) else null)
                    }
                }
            }
        }
    }
}

/** One firing: what it made (and how that task stands) or why it was skipped; what fired it, and when. */
@Composable
private fun FiringRow(f: Firing, t: BoardTask?, now: Long, onOpen: (() -> Unit)?) {
    val c = Herdr.colors
    val name = t?.name ?: f.task
    val title = when {
        f.task.isEmpty() -> "Skipped${if (f.times > 1) " ×${f.times}" else ""}: ${f.skipped}"
        f.note.isNotEmpty() && (t == null || t.status == BoardStatus.Todo) -> "Added $name, no agent started"
        t != null -> "$name · ${t.status.label}"
        else -> "Added $name"
    }
    val cause = when (f.cause) {
        "run" -> "Run by hand"
        "schedule" -> "On schedule"
        else -> f.what.replaceFirstChar { it.uppercase() }
    }
    val color = when {
        f.task.isEmpty() -> c.idle
        f.note.isNotEmpty() && (t == null || t.status == BoardStatus.Todo) -> c.red
        t != null -> t.status.color()
        else -> c.idle
    }
    ListRow(
        title, subtitle = "$cause · ${AutomationWords.ago(f.at, now)}", subtitleLines = 1, titleLines = 3,
        leading = { StatusDot(color, size = 8) },
        chevron = onOpen != null, onClickLabel = "Open the task", onClick = onOpen,
    )
}
