package com.makefunstuff.herdr.ui.board

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

/**
 * The board's own bottom panel: a scrim and a rounded surface drawn inside the screen (no platform dialog or
 * Material sheet window), with a title, scrolling content and a fixed row of buttons.
 */
@Composable
fun BoardPanel(
    title: String,
    onDismiss: () -> Unit,
    accent: Color? = null,
    buttons: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onDismiss)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close", onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp).imePadding().heightIn(max = 640.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Column(Modifier.navigationBarsPadding()) {
                Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp).width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.outlineVariant))
                Text(
                    title, style = MaterialTheme.typography.titleMedium, color = accent ?: MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp).semantics { heading() },
                )
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp), content = content,
                )
                if (buttons != null) Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically, content = buttons,
                ) else Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun PanelCancel(onDismiss: () -> Unit) = TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }

/** What a task's "More" offers: every action that fits, as plain rows. */
@Composable
fun ActionsSheet(t: BoardTask, onDismiss: () -> Unit, onAction: (BoardAction) -> Unit, onEdit: () -> Unit, onOpen: (() -> Unit)?) {
    BoardPanel(t.name + " · " + t.title, onDismiss) {
        if (onOpen != null) Entry("Open details", onOpen)
        BoardActions.forTask(t).forEach { a -> Entry(a.label.removeSuffix("…"), { onAction(a) }, if (a.destructive) BoardStatus.Error.color() else Color.Unspecified) }
        Entry("Edit title or description", onEdit)
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun Entry(text: String, onClick: () -> Unit, color: Color = Color.Unspecified) {
    Text(
        text, color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
            .padding(horizontal = 8.dp).wrapContentHeight(Alignment.CenterVertically),
    )
}

/** What a person should do about a task in this status, in a sentence. */
private fun nextStep(t: BoardTask): String? = when (t.status) {
    BoardStatus.Approval -> "The work is done and checked. Approving merges it."
    BoardStatus.ReviewFailed -> "A review found problems. Send them back to the agent, or approve anyway."
    BoardStatus.PlanReady -> "The agent made a plan. Approve it to let work start, or send it back."
    BoardStatus.PickOne -> "Several attempts finished. Pick the one to keep."
    BoardStatus.Asks, BoardStatus.AsksOk -> "The agent is waiting for your answer."
    BoardStatus.NeedsLogin -> "The agent needs a login on the machine. Tell it once that's done."
    BoardStatus.Stopped -> "The agent stopped (limit reached or stopped by hand). Retry, or finish with what's there."
    BoardStatus.Error -> "Something went wrong. See below, then retry or send the failure to the agent."
    BoardStatus.Todo -> "Not started. Start an agent when ready."
    else -> null
}

/** A task, full screen: status and next step first, then everything `details <id>` knows in sections. Kept current while open. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaskDetailScreen(
    session: HostSession,
    t: BoardTask,
    onBack: () -> Unit,
    busy: String? = null,
    onAction: (BoardAction) -> Unit,
    onPick: (String) -> Unit,
    onMore: () -> Unit,
) {
    BackHandler(onBack = onBack)
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
    val color = t.status.color()
    val d = details
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to the board") } },
                title = { Text(t.name, fontWeight = FontWeight.Bold) },
                actions = { IconButton(onClick = onMore) { Icon(Icons.Filled.MoreVert, "More actions") } },
            )
        },
        bottomBar = {
            if (primary != null || busy != null) Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    busy?.let {
                        LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Running: $it" })
                        Text("$it…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (primary != null) Button(
                        onClick = { onAction(primary) }, enabled = busy == null,
                        colors = if (primary.destructive) ButtonDefaults.buttonColors(containerColor = DangerButton, contentColor = Color.White) else ButtonDefaults.buttonColors(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) { Text(primary.label, style = MaterialTheme.typography.titleSmall) }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(t.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(t.status.label, color = color)
                if (t.research) Pill("research")
                if (t.agent.isNotEmpty()) Pill(t.agent)
                if (t.pinned) Pill("pinned")
                d?.branch?.takeIf { it.isNotEmpty() }?.let { Pill(it) }
            }
            // Status and next step.
            val step = nextStep(t)
            val doing = d?.msg?.ifEmpty { t.msg } ?: t.msg
            if (step != null || doing.isNotEmpty() || t.progress != null) Surface(
                color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = false) { },
            ) {
                Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(5.dp).fillMaxHeight().background(color))
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (t.needsYou) "Needs you" else "Status", style = MaterialTheme.typography.labelLarge, color = color, modifier = Modifier.semantics { heading() })
                        if (step != null) Text(step, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        if (doing.isNotEmpty() && d?.failure?.title?.startsWith(doing.trim().trimEnd('.'), ignoreCase = true) != true) MarkdownText(doing, style = MaterialTheme.typography.bodyMedium)
                        t.progress?.let { p ->
                            LinearProgressIndicator(progress = { p / 100f }, color = color, modifier = Modifier.fillMaxWidth())
                            Text("$p%" + (t.eta?.let { " · ${it}m left" } ?: ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (d?.queue?.isNotEmpty() == true) Text(d.queue, style = MaterialTheme.typography.bodySmall)
                        if (d != null && d.waitsFor.isNotEmpty()) Text("Waits for: ${d.waitsFor.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            problem?.let { Text(it, color = BoardStatus.Error.color(), style = MaterialTheme.typography.bodySmall) }
            if (d == null && problem == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (d != null) {
                d.failure?.let { f ->
                    Card(f.title.ifEmpty { "Something went wrong" }, BoardStatus.Error.color()) {
                        if (f.detail.isNotEmpty()) MarkdownText(f.detail, style = MaterialTheme.typography.bodySmall)
                        if (f.next.isNotEmpty()) {
                            Text("Next step", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            MarkdownText(f.next, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (d.attempts.isNotEmpty()) Section(if (t.status == BoardStatus.PickOne) "Pick one of ${d.attempts.size}" else "Attempts", startOpen = true) {
                    d.attempts.forEach { a ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
                if (d.plan.isNotEmpty()) Section("Plan", startOpen = t.status == BoardStatus.PlanReady) { MarkdownText(d.plan, style = MaterialTheme.typography.bodyMedium) }
                if (d.reviews.isNotEmpty()) Section("Reviews · ${d.reviews.size}", startOpen = true) {
                    d.reviews.asReversed().forEachIndexed { i, r -> ReviewView(r, startOpen = i == 0) }
                }
                if (d.pr.isNotEmpty() || d.reportLink.isNotEmpty()) Section("Links", startOpen = true) {
                    if (d.pr.isNotEmpty()) LinkRow("Pull request", d.pr.substringAfter("github.com/")) { context.openUrl(d.pr) }
                    if (d.reportLink.isNotEmpty()) LinkRow("Research report", d.reportLink.substringAfter("://")) { context.openUrl(d.reportLink) }
                }
                if (d.description.isNotEmpty()) Section("Description", startOpen = true, collapseLong = true) { MarkdownText(d.description, style = MaterialTheme.typography.bodyMedium) }
                if (d.handovers.isNotEmpty()) Section("Handovers · ${d.handovers.size}", startOpen = false) { d.handovers.forEach { MarkdownText("↪ $it", style = MaterialTheme.typography.bodySmall) } }
                if (d.time != null || d.tokens != null) Section("Limits and usage", startOpen = false) {
                    d.time?.let { UsageRow("Time", it.used.ifEmpty { "0s" }, it.limit.ifEmpty { "none" }) }
                    d.tokens?.let { UsageRow("Tokens", it.used.ifEmpty { "0" }, it.limit.ifEmpty { "none" }) }
                }
            }
        }
    }
}

@Composable
private fun LinkRow(label: String, value: String, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "Open $label in the browser", onClick = onClick)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp).heightIn(min = 36.dp), verticalArrangement = Arrangement.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun UsageRow(label: String, used: String, limit: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text("$used used · limit $limit", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun Section(title: String, startOpen: Boolean = true, collapseLong: Boolean = false, content: @Composable () -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(startOpen) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(onClickLabel = if (open) "Collapse $title" else "Expand $title") { open = !open }
                .semantics { heading() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, if (open) "Collapse" else "Expand", tint = MaterialTheme.colorScheme.onSurfaceVariant)
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

@OptIn(ExperimentalLayoutApi::class)
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
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Round ${r.round} · $verdict" + (if (findings.isNotEmpty()) " · ${findings.size} finding${if (findings.size == 1) "" else "s"}" else "") +
                        if (r.by.isNotEmpty()) " · ${r.by.substringAfterLast('/')}" else "",
                    style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f),
                )
                Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null)
            }
            if (!open && findings.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                findings.groupBy { it.severity.ifEmpty { "finding" } }.forEach { (sev, l) -> Pill("${l.size} $sev", color = severityColor(sev)) }
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
internal fun FindingView(f: Finding) {
    Row(Modifier.padding(top = 4.dp).height(IntrinsicSize.Min)) {
    Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(severityColor(f.severity)))
    Column(Modifier.padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(f.severity.ifEmpty { "finding" }, color = severityColor(f.severity))
            Text(f.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        MarkdownText(f.subject, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
        if (f.where.isNotEmpty()) MarkdownText(f.where, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (f.proof.isNotEmpty()) MarkdownText(f.proof, style = MaterialTheme.typography.bodySmall)
    }
    }
}


/** The panels of a half-done action: words for the agent, a confirmation, an edit, a new task. */
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
            BoardPanel(
                if (pending.action == BoardAction.SendPlanBack) "Send the plan back" else "Tell ${pending.task.name}'s agent", onDismiss,
                buttons = {
                    PanelCancel(onDismiss)
                    Button(onClick = { onWords(pending, text.trim()) }, enabled = text.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) { Text("Send") }
                },
            ) {
                OutlinedTextField(
                    text, { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 3,
                    placeholder = { Text(if (pending.action == BoardAction.SendPlanBack) "What should change in the plan?" else "Message") },
                )
            }
        }
        is Pending.Confirm -> BoardPanel(
            "${pending.action.label.removeSuffix("…")}?", onDismiss, accent = MaterialTheme.colorScheme.error,
            buttons = {
                PanelCancel(onDismiss)
                Button(
                    onClick = { onConfirm(pending) }, modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DangerButton, contentColor = Color.White),
                ) { Text(pending.action.label.removeSuffix("…")) }
            },
        ) { Text(confirmText(pending), style = MaterialTheme.typography.bodyMedium) }
        is Pending.Anyway -> {
            val details by produceState<Result<TaskDetails>?>(null, pending.task.id) {
                value = runCatching { session.awaitBoard().details(pending.task.id) }
            }
            var sure by remember { mutableStateOf(false) }
            BoardPanel(
                "Approve ${pending.task.name} anyway?", onDismiss, accent = MaterialTheme.colorScheme.error,
                buttons = {
                    PanelCancel(onDismiss)
                    Button(
                        onClick = { onAnyway(pending) }, enabled = sure, modifier = Modifier.heightIn(min = 48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = DangerButton, contentColor = Color.White),
                    ) { Text("Approve anyway") }
                },
            ) {
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
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { sure = !sure }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(sure, { sure = it })
                    Text("I know it failed, merge it anyway")
                }
            }
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
            BoardPanel(
                "Edit ${pending.task.name}", onDismiss,
                buttons = {
                    PanelCancel(onDismiss)
                    Button(enabled = !busy && title.isNotBlank() && loaded, modifier = Modifier.heightIn(min = 48.dp), onClick = {
                        busy = true
                        scope.launch {
                            val b = session.awaitBoard()
                            runCatching {
                                if (title.trim() != pending.task.title) b.rename(pending.task.id, title.trim())
                                if (desc.trim() != orig.orEmpty().trim()) b.describe(pending.task.id, desc.trim())
                            }.onFailure { context.toast(it.message ?: "The board refused") }
                            onSaved()
                        }
                    }) { Text(if (busy) "Saving…" else "Save") }
                },
            ) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(desc, { desc = it }, label = { Text("Description (Markdown)") }, enabled = loaded, minLines = 4, modifier = Modifier.fillMaxWidth())
            }
        }
        Pending.Add -> {
            var title by remember { mutableStateOf("") }
            var repo by remember { mutableStateOf(repos.firstOrNull().orEmpty()) }
            var desc by remember { mutableStateOf("") }
            var research by remember { mutableStateOf(false) }
            var start by remember { mutableStateOf(false) }
            var busy by remember { mutableStateOf(false) }
            BoardPanel(
                "Add a task", onDismiss,
                buttons = {
                    PanelCancel(onDismiss)
                    Button(enabled = !busy && title.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp), onClick = {
                        busy = true
                        scope.launch {
                            runCatching { session.awaitBoard().add(title.trim(), repo.trim(), desc.trim(), research, start) }
                                .onSuccess { context.toast(it.ifEmpty { "Added" }) }
                                .onFailure { context.toast(it.message ?: "The board refused") }
                            onSaved()
                        }
                    }) { Text(if (busy) "Adding…" else if (start) "Add and start" else "Add") }
                },
            ) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(repo, { repo = it }, label = { Text("Repo (name or path)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (repos.isNotEmpty()) FlowRowChips(repos, repo) { repo = it }
                OutlinedTextField(desc, { desc = it }, label = { Text("Description (Markdown)") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) { Text("Research only", Modifier.weight(1f)); Switch(research, { research = it }) }
                Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) { Text("Start an agent now", Modifier.weight(1f)); Switch(start, { start = it }) }
            }
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

/** Dark enough for white text at WCAG AA (about 6.5:1), unlike the salmon status colour. */
private val DangerButton = Color(0xFFB3261E)
