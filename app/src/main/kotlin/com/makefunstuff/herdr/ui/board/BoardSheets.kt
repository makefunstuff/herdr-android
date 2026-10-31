package com.makefunstuff.herdr.ui.board

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.makefunstuff.herdr.core.push.Watch
import com.makefunstuff.herdr.core.push.Watches
import com.makefunstuff.herdr.ui.design.MenuSection
import com.makefunstuff.herdr.core.board.BoardAction
import com.makefunstuff.herdr.core.board.BoardActions
import com.makefunstuff.herdr.core.board.BoardStatus
import com.makefunstuff.herdr.core.board.BoardTask
import com.makefunstuff.herdr.core.board.Finding
import com.makefunstuff.herdr.core.board.Review
import com.makefunstuff.herdr.core.board.TaskDetails
import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.board.TaskLine
import com.makefunstuff.herdr.core.chat.ModelUse
import com.makefunstuff.herdr.ui.design.AgentMark
import com.makefunstuff.herdr.ui.design.SecondaryButton
import androidx.compose.foundation.layout.PaddingValues
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
import com.makefunstuff.herdr.ui.design.InfoLine
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.design.MenuRow
import com.makefunstuff.herdr.ui.design.PrimaryButton
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.design.StatusBadge
import com.makefunstuff.herdr.ui.design.SwitchRow
import com.makefunstuff.herdr.ui.design.sheetColor
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.statusWord
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
        Modifier.fillMaxSize().background(Herdr.colors.scrim)
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
                    Modifier.align(Alignment.CenterHorizontally).padding(top = Space.m, bottom = Space.s).width(36.dp).height(4.dp)
                        .clip(HerdrShapes.Pill).background(Herdr.colors.lineStrong),
                )
                Text(
                    title, style = MaterialTheme.typography.titleLarge, color = accent ?: Herdr.colors.strong,
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
internal fun PanelCancel(onDismiss: () -> Unit) = QuietButton(onClick = onDismiss) { Text("Cancel") }

/**
 * What a task's "More" (and its long-press) offers: every action that fits, as rows with icons;
 * finished work also its review card; and, with [machineId], one-shot watches: tell me when its
 * checks pass, when it's done.
 */
@Composable
fun ActionsSheet(
    t: BoardTask,
    onDismiss: () -> Unit,
    onAction: (BoardAction) -> Unit,
    onEdit: () -> Unit,
    onOpen: (() -> Unit)?,
    onReview: (() -> Unit)? = null,
    machineId: String? = null,
) {
    val container = com.makefunstuff.herdr.ui.common.LocalContainer.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val st by container.push.state.collectAsStateWithLifecycle()
    HerdrMenu(expanded = true, onDismissRequest = onDismiss, title = t.name + " · " + t.title) {
        if (onOpen != null) MenuRow("Open details", HerdrIcons.FileText, onOpen)
        if (onReview != null) MenuRow("Open its review card", HerdrIcons.Layers, onReview)
        BoardActions.forTask(t).forEach { a ->
            MenuRow(a.label.removeSuffix("…"), a.icon(), { onAction(a) }, destructive = a.destructive)
        }
        MenuRow("Edit title or description", HerdrIcons.Pencil, onEdit)
        if (machineId != null) {
            val set = st.watches.filter { it.machineId == machineId && it.taskId == t.id }
            val offers = Watches.offers(machineId, t, System.currentTimeMillis()).filter { o -> set.none { it.id == o.id } }
            if (set.isNotEmpty() || offers.isNotEmpty()) MenuSection("Tell me once")
            set.forEach { w ->
                MenuRow("Stop: " + watchWords(w), HerdrIcons.BellOff, {
                    container.watchWork.remove(w.id)
                    context.toast("Watch removed")
                    onDismiss()
                })
            }
            offers.forEach { w ->
                MenuRow(watchWords(w).replaceFirstChar { it.uppercase() }, HerdrIcons.BellRing, {
                    container.watchWork.add(w)
                    context.toast("You'll hear once: " + watchWords(w))
                    onDismiss()
                })
            }
        }
    }
}

/** What a task's watch waits for, as its sheet row says it: "when its checks pass". */
private fun watchWords(w: Watch) = when (w.what) {
    Watch.What.ChecksPass -> "when its checks pass"
    Watch.What.TaskDone -> "when it's done"
    Watch.What.AgentDone -> "when it finishes"
}

/** Each action's icon, so the sheet's rows line up and read at a glance. */
internal fun BoardAction.icon(): ImageVector = when (this) {
    BoardAction.Delegate -> HerdrIcons.Play
    BoardAction.Tell, BoardAction.SendPlanBack -> HerdrIcons.MessageSquare
    BoardAction.Stop -> HerdrIcons.CircleStop
    BoardAction.Retry -> HerdrIcons.RotateCcw
    BoardAction.Fix -> HerdrIcons.Send
    BoardAction.Review -> HerdrIcons.ListChecks
    BoardAction.Finish -> HerdrIcons.CircleCheckBig
    BoardAction.Approve, BoardAction.ApprovePlan -> HerdrIcons.CircleCheck
    BoardAction.ApproveAnyway -> HerdrIcons.TriangleAlert
    BoardAction.Pick -> HerdrIcons.ListTodo
    BoardAction.Archive -> HerdrIcons.Archive
    BoardAction.Restore -> HerdrIcons.ArchiveRestore
    BoardAction.Clear -> HerdrIcons.Trash2
    BoardAction.Pin -> HerdrIcons.Pin
}

/** What a person should do about a task in this status, in a sentence. */
private fun nextStep(t: BoardTask): String? = when (t.status) {
    BoardStatus.Approval -> "The work is done and checked. Approving merges it."
    BoardStatus.ReviewFailed -> "A review found problems. Send them back to the agent, or approve anyway."
    BoardStatus.PlanReady -> "The agent made a plan. Approve it to let work start, or send it back."
    BoardStatus.PickOne -> "Several attempts finished. Pick the one to keep."
    BoardStatus.Asks, BoardStatus.AsksOk -> "The agent is waiting for your answer."
    BoardStatus.NeedsLogin -> "The agent needs a login on the machine. Sign in, then tell it once that's done."
    BoardStatus.Stopped -> "The agent stopped (limit reached or stopped by hand). Retry, or finish with what's there."
    BoardStatus.Error -> "Something went wrong. See below, then retry or send the failure to the agent."
    BoardStatus.Todo -> "Not started. Start an agent when ready."
    else -> null
}

/** Who works on a task, and how to reach its agent: its live chat when its pane is on the machine. */
data class TaskAgent(
    /** "claude · Opus 5.5"; empty while the task has had no agent. */
    val who: String,
    /** Its agent's status on the machine in words ("working"); empty when its pane is gone. */
    val live: String = "",
    /** Opens its agent's chat; null when no pane of its agent is on the machine. */
    val onOpen: (() -> Unit)? = null,
)

/** A task, full screen: what matters at a glance first, the rest folded under More. Kept current while open. */
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
    /** False while Back has something else to close first (the middle of a wide window, beside this board). */
    backEnabled: Boolean = true,
    /** Opens the code of the task's worktree, at its changes. */
    onCode: ((TaskDetails) -> Unit)? = null,
    /** Opens the quick commands of the task's worktree. */
    onQuick: ((TaskDetails) -> Unit)? = null,
    /** Opens the timeline of what its agents did. */
    onActivity: (() -> Unit)? = null,
    /** Its agent on the machine, when one of its panes is there: its status, its model, and a way to its chat. */
    live: AgentInfo? = null,
    onOpenAgent: (() -> Unit)? = null,
    /** Opens the agent logins of the machine its agent runs on, for a task that needs one. */
    onSignIn: (() -> Unit)? = null,
) {
    BackHandler(enabled = backEnabled, onBack = onBack)
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
    // Read once a task: the agents its settings give it (for the model), and finished work's closing note.
    val workers by produceState(emptyList<String>(), t.id) {
        value = runCatching { session.awaitBoard().agents(t.id).workers }.getOrDefault(emptyList())
    }
    val finished = t.status in setOf(BoardStatus.Approval, BoardStatus.ReviewFailed, BoardStatus.Done, BoardStatus.Stopped)
    val note by produceState("", t.id, finished) {
        if (finished) value = runCatching { session.awaitBoard().facts(t).note }.getOrDefault("")
    }
    val agent = details?.agent?.ifEmpty { null } ?: t.agent
    val model = live?.tokens?.get("model")?.takeIf { it.isNotBlank() }
    val who = when {
        agent.isEmpty() -> ""
        model != null -> "$agent · ${ModelUse.name(model) ?: model}"
        else -> TaskLine.who(agent, workers)
    }
    val reach = TaskAgent(who, live?.statusWord().orEmpty(), onOpenAgent.takeIf { live != null })
    TaskDetailContent(t, details, problem, busy, onBack, onAction, onPick, onMore, onCode, onQuick, onActivity, reach, note, onSignIn)
}

/** The actions a task's card offers beside its primary one, as buttons: telling, stopping, retrying. */
private val CardActions = setOf(
    BoardAction.Tell, BoardAction.Stop, BoardAction.Retry, BoardAction.Fix, BoardAction.Review, BoardAction.Finish,
    BoardAction.ApproveAnyway, BoardAction.SendPlanBack, BoardAction.Delegate,
)

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
    onCode: ((TaskDetails) -> Unit)? = null,
    onQuick: ((TaskDetails) -> Unit)? = null,
    onActivity: (() -> Unit)? = null,
    agent: TaskAgent = TaskAgent(d?.agent?.ifEmpty { null } ?: t.agent),
    /** The agent's closing note, for finished work. */
    note: String = "",
    onSignIn: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val primary = BoardActions.primary(t)
    val others = BoardActions.forTask(t).filter { it != primary && it in CardActions }.take(3)
    val color = t.status.color()
    val c = Herdr.colors
    Scaffold(
        containerColor = c.ground,
        topBar = {
            HerdrTopBar(t.name, onBack = onBack, backLabel = "Back to the board") {
                BarIcon(HerdrIcons.Ellipsis, "More actions", onMore)
            }
        },
        bottomBar = {
            if (primary != null || others.isNotEmpty() || busy != null) Column(
                Modifier.fillMaxWidth().background(c.ground).navigationBarsPadding().padding(horizontal = Space.screen, vertical = Space.m),
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                busy?.let {
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth().height(3.dp).clip(HerdrShapes.Pill).semantics { contentDescription = "Running: $it" },
                        color = c.accent, trackColor = c.line,
                    )
                    Text("$it…", style = MaterialTheme.typography.labelMedium, color = c.muted)
                }
                if (others.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    others.forEach { a ->
                        SecondaryButton(onClick = { onAction(a) }, modifier = Modifier.weight(1f), enabled = busy == null) {
                            Icon(a.icon(), null, Modifier.size(18.dp))
                            Text(a.short(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
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
                Text(t.title, style = MaterialTheme.typography.headlineSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    StatusBadge(t.status.word, color)
                    if (t.research) Pill("research")
                    if (t.pinned) Pill("pinned")
                }
            }
            // Status: the next step, the board's one line (its noise taken out), progress.
            val step = nextStep(t)
            val doing = remember(d?.msg, t.msg) { TaskLine.clean(d?.msg?.ifEmpty { t.msg } ?: t.msg) }
            val failing = d?.failure?.title?.startsWith(doing.trim().trimEnd('.'), ignoreCase = true) == true
            if (step != null || doing.isNotEmpty() || t.progress != null || t.eta != null) QuietCard(
                padded = true,
                color = if (t.needsYou) c.tint(color).compositeOver(c.ground) else c.card,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Text(if (t.needsYou) "Needs you" else "Status", style = MaterialTheme.typography.titleSmall, color = color, modifier = Modifier.semantics { heading() })
                    if (step != null) Text(step, style = MaterialTheme.typography.bodyLarge)
                    if (doing.isNotEmpty() && !failing) Preview(doing, "status", lines = 3, color = c.muted)
                    if (t.progress != null || t.eta != null) Row(verticalAlignment = Alignment.CenterVertically) {
                        t.progress?.let { Progress(it, color, Modifier.weight(1f)) } ?: Spacer(Modifier.weight(1f))
                        Text(
                            listOfNotNull(t.progress?.let { "$it%" }, t.eta?.let { "${it}m left" }).joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium, color = c.faint, modifier = Modifier.padding(start = Space.m),
                        )
                    }
                    if (d != null && d.waitsFor.isNotEmpty()) Text("Waits for ${d.waitsFor.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = c.muted)
                    if (t.status == BoardStatus.NeedsLogin && onSignIn != null) SecondaryButton(onSignIn) {
                        Icon(HerdrIcons.KeyRound, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(Space.xs))
                        Text("Sign in")
                    }
                }
            }
            problem?.let { ErrorMessage(it) }
            d?.failure?.let { f ->
                Card(f.title.ifEmpty { "Something went wrong" }, BoardStatus.Error.color()) {
                    if (f.detail.isNotEmpty()) Preview(f.detail, "failure", lines = 6)
                    if (f.next.isNotEmpty()) {
                        Text("Next step", style = MaterialTheme.typography.titleSmall, color = c.muted, modifier = Modifier.padding(top = Space.xs))
                        MarkdownText(f.next, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            // Who works on it, how its review went, and the way to its agent.
            if (agent.who.isNotEmpty() || d?.reviews?.isNotEmpty() == true) AgentPart(t, agent, d?.reviews.orEmpty(), onActivity)
            if (d == null && problem == null) LoadingState("Reading the task")
            if (d != null) {
                if (d.attempts.isNotEmpty()) Section(if (t.status == BoardStatus.PickOne) "Pick one of ${d.attempts.size}" else "Attempts", startOpen = true) {
                    d.attempts.forEach { a ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${a.letter} · ${a.agent}", style = MaterialTheme.typography.titleMedium)
                                Text(listOf(a.standing, a.stat).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.muted)
                            }
                            if (t.status == BoardStatus.PickOne) PrimaryButton("Pick ${a.letter}", enabled = busy == null) { onPick(a.letter) }
                            else if (a.report.isNotEmpty()) QuietButton(onClick = { context.openUrl(a.report) }, color = c.accent) { Text("Report") }
                        }
                    }
                    if (d.comparison.isNotEmpty()) MarkdownText(d.comparison, style = MaterialTheme.typography.bodyMedium, color = c.muted)
                }
                if (d.plan.isNotEmpty()) Section("Plan", startOpen = t.status == BoardStatus.PlanReady) { MarkdownText(d.plan, style = MaterialTheme.typography.bodyMedium) }
                if (d.pr.isNotEmpty() || d.reportLink.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    if (d.pr.isNotEmpty()) LinkRow("Pull request" + (prNumber(d.pr)?.let { " #$it" } ?: ""), d.pr.substringAfter("github.com/")) { context.openUrl(d.pr) }
                    if (d.reportLink.isNotEmpty()) LinkRow("Research report", d.reportLink.substringAfter("://")) { context.openUrl(d.reportLink) }
                }
                MorePart(t, d, note, onCode, onQuick, onActivity.takeIf { agent.who.isEmpty() })
            }
        }
    }
}

private fun prNumber(pr: String): String? = pr.substringAfterLast('/').takeIf { "/pull/" in pr && it.all(Char::isDigit) && it.isNotEmpty() }

/** An action's label on a button of the card: "Tell", "Stop", "Retry". */
private fun BoardAction.short(): String = when (this) {
    BoardAction.Tell -> "Tell"
    BoardAction.Stop -> "Stop"
    BoardAction.Fix -> "Send failure"
    BoardAction.Review -> "Send back"
    BoardAction.Finish -> "Finish"
    BoardAction.ApproveAnyway -> "Approve anyway"
    BoardAction.SendPlanBack -> "Send back"
    BoardAction.Delegate -> "Start"
    else -> label.removeSuffix("…")
}

/**
 * Who works on the task (its agent and model) and the last review's verdict, with the way to its agent:
 * Open agent while its pane is on the machine, else what its agents did.
 */
@Composable
private fun AgentPart(t: BoardTask, agent: TaskAgent, reviews: List<Review>, onActivity: (() -> Unit)?) {
    val c = Herdr.colors
    val last = reviews.maxByOrNull { it.round }
    var showReview by rememberSaveable(t.id, "review") { mutableStateOf(t.status == BoardStatus.ReviewFailed) }
    QuietCard(padded = true, modifier = Modifier.animateContentSize()) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
            if (agent.who.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                AgentMark(agent.who.substringBefore(" · "), size = 28.dp)
                Column(Modifier.weight(1f).padding(start = Space.m)) {
                    Text(agent.who, style = MaterialTheme.typography.titleSmall, color = c.strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            agent.live.isNotEmpty() -> "Its agent · ${agent.live}"
                            t.status == BoardStatus.Working || t.status == BoardStatus.Reviewing -> "Its agent"
                            else -> "Its agent's chat is closed"
                        },
                        style = MaterialTheme.typography.bodySmall, color = c.muted,
                    )
                }
            }
            if (last != null) {
                val passed = last.pass == true && last.error.isEmpty()
                val n = last.findings.size
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(Radius.m))
                        .clickable(onClickLabel = if (showReview) "Hide the review" else "Show the review") { showReview = !showReview },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (passed) HerdrIcons.CircleCheck else HerdrIcons.CircleAlert, null, tint = if (passed) c.done else c.red, modifier = Modifier.size(20.dp))
                    Text(
                        (if (passed) "Review passed" else if (last.error.isNotEmpty()) "Review failed to run" else "Review failed") +
                            (if (n > 0) " · $n finding${if (n == 1) "" else "s"}" else "") +
                            (last.by.takeIf { it.isNotEmpty() }?.let { " · by ${it.substringAfterLast('/')}" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium, color = c.text, modifier = Modifier.weight(1f).padding(start = Space.s),
                    )
                    Icon(if (showReview) HerdrIcons.ChevronUp else HerdrIcons.ChevronDown, null, tint = c.muted, modifier = Modifier.size(20.dp))
                }
                if (showReview) reviews.asReversed().forEachIndexed { i, r -> ReviewView(r, startOpen = i == 0) }
            }
            when {
                // Amber when nothing else on the card is: the bottom's primary action keeps it otherwise.
                agent.onOpen != null && BoardActions.primary(t) == null -> PrimaryButton(onClick = agent.onOpen, modifier = Modifier.fillMaxWidth()) {
                    Icon(HerdrIcons.MessageSquare, null, Modifier.size(18.dp))
                    Text("Open agent")
                }
                agent.onOpen != null -> SecondaryButton(onClick = agent.onOpen, modifier = Modifier.fillMaxWidth()) {
                    Icon(HerdrIcons.MessageSquare, null, Modifier.size(18.dp), tint = Herdr.colors.accent)
                    Text("Open agent")
                }
                agent.who.isNotEmpty() && onActivity != null && t.status != BoardStatus.Todo -> SecondaryButton(onClick = onActivity, modifier = Modifier.fillMaxWidth()) {
                    Icon(HerdrIcons.Activity, null, Modifier.size(18.dp))
                    Text("What its agents did")
                }
            }
        }
    }
}

/**
 * Everything else, folded away: the closing note and the description as short previews, the code and
 * quick commands, handovers, and the paths, branch, time and token counters.
 */
@Composable
private fun MorePart(
    t: BoardTask,
    d: TaskDetails,
    note: String,
    onCode: ((TaskDetails) -> Unit)?,
    onQuick: ((TaskDetails) -> Unit)?,
    onActivity: (() -> Unit)?,
) {
    val c = Herdr.colors
    val any = note.isNotEmpty() || d.description.isNotEmpty() || d.handovers.isNotEmpty() || d.worktree.isNotEmpty() || d.branch.isNotEmpty() ||
        d.time != null || d.tokens != null || d.queue.isNotEmpty() || onActivity != null
    if (!any) return
    Section("More", startOpen = false, key = "more-${t.id}") {
        Column(verticalArrangement = Arrangement.spacedBy(Space.l)) {
            if (note.isNotEmpty()) Labeled("Closing note") { Preview(note, "note-${t.id}", lines = 4) }
            if (d.description.isNotEmpty()) Labeled("Description") { Preview(d.description, "desc-${t.id}", lines = 4) }
            if (d.worktree.isNotEmpty() && onCode != null) Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                NavCard(HerdrIcons.GitCompare, "Changes and files", "Read the task's code and changes") { onCode(d) }
                if (onQuick != null) NavCard(HerdrIcons.Zap, "Quick commands", "Its project's scripts, run in its worktree") { onQuick(d) }
            }
            if (onActivity != null && t.status != BoardStatus.Todo) NavCard(HerdrIcons.Activity, "Activity timeline", "Each step its agents took") { onActivity() }
            if (d.handovers.isNotEmpty()) Labeled("Handovers") { d.handovers.forEach { MarkdownText("↪ $it", style = MaterialTheme.typography.bodySmall, color = c.muted) } }
            if (d.queue.isNotEmpty()) Labeled("Queue") { Text(d.queue, style = MaterialTheme.typography.bodySmall, color = c.muted) }
            if (d.branch.isNotEmpty() || d.worktree.isNotEmpty() || d.time != null || d.tokens != null) Labeled("Details") {
                if (d.branch.isNotEmpty()) InfoLine("Branch", d.branch, mono = true)
                if (d.worktree.isNotEmpty()) InfoLine("Worktree", d.worktree, mono = true)
                d.time?.let { UsageRow("Time", it.used.ifEmpty { "0s" }, it.limit.ifEmpty { "none" }) }
                d.tokens?.let { UsageRow("Tokens", it.used.toLongOrNull()?.let(ModelUse::tokens) ?: it.used.ifEmpty { "0" }, it.limit.ifEmpty { "none" }) }
            }
        }
    }
}

@Composable
private fun Labeled(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = Herdr.colors.muted, modifier = Modifier.semantics { heading() })
        content()
    }
}

@Composable
private fun NavCard(icon: ImageVector, title: String, detail: String, onClick: () -> Unit) {
    QuietCard(onClick = onClick, onClickLabel = title, padded = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Herdr.colors.muted, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f).padding(start = Space.m)) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = Herdr.colors.accent)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = Herdr.colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(HerdrIcons.ChevronRight, null, tint = Herdr.colors.faint, modifier = Modifier.size(20.dp))
        }
    }
}

/** The first lines of a long Markdown text, about [lines] of them, and "Show all" to read the rest. Short text shows whole. */
@Composable
internal fun Preview(source: String, key: String, lines: Int, color: Color = Color.Unspecified) {
    val short = remember(source, lines) { previewOf(source, lines) }
    var all by rememberSaveable(key) { mutableStateOf(false) }
    Column(Modifier.animateContentSize()) {
        MarkdownText(if (all || short == null) source else short, style = MaterialTheme.typography.bodyMedium, color = color)
        if (short != null) QuietButton(onClick = { all = !all }, color = Herdr.colors.accent, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 6.dp)) {
            Text(if (all) "Show less" else "Show all")
        }
    }
}

/** [source] cut to about [lines] lines (of some 60 characters) at a line's end, or null when it is that short already. */
internal fun previewOf(source: String, lines: Int): String? {
    val text = source.trim()
    val budget = lines * 60
    val rows = text.lines()
    if (text.length <= budget + 40 && rows.size <= lines + 1) return null
    val out = StringBuilder()
    for (row in rows) {
        if (out.isNotEmpty() && (out.length + row.length > budget || out.lines().size >= lines)) break
        if (out.isNotEmpty()) out.append('\n')
        out.append(if (row.length > budget) row.take(budget).substringBeforeLast(' ') + "…" else row)
    }
    return out.toString().trimEnd() + if (out.endsWith("…")) "" else " …"
}

@Composable
internal fun LinkRow(label: String, value: String, onClick: () -> Unit) {
    QuietCard(onClick = onClick, onClickLabel = "Open $label in the browser", padded = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = Herdr.colors.accent)
                Text(value, style = MaterialTheme.typography.bodySmall, color = Herdr.colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(HerdrIcons.ExternalLink, null, tint = Herdr.colors.faint, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun UsageRow(label: String, used: String, limit: String) = InfoLine(label, "$used used · limit $limit")

@Composable
private fun Card(title: String, color: Color, content: @Composable () -> Unit) {
    QuietCard(padded = true) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(HerdrIcons.CircleAlert, null, tint = color, modifier = Modifier.size(20.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = Space.s))
            }
            content()
        }
    }
}

/** A titled part of the task that folds away: a 48dp heading row with a chevron, its content under it. */
@Composable
internal fun Section(title: String, startOpen: Boolean = true, key: String = title, content: @Composable () -> Unit) {
    var open by rememberSaveable(key) { mutableStateOf(startOpen) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s), modifier = Modifier.animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(onClickLabel = if (open) "Collapse $title" else "Expand $title") { open = !open }
                .semantics { heading() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(if (open) HerdrIcons.ChevronUp else HerdrIcons.ChevronDown, if (open) "Collapse" else "Expand", tint = Herdr.colors.muted)
        }
        if (open) content()
    }
}

@Composable
internal fun severityColor(s: String) = when (s) {
    "critical", "blocker", "major" -> Herdr.colors.red
    "minor" -> Herdr.colors.notice
    else -> Herdr.colors.idle
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
                Icon(if (passed) HerdrIcons.CircleCheck else HerdrIcons.CircleAlert, null, tint = if (passed) c.done else c.red, modifier = Modifier.size(20.dp))
                Text(
                    "Round ${r.round} · $verdict" + (if (findings.isNotEmpty()) " · ${findings.size} finding${if (findings.size == 1) "" else "s"}" else "") +
                        if (r.by.isNotEmpty()) " · ${r.by.substringAfterLast('/')}" else "",
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).padding(start = Space.s),
                )
                Icon(if (open) HerdrIcons.ChevronUp else HerdrIcons.ChevronDown, null, tint = c.muted)
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
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.l)).background(c.card).border(1.dp, c.line, RoundedCornerShape(Radius.l)).padding(Space.m),
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
    onStart: (Pending.Start, String?) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    when (pending) {
        null -> {}
        is Pending.Start -> StartAgentPanel(pending.task, session, onDismiss) { agent -> onStart(pending, agent) }
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
                HerdrField(title, { title = it }, label = "Title", modifier = Modifier.fillMaxWidth())
                HerdrField(desc, { desc = it }, label = "Description (Markdown)", enabled = loaded, minLines = 4, modifier = Modifier.fillMaxWidth())
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
                HerdrField(title, { title = it }, label = "Title", modifier = Modifier.fillMaxWidth())
                HerdrField(repo, { repo = it }, label = "Repo (name or path)", singleLine = true, modifier = Modifier.fillMaxWidth())
                if (repos.isNotEmpty()) FlowRowChips(repos, repo) { repo = it }
                HerdrField(desc, { desc = it }, label = "Description (Markdown)", minLines = 3, modifier = Modifier.fillMaxWidth())
                SwitchRow("Research only", "A report instead of code changes", research) { research = it }
                SwitchRow("Start an agent now", null, start) { start = it }
                if (start) DefaultAgentLine(session)
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
