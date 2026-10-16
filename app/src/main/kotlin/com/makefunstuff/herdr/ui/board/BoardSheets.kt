package com.makefunstuff.herdr.ui.board

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.makefunstuff.herdr.ui.common.MarkdownText
import com.makefunstuff.herdr.ui.common.Pill
import com.makefunstuff.herdr.ui.common.openUrl
import com.makefunstuff.herdr.ui.common.toast
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.DangerButton
import com.makefunstuff.herdr.ui.design.ErrorMessage
import com.makefunstuff.herdr.ui.design.HerdrChip
import com.makefunstuff.herdr.ui.design.HerdrField
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrMenu
import com.makefunstuff.herdr.ui.design.HerdrShapes
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.IconTile
import com.makefunstuff.herdr.ui.design.InfoLine
import com.makefunstuff.herdr.ui.design.ListRow
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.design.MenuRow
import com.makefunstuff.herdr.ui.design.PrimaryButton
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.design.StatusBadge
import com.makefunstuff.herdr.ui.design.SwitchRow
import com.makefunstuff.herdr.ui.design.sheetColor
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Radius
import com.makefunstuff.herdr.ui.theme.Space
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
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close", onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            color = sheetColor,
            shape = HerdrShapes.Sheet,
            modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp).imePadding().heightIn(max = 640.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Column(Modifier.navigationBarsPadding()) {
                Box(
                    Modifier.align(Alignment.CenterHorizontally).padding(top = Space.m, bottom = Space.s).width(32.dp).height(4.dp)
                        .clip(HerdrShapes.Pill).background(Herdr.colors.fieldFocused),
                )
                Text(
                    title, style = MaterialTheme.typography.titleLarge, color = accent ?: Herdr.colors.text,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = Space.xl, end = Space.xl, top = Space.xs, bottom = Space.m).semantics { heading() },
                )
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = Space.xl),
                    verticalArrangement = Arrangement.spacedBy(Space.m), content = content,
                )
                if (buttons != null) Row(
                    Modifier.fillMaxWidth().padding(start = Space.l, end = Space.l, top = Space.l, bottom = Space.m),
                    horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.End), verticalAlignment = Alignment.CenterVertically, content = buttons,
                ) else Spacer(Modifier.height(Space.l))
            }
        }
    }
}

@Composable
private fun PanelCancel(onDismiss: () -> Unit) = QuietButton(onClick = onDismiss) { Text("Cancel") }

/** What a task's "More" offers: every action that fits, as rows with icons. */
@Composable
fun ActionsSheet(t: BoardTask, onDismiss: () -> Unit, onAction: (BoardAction) -> Unit, onEdit: () -> Unit, onOpen: (() -> Unit)?) {
    HerdrMenu(expanded = true, onDismissRequest = onDismiss, title = t.title, subtitle = "${t.name} · ${t.status.label}") {
        if (onOpen != null) MenuRow("Open details", HerdrIcons.Description, onOpen)
        BoardActions.forTask(t).forEach { a ->
            MenuRow(a.label.removeSuffix("…"), a.icon(), { onAction(a) }, destructive = a.destructive)
        }
        MenuRow("Edit title or description", HerdrIcons.Edit, onEdit)
    }
}

/** Each action's icon, so the sheet's rows line up and read at a glance. */
private fun BoardAction.icon(): ImageVector = when (this) {
    BoardAction.Delegate -> HerdrIcons.PlayArrow
    BoardAction.Tell, BoardAction.SendPlanBack -> HerdrIcons.ChatBubble
    BoardAction.Stop -> HerdrIcons.StopCircle
    BoardAction.Retry -> HerdrIcons.RestartAlt
    BoardAction.Fix -> HerdrIcons.Send
    BoardAction.Review -> HerdrIcons.Rule
    BoardAction.Finish -> HerdrIcons.TaskAlt
    BoardAction.Approve, BoardAction.ApprovePlan -> HerdrIcons.CheckCircle
    BoardAction.ApproveAnyway -> HerdrIcons.Warning
    BoardAction.Pick -> HerdrIcons.Checklist
    BoardAction.Archive -> HerdrIcons.Archive
    BoardAction.Restore -> HerdrIcons.Unarchive
    BoardAction.Clear -> HerdrIcons.Delete
    BoardAction.Pin -> HerdrIcons.PushPin
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
    TaskDetailContent(t, details, problem, busy, onBack, onAction, onPick, onMore)
}

/** The screen itself, from data alone: what [TaskDetailScreen] draws once it has read `details`. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaskDetailContent(
    t: BoardTask,
    d: TaskDetails?,
    problem: String?,
    busy: String?,
    onBack: () -> Unit,
    onAction: (BoardAction) -> Unit,
    onPick: (String) -> Unit,
    onMore: () -> Unit,
) {
    val context = LocalContext.current
    val primary = BoardActions.primary(t)
    val color = t.status.color()
    val c = Herdr.colors
    Scaffold(
        containerColor = c.ground,
        topBar = {
            HerdrTopBar(t.name, onBack = onBack, backLabel = "Back to the board") {
                BarIcon(HerdrIcons.MoreVert, "More actions", onMore)
            }
        },
        bottomBar = {
            if (primary != null || busy != null) Column(
                Modifier.fillMaxWidth().background(c.ground).navigationBarsPadding().padding(horizontal = Space.screen, vertical = Space.m),
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                busy?.let {
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth().height(3.dp).clip(HerdrShapes.Pill).semantics { contentDescription = "Running: $it" },
                        color = c.accent, trackColor = c.field,
                    )
                    Text("$it…", style = MaterialTheme.typography.labelMedium, color = c.muted)
                }
                if (primary != null) {
                    if (primary.destructive) DangerButton(primary.label, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = busy == null) { onAction(primary) }
                    else PrimaryButton(primary.label, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = busy == null) { onAction(primary) }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.screen).padding(top = Space.xs, bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Text(t.title, style = MaterialTheme.typography.headlineMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    StatusBadge(t.status.label, color)
                    if (t.research) Pill("research")
                    if (t.agent.isNotEmpty()) Pill(t.agent)
                    if (t.pinned) Pill("pinned")
                    d?.branch?.takeIf { it.isNotEmpty() }?.let { Pill(it) }
                }
            }
            // Status and next step.
            val step = nextStep(t)
            val doing = d?.msg?.ifEmpty { t.msg } ?: t.msg
            if (step != null || doing.isNotEmpty() || t.progress != null) QuietCard(
                padded = true,
                color = if (t.needsYou) c.tint(color).compositeOver(c.ground) else c.card,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Text(if (t.needsYou) "Needs you" else "Status", style = MaterialTheme.typography.titleSmall, color = color, modifier = Modifier.semantics { heading() })
                    if (step != null) Text(step, style = MaterialTheme.typography.bodyLarge)
                    if (doing.isNotEmpty() && d?.failure?.title?.startsWith(doing.trim().trimEnd('.'), ignoreCase = true) != true) MarkdownText(doing, style = MaterialTheme.typography.bodyMedium, color = c.muted)
                    t.progress?.let { p ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Progress(p, color, Modifier.weight(1f))
                            Text("$p%" + (t.eta?.let { " · ${it}m left" } ?: ""), style = MaterialTheme.typography.labelMedium, color = c.faint, modifier = Modifier.padding(start = Space.m))
                        }
                    }
                    if (d?.queue?.isNotEmpty() == true) Text(d.queue, style = MaterialTheme.typography.bodySmall, color = c.muted)
                    if (d != null && d.waitsFor.isNotEmpty()) Text("Waits for: ${d.waitsFor.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = c.muted)
                }
            }
            problem?.let { ErrorMessage(it) }
            if (d == null && problem == null) LoadingState("Reading the task")
            if (d != null) {
                d.failure?.let { f ->
                    Card(f.title.ifEmpty { "Something went wrong" }, BoardStatus.Error.color()) {
                        if (f.detail.isNotEmpty()) MarkdownText(f.detail, style = MaterialTheme.typography.bodyMedium)
                        if (f.next.isNotEmpty()) {
                            Text("Next step", style = MaterialTheme.typography.titleSmall, color = c.muted, modifier = Modifier.padding(top = Space.xs))
                            MarkdownText(f.next, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (d.attempts.isNotEmpty()) Section(if (t.status == BoardStatus.PickOne) "Pick one of ${d.attempts.size}" else "Attempts", startOpen = true) {
                    d.attempts.forEach { a ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            IconTile(letter = a.letter)
                            Column(Modifier.weight(1f).padding(start = Space.m)) {
                                Text(a.agent, style = MaterialTheme.typography.titleMedium)
                                Text(listOf(a.standing, a.stat).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.muted)
                            }
                            if (t.status == BoardStatus.PickOne) PrimaryButton("Pick ${a.letter}", enabled = busy == null) { onPick(a.letter) }
                            else if (a.report.isNotEmpty()) QuietButton(onClick = { context.openUrl(a.report) }, color = c.accent) { Text("Report") }
                        }
                    }
                    if (d.comparison.isNotEmpty()) MarkdownText(d.comparison, style = MaterialTheme.typography.bodyMedium, color = c.muted)
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
                if (d.handovers.isNotEmpty()) Section("Handovers · ${d.handovers.size}", startOpen = false) { d.handovers.forEach { MarkdownText("↪ $it", style = MaterialTheme.typography.bodyMedium) } }
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
    QuietCard(onClick = onClick, onClickLabel = "Open $label in the browser") {
        ListRow(
            label, subtitle = value,
            leading = { IconTile(HerdrIcons.Link) },
            trailing = { Icon(HerdrIcons.OpenInNew, null, tint = Herdr.colors.faint, modifier = Modifier.size(20.dp)) },
        )
    }
}

@Composable
private fun UsageRow(label: String, used: String, limit: String) = InfoLine(label, "$used used · limit $limit")

@Composable
private fun Card(title: String, color: Color, content: @Composable () -> Unit) {
    QuietCard(padded = true) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(HerdrIcons.Error, null, tint = color, modifier = Modifier.size(20.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = Space.s))
            }
            content()
        }
    }
}

/** A titled part of the task that folds away: a 48dp heading row with a chevron, its content under it. */
@Composable
private fun Section(title: String, startOpen: Boolean = true, collapseLong: Boolean = false, content: @Composable () -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(startOpen) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s), modifier = Modifier.animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(onClickLabel = if (open) "Collapse $title" else "Expand $title") { open = !open }
                .semantics { heading() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(if (open) HerdrIcons.ExpandLess else HerdrIcons.ExpandMore, if (open) "Collapse" else "Expand", tint = Herdr.colors.muted)
        }
        if (open) content()
    }
}

@Composable
private fun severityColor(s: String) = when (s) {
    "critical", "blocker", "major" -> Herdr.colors.red
    "minor" -> Herdr.colors.amber
    else -> Herdr.colors.grey
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
    val c = Herdr.colors
    val passed = r.pass == true && r.error.isEmpty()
    QuietCard(padded = true, modifier = Modifier.animateContentSize()) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable(onClickLabel = if (open) "Collapse review" else "Expand review") { open = !open },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (passed) HerdrIcons.CheckCircle else HerdrIcons.Error, null, tint = if (passed) c.green else c.red, modifier = Modifier.size(20.dp))
                Text(
                    "Round ${r.round} · $verdict" + (if (findings.isNotEmpty()) " · ${findings.size} finding${if (findings.size == 1) "" else "s"}" else "") +
                        if (r.by.isNotEmpty()) " · ${r.by.substringAfterLast('/')}" else "",
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).padding(start = Space.s),
                )
                Icon(if (open) HerdrIcons.ExpandLess else HerdrIcons.ExpandMore, null, tint = c.muted)
            }
            if (!open && findings.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                findings.groupBy { it.severity.ifEmpty { "finding" } }.forEach { (sev, l) -> Pill("${l.size} $sev", color = severityColor(sev)) }
            }
            if (open) {
                if (r.error.isNotEmpty()) MarkdownText(r.error, style = MaterialTheme.typography.bodyMedium, color = c.red)
                if (r.text.isNotEmpty()) MarkdownText(r.text, style = MaterialTheme.typography.bodyMedium)
                findings.forEach { FindingView(it) }
                r.limitations.forEach { MarkdownText("Limit: $it", style = MaterialTheme.typography.labelMedium, color = c.muted) }
            }
        }
    }
}

@Composable
internal fun FindingView(f: Finding) {
    val c = Herdr.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.m)).background(c.field).padding(Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
            StatusBadge(f.severity.ifEmpty { "finding" }, severityColor(f.severity))
            Text(f.id, style = MaterialTheme.typography.labelMedium, color = c.faint)
        }
        MarkdownText(f.subject, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
        if (f.where.isNotEmpty()) MarkdownText(f.where, style = MaterialTheme.typography.labelMedium, color = c.muted)
        if (f.proof.isNotEmpty()) MarkdownText(f.proof, style = MaterialTheme.typography.bodyMedium)
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
                    PrimaryButton("Send", enabled = text.isNotBlank()) { onWords(pending, text.trim()) }
                },
            ) {
                HerdrField(
                    text, { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 3,
                    placeholder = { Text(if (pending.action == BoardAction.SendPlanBack) "What should change in the plan?" else "Message") },
                )
            }
        }
        is Pending.Confirm -> BoardPanel(
            "${pending.action.label.removeSuffix("…")}?", onDismiss,
            buttons = {
                PanelCancel(onDismiss)
                DangerButton(pending.action.label.removeSuffix("…")) { onConfirm(pending) }
            },
        ) { Text(confirmText(pending), style = MaterialTheme.typography.bodyMedium, color = Herdr.colors.muted) }
        is Pending.Anyway -> {
            val details by produceState<Result<TaskDetails>?>(null, pending.task.id) {
                value = runCatching { session.awaitBoard().details(pending.task.id) }
            }
            var sure by remember { mutableStateOf(false) }
            BoardPanel(
                "Approve ${pending.task.name} anyway?", onDismiss,
                buttons = {
                    PanelCancel(onDismiss)
                    DangerButton("Approve anyway", enabled = sure) { onAnyway(pending) }
                },
            ) {
                Text("A check on this work failed. Approving merges it as it is.", color = Herdr.colors.muted)
                val d = details
                when {
                    d == null -> LoadingState("Reading what failed")
                    d.isFailure -> ErrorMessage(d.exceptionOrNull()?.message.orEmpty())
                    else -> {
                        val x = d.getOrThrow()
                        x.failure?.let { f -> MarkdownText(listOf("**${f.title}**", f.detail).filter { it.isNotEmpty() }.joinToString("\n\n"), color = Herdr.colors.red) }
                        x.reviews.lastOrNull()?.let { r ->
                            if (r.text.isNotEmpty()) MarkdownText(r.text, style = MaterialTheme.typography.bodySmall)
                            r.findings.forEach { FindingView(it) }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(Radius.m)).clickable { sure = !sure }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(sure, { sure = it })
                    Text("I know it failed, merge it anyway", style = MaterialTheme.typography.bodyLarge)
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
                    PrimaryButton(enabled = !busy && title.isNotBlank() && loaded, onClick = {
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
                HerdrField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
                HerdrField(desc, { desc = it }, label = { Text("Description (Markdown)") }, enabled = loaded, minLines = 4, modifier = Modifier.fillMaxWidth())
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
                    PrimaryButton(enabled = !busy && title.isNotBlank(), onClick = {
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
                HerdrField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
                HerdrField(repo, { repo = it }, label = { Text("Repo (name or path)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (repos.isNotEmpty()) FlowRowChips(repos, repo) { repo = it }
                HerdrField(desc, { desc = it }, label = { Text("Description (Markdown)") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                SwitchRow("Research only", "A report instead of code changes", research) { research = it }
                SwitchRow("Start an agent now", null, start) { start = it }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowChips(repos: List<String>, selected: String, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        repos.forEach { r -> HerdrChip(r, selected = selected == r, onClick = { onPick(r) }) }
    }
}

private fun confirmText(p: Pending.Confirm): String = when (p.action) {
    BoardAction.Stop -> "Stops ${p.task.name}'s agent. Its work is kept."
    BoardAction.Finish -> "Takes ${p.task.name}'s work as it is, without the agent's summary."
    BoardAction.Clear -> "Removes ${p.task.name} from the board. Work already merged or kept stays."
    else -> "${p.action.label} on ${p.task.name}."
}
