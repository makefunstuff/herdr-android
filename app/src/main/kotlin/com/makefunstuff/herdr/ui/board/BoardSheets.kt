package com.makefunstuff.herdr.ui.board

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.makefunstuff.herdr.ui.common.MarkdownText
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.makefunstuff.herdr.core.board.BoardAction
import com.makefunstuff.herdr.core.board.BoardActions
import com.makefunstuff.herdr.core.board.BoardStatus
import com.makefunstuff.herdr.core.board.BoardTask
import com.makefunstuff.herdr.core.board.Finding
import com.makefunstuff.herdr.core.board.Review
import com.makefunstuff.herdr.core.board.TaskDetails
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.ui.common.Pill
import com.makefunstuff.herdr.ui.common.openUrl
import com.makefunstuff.herdr.ui.common.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What a task's long press offers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionsSheet(t: BoardTask, onDismiss: () -> Unit, onAction: (BoardAction) -> Unit, onEdit: () -> Unit, onOpen: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(t.name + " · " + t.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            Entry("Details", onOpen)
            BoardActions.forTask(t).forEach { a -> Entry(a.label, { onAction(a) }, if (a.destructive) BoardStatus.Error.color() else Color.Unspecified) }
            Entry("Edit title or description…", onEdit)
        }
    }
}

@Composable
private fun Entry(text: String, onClick: () -> Unit, color: Color = Color.Unspecified) {
    ListItem(
        headlineContent = { Text(text, color = color) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** `details <id>`, kept current while the sheet is open. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaskSheet(
    session: HostSession,
    t: BoardTask,
    onDismiss: () -> Unit,
    busy: String? = null,
    onAction: (BoardAction) -> Unit,
    onPick: (String) -> Unit,
    onEdit: () -> Unit,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var details by remember { mutableStateOf<TaskDetails?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(t.id, t.stage, t.state, t.msg) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    details = session.awaitBoard().details(t.id)
                    problem = null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    problem = e.message ?: "Couldn't read the task"
                }
                delay(8000)
            }
        }
    }
    val primary = BoardActions.primary(t)
    val acts = BoardActions.forTask(t).filter { it != primary }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(t.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Pill(t.status.label, color = t.status.color())
                if (t.research) Pill("research")
                if (t.agent.isNotEmpty()) Pill(t.agent)
            }
            Text(t.title, style = MaterialTheme.typography.titleLarge)
            val d = details
            val doing = d?.msg?.ifEmpty { t.msg } ?: t.msg
            problem?.let { Text(it, color = BoardStatus.Error.color(), style = MaterialTheme.typography.bodySmall) }
            if (d == null && problem == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (d != null) d.failure?.let { f ->
                Card(f.title.ifEmpty { "Something went wrong" }, BoardStatus.Error.color()) {
                    if (f.detail.isNotEmpty()) MarkdownText(f.detail, style = MaterialTheme.typography.bodySmall)
                    if (f.next.isNotEmpty()) {
                        Text("Next step", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        MarkdownText(f.next, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (doing.isNotEmpty()) Section("Doing now", startOpen = true) { MarkdownText(doing) }
            if (d?.queue?.isNotEmpty() == true) Text(d.queue, style = MaterialTheme.typography.bodySmall)
            if (d != null && d.waitsFor.isNotEmpty()) Text("Waits for: ${d.waitsFor.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
            if (d != null) {
                if (d.plan.isNotEmpty()) Section("Plan", startOpen = t.status == BoardStatus.PlanReady) { MarkdownText(d.plan, style = MaterialTheme.typography.bodySmall) }
                if (d.attempts.isNotEmpty()) Section(if (t.status == BoardStatus.PickOne) "Pick one of ${d.attempts.size}" else "Attempts", startOpen = true) {
                    d.attempts.forEach { a ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${a.letter} · ${a.agent}", fontWeight = FontWeight.SemiBold)
                                Text(listOf(a.standing, a.stat).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                            }
                            if (t.status == BoardStatus.PickOne) Button(onClick = { onPick(a.letter) }, enabled = busy == null, modifier = Modifier.heightIn(min = 48.dp)) { Text("Pick ${a.letter}") }
                            else if (a.report.isNotEmpty()) TextButton(onClick = { context.openUrl(a.report) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Report") }
                        }
                    }
                    if (d.comparison.isNotEmpty()) MarkdownText(d.comparison, style = MaterialTheme.typography.bodySmall)
                }
                if (d.reviews.isNotEmpty()) Section("Reviews · ${d.reviews.size}", startOpen = true) {
                    d.reviews.asReversed().forEachIndexed { i, r -> ReviewView(r, startOpen = i == 0) }
                }
                if (d.pr.isNotEmpty() || d.reportLink.isNotEmpty()) Section("Links", startOpen = true) {
                    if (d.pr.isNotEmpty()) OutlinedButton(onClick = { context.openUrl(d.pr) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Pull request ${d.pr.substringAfter("github.com/")}", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    if (d.reportLink.isNotEmpty()) OutlinedButton(onClick = { context.openUrl(d.reportLink) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Research report") }
                }
                if (d.description.isNotEmpty()) Section("Description", startOpen = d.description.length < 600) { MarkdownText(d.description, style = MaterialTheme.typography.bodySmall) }
                if (d.handovers.isNotEmpty()) Section("Handovers · ${d.handovers.size}", startOpen = false) { d.handovers.forEach { MarkdownText("↪ $it", style = MaterialTheme.typography.bodySmall) } }
                if (d.time != null || d.tokens != null) Section("Limits and usage", startOpen = false) {
                    d.time?.let { Text("Time: ${it.used.ifEmpty { "0s" }} used · limit ${it.limit.ifEmpty { "none" }}", style = MaterialTheme.typography.bodySmall) }
                    d.tokens?.let { Text("Tokens: ${it.used.ifEmpty { "0" }} used · limit ${it.limit.ifEmpty { "none" }}", style = MaterialTheme.typography.bodySmall) }
                }
                if (d.branch.isNotEmpty()) Text("Branch ${d.branch}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider()
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            busy?.let { Text("$it…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (primary != null) {
                Button(
                    onClick = { onAction(primary) }, enabled = busy == null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) { Text(primary.label, style = MaterialTheme.typography.titleSmall) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                (acts).forEach { a ->
                    OutlinedButton(
                        onClick = { onAction(a) }, enabled = busy == null, modifier = Modifier.heightIn(min = 48.dp),
                        colors = if (a.destructive) ButtonDefaults.outlinedButtonColors(contentColor = BoardStatus.Error.color()) else ButtonDefaults.outlinedButtonColors(),
                    ) { Text(a.label) }
                }
                OutlinedButton(onClick = onEdit, enabled = busy == null, modifier = Modifier.heightIn(min = 48.dp)) { Text("Edit…") }
            }
        }
        }
    }
}

@Composable
private fun Card(title: String, color: Color, content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = color)
            content()
        }
    }
}

@Composable
private fun Section(title: String, startOpen: Boolean = true, content: @Composable () -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(startOpen) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(onClickLabel = if (open) "Collapse $title" else "Expand $title") { open = !open }
                .semantics { heading() },
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(if (open) "▾" else "▸", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) content()
    }
}

private fun severityColor(s: String) = when (s) {
    "critical", "blocker", "major" -> BoardStatus.Error.color()
    "minor" -> BoardStatus.Working.color()
    else -> BoardStatus.Todo.color()
}

private val severityOrder = listOf("critical", "blocker", "major", "minor", "trivial")

@Composable
private fun ReviewView(r: Review, startOpen: Boolean = true) {
    val verdict = when {
        r.error.isNotEmpty() -> "failed"
        r.pass == true -> "passed"
        r.pass == false || r.meets == false -> "did not pass"
        else -> "in"
    }
    var open by rememberSaveable("review-${r.round}") { mutableStateOf(startOpen) }
    val findings = r.findings.sortedBy { severityOrder.indexOf(it.severity).let { i -> if (i < 0) 99 else i } }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable(onClickLabel = if (open) "Collapse review" else "Expand review") { open = !open },
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    "Round ${r.round} · $verdict" + (if (findings.isNotEmpty()) " · ${findings.size} finding${if (findings.size == 1) "" else "s"}" else "") +
                        if (r.by.isNotEmpty()) " · ${r.by.substringAfterLast('/')}" else "",
                    style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f),
                )
                Text(if (open) "▾" else "▸")
            }
            if (open) {
                if (r.error.isNotEmpty()) MarkdownText(r.error, style = MaterialTheme.typography.bodySmall, color = BoardStatus.Error.color())
                if (r.text.isNotEmpty()) MarkdownText(r.text, style = MaterialTheme.typography.bodySmall)
                findings.forEach { FindingView(it) }
                r.limitations.forEach { MarkdownText("Limit: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun FindingView(f: Finding) {
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Pill(f.severity.ifEmpty { "finding" }, color = severityColor(f.severity))
            Text(f.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        MarkdownText(f.subject, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
        if (f.where.isNotEmpty()) MarkdownText(f.where, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (f.proof.isNotEmpty()) MarkdownText(f.proof, style = MaterialTheme.typography.bodySmall)
    }
}

/** The dialogs of a half-done action: words for the agent, a confirmation, an edit, a new task. */
@Composable
fun BoardDialogs(
    pending: Pending?,
    session: HostSession,
    repos: List<String>,
    onDismiss: () -> Unit,
    onWords: (Pending.Words, String) -> Unit,
    onConfirm: (Pending.Confirm) -> Unit,
    onAnyway: (Pending.Anyway) -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    when (pending) {
        null -> {}
        is Pending.Words -> {
            var text by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(if (pending.action == BoardAction.SendPlanBack) "Send the plan back" else "Tell ${pending.task.name}'s agent") },
                text = {
                    OutlinedTextField(
                        text, { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 3,
                        placeholder = { Text(if (pending.action == BoardAction.SendPlanBack) "What should change in the plan?" else "Message") },
                    )
                },
                confirmButton = { TextButton(onClick = { onWords(pending, text.trim()) }, enabled = text.isNotBlank()) { Text("Send") } },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
            )
        }
        is Pending.Confirm -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("${pending.action.label.removeSuffix("…")}?") },
            text = { Text(confirmText(pending)) },
            confirmButton = { TextButton(onClick = { onConfirm(pending) }) { Text("Yes") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
        is Pending.Anyway -> {
            val details by produceState<Result<TaskDetails>?>(null, pending.task.id) {
                value = runCatching { session.awaitBoard().details(pending.task.id) }
            }
            var sure by remember { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Approve ${pending.task.name} anyway?") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("A check on this work failed. Approving merges it as it is.")
                        val d = details
                        when {
                            d == null -> Text("Reading what failed…")
                            d.isFailure -> Text(d.exceptionOrNull()?.message.orEmpty(), color = BoardStatus.Error.color())
                            else -> {
                                val x = d.getOrThrow()
                                x.failure?.let { f -> MarkdownText(listOf("**${f.title}**", f.detail).filter { it.isNotEmpty() }.joinToString("\n\n"), color = BoardStatus.Error.color()) }
                                x.reviews.lastOrNull()?.let { r ->
                                    if (r.text.isNotEmpty()) MarkdownText(r.text, style = MaterialTheme.typography.bodySmall)
                                    r.findings.forEach { FindingView(it) }
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().clickable { sure = !sure }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(sure, { sure = it })
                            Text("I know it failed, merge it anyway")
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { onAnyway(pending) }, enabled = sure) { Text("Approve anyway") } },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
            )
        }
        is Pending.Edit -> {
            var title by remember { mutableStateOf(pending.task.title) }
            var desc by remember { mutableStateOf("") }
            var loaded by remember { mutableStateOf(false) }
            var busy by remember { mutableStateOf(false) }
            val orig by produceState<String?>(null, pending.task.id) {
                value = runCatching { session.awaitBoard().details(pending.task.id).description }.getOrNull()
                if (!loaded) { desc = value.orEmpty(); loaded = true }
            }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Edit ${pending.task.name}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(desc, { desc = it }, label = { Text("Description") }, enabled = loaded, minLines = 4, modifier = Modifier.fillMaxWidth())
                    }
                },
                confirmButton = {
                    TextButton(enabled = !busy && title.isNotBlank() && loaded, onClick = {
                        busy = true
                        scope.launch {
                            val b = session.awaitBoard()
                            runCatching {
                                if (title.trim() != pending.task.title) b.rename(pending.task.id, title.trim())
                                if (desc.trim() != orig.orEmpty().trim()) b.describe(pending.task.id, desc.trim())
                            }.onFailure { context.toast(it.message ?: "The board refused") }
                            onSaved()
                        }
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
            )
        }
        Pending.Add -> {
            var title by remember { mutableStateOf("") }
            var repo by remember { mutableStateOf(repos.firstOrNull().orEmpty()) }
            var desc by remember { mutableStateOf("") }
            var research by remember { mutableStateOf(false) }
            var start by remember { mutableStateOf(false) }
            var busy by remember { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Add a task") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(repo, { repo = it }, label = { Text("Repo (name or path)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        if (repos.isNotEmpty()) {
                            FlowRowChips(repos, repo) { repo = it }
                        }
                        OutlinedTextField(desc, { desc = it }, label = { Text("Description") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("Research only", Modifier.weight(1f)); Switch(research, { research = it })
                        }
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("Start an agent now", Modifier.weight(1f)); Switch(start, { start = it })
                        }
                    }
                },
                confirmButton = {
                    TextButton(enabled = !busy && title.isNotBlank(), onClick = {
                        busy = true
                        scope.launch {
                            runCatching { session.awaitBoard().add(title.trim(), repo.trim(), desc.trim(), research, start) }
                                .onSuccess { context.toast(it.ifEmpty { "Added" }) }
                                .onFailure { context.toast(it.message ?: "The board refused") }
                            onSaved()
                        }
                    }) { Text(if (start) "Add and start" else "Add") }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowChips(repos: List<String>, selected: String, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repos.forEach { r -> FilterChip(selected == r, { onPick(r) }, label = { Text(r) }) }
    }
}

private fun confirmText(p: Pending.Confirm): String = when (p.action) {
    BoardAction.Stop -> "Stops ${p.task.name}'s agent. Its work is kept."
    BoardAction.Finish -> "Takes ${p.task.name}'s work as it is, without the agent's summary."
    BoardAction.Clear -> "Removes ${p.task.name} from the board. Work already merged or kept stays."
    else -> "${p.action.label} on ${p.task.name}."
}
