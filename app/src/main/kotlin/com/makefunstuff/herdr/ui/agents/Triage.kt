package com.makefunstuff.herdr.ui.agents

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.makefunstuff.herdr.core.home.AgentRow
import com.makefunstuff.herdr.core.home.HomeAgent
import com.makefunstuff.herdr.core.home.Snooze
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.toast
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.manage.ManageRow
import com.makefunstuff.herdr.ui.manage.ManageSheet
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Each action under a row swiped left. */
private val ACTION = 76.dp

/** How far right a row goes before letting go marks it seen. */
private val SEEN_AT = 96.dp

/**
 * What the Agents list does to one agent from its swipes, its long-press sheet and TalkBack's
 * actions: mark a finish seen, pin it on top, snooze it, mute its notifications. Kept on this phone
 * ([com.makefunstuff.herdr.data.TriageStore], [com.makefunstuff.herdr.data.SeenStore]).
 */
class TriageActions(
    private val seen: (HomeAgent) -> Unit,
    private val pin: (HomeAgent, Boolean) -> Unit,
    /** Opens the snooze choices for an agent; null takes its snooze back. */
    private val snooze: (HomeAgent, Boolean) -> Unit,
    private val mute: (HomeAgent, Boolean) -> Unit,
) {
    fun seen(row: AgentRow) = seen(row.agent)
    fun pin(row: AgentRow) = pin(row.agent, !row.pinned)
    fun snooze(row: AgentRow) = snooze(row.agent, row.snooze == null)
    fun mute(row: AgentRow) = mute(row.agent, !row.muted)

    /** The same, as TalkBack offers them on the row. */
    fun accessibility(row: AgentRow): List<CustomAccessibilityAction> = listOfNotNull(
        CustomAccessibilityAction("Mark seen") { seen(row); true }.takeIf { row.new },
        CustomAccessibilityAction(if (row.pinned) "Unpin" else "Pin to the top") { pin(row); true },
        CustomAccessibilityAction(if (row.snooze != null) "Stop snoozing" else "Snooze") { snooze(row); true },
        CustomAccessibilityAction(if (row.muted) "Unmute notifications" else "Mute notifications") { mute(row); true },
    )
}

/** The actions on the home list, kept in the phone's stores, with a toast for each. */
@Composable
fun rememberTriageActions(onSnoozeChoices: (HomeAgent) -> Unit): TriageActions {
    val container = LocalContainer.current
    val context = LocalContext.current
    return remember(container, context) {
        TriageActions(
            seen = { a ->
                container.seen.mark(a.machineId, a.agent.paneId, a.finish)
                container.triage.change { it.copy(used = true) }
            },
            pin = { a, on ->
                container.triage.change { (if (on) it.pin(a.key) else it.unpin(a.key)).copy(used = true) }
                context.toast(if (on) "Pinned to the top" else "Unpinned")
            },
            snooze = { a, on ->
                if (on) onSnoozeChoices(a) else {
                    container.triage.change { it.wake(a.key) }
                    context.toast("Not snoozed any more")
                }
            },
            mute = { a, on ->
                container.triage.change { (if (on) it.mute(a.key) else it.unmute(a.key)).copy(used = true) }
                context.toast(if (on) "Muted: no notifications from ${name(a)}" else "Notifications from ${name(a)} again")
            },
        )
    }
}

private fun name(a: HomeAgent) = a.agent.name?.takeIf { it.isNotBlank() } ?: a.agent.displayName

/**
 * A row that swipes: right marks a finish seen (when [AgentRow.new]), left shows Pin, Snooze and Mute
 * under it. [open]: whether it stays open on the left, so that only one row is open at a time.
 */
@Composable
fun SwipeRow(
    row: AgentRow,
    actions: TriageActions,
    open: Boolean,
    onOpen: (Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    val c = Herdr.colors
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val reveal = with(density) { (ACTION * 3).toPx() }
    val seenAt = with(density) { SEEN_AT.toPx() }
    var x by remember { mutableFloatStateOf(0f) }
    suspend fun settle(to: Float) = animate(x, to) { v, _ -> x = v }
    LaunchedEffect(open) { settle(if (open) -reveal else 0f) }
    val drag = rememberDraggableState { d -> x = (x + d).coerceIn(-reveal * 1.1f, if (row.new) seenAt * 1.5f else 0f) }
    Box(Modifier.fillMaxWidth()) {
        if (x > 0f) {
            val past = x >= seenAt
            Row(
                Modifier.matchParentSize().background(if (past) c.tint(c.done) else c.card).clearAndSetSemantics {}.padding(start = Space.screen + Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(HerdrIcons.CheckCheck, null, tint = if (past) c.done else c.muted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(Space.s))
                Text("Seen", style = MaterialTheme.typography.labelLarge, color = if (past) c.done else c.muted)
            }
        }
        if (x < 0f) {
            Row(Modifier.matchParentSize().background(c.card), horizontalArrangement = Arrangement.End) {
                fun done(f: () -> Unit) { onOpen(false); f() }
                SwipeAction(if (row.pinned) "Unpin" else "Pin", if (row.pinned) HerdrIcons.PinOff else HerdrIcons.Pin, c.text) { done { actions.pin(row) } }
                SwipeAction(if (row.snooze != null) "Wake" else "Snooze", HerdrIcons.Clock, c.accent) { done { actions.snooze(row) } }
                SwipeAction(if (row.muted) "Unmute" else "Mute", if (row.muted) HerdrIcons.Bell else HerdrIcons.BellOff, c.text) { done { actions.mute(row) } }
            }
        }
        Box(
            Modifier.offset { IntOffset(x.roundToInt(), 0) }.background(c.ground)
                .draggable(
                    drag, Orientation.Horizontal,
                    onDragStopped = { v ->
                        when {
                            row.new && x >= seenAt -> { actions.seen(row); settle(0f); onOpen(false) }
                            x < -reveal / 2 || v < -1500f -> { settle(-reveal); onOpen(true) }
                            else -> { settle(0f); onOpen(false) }
                        }
                    },
                ),
        ) {
            content()
            // Open: a tap on the row closes it rather than opening the chat.
            if (open || x < 0f) Box(Modifier.matchParentSize().clickable(onClickLabel = "Close") { onOpen(false); scope.launch { settle(0f) } })
        }
    }
}

@Composable
private fun SwipeAction(label: String, icon: ImageVector, tint: Color, onClick: () -> Unit) {
    Column(
        Modifier.width(ACTION).fillMaxHeight().clickable(onClick = onClick).padding(vertical = Space.s),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint, modifier = Modifier.padding(top = Space.xs))
    }
}

/** How long to snooze [agent]: until it changes, or until a time. */
@Composable
fun SnoozeSheet(agent: HomeAgent, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val now = container.triage.now()
    val zone = container.triage.zone()
    ManageSheet("Snooze ${name(agent)}", "Off the list until then. A question still shows in Needs you.", onDismiss) {
        Snooze.choices(agent, now, zone).forEachIndexed { i, (label, s) ->
            ManageRow(
                label,
                icon = if (i == 0) HerdrIcons.RefreshCw else HerdrIcons.Clock,
                detail = when {
                    i == 0 -> "Back when it works, finishes or asks again"
                    label.startsWith("For") -> s.label(now, zone).replaceFirstChar { it.uppercase() }
                    else -> null
                },
            ) {
                container.triage.change { it.snooze(agent.key, s).copy(used = true) }
                context.toast("Snoozed ${s.label(now, zone)}")
                onDismiss()
            }
        }
    }
}

/**
 * The triage rows of an agent's long-press sheet: Mark seen (a finish not looked at), Pin to the top,
 * Snooze, Mute notifications; each taken back from the same row.
 */
@Composable
fun TriageRows(agent: HomeAgent, onSnooze: () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val triage by container.triage.state.collectAsStateWithLifecycle()
    val seen by container.seen.marks.collectAsStateWithLifecycle()
    val key = agent.key
    val new = agent.agent.agentStatus == com.makefunstuff.herdr.core.api.AgentStatus.Done && seen[key] != agent.finish
    if (new) ManageRow("Mark seen", icon = HerdrIcons.CheckCheck, detail = "It leaves the Done list") {
        container.seen.mark(agent.machineId, agent.agent.paneId, agent.finish)
        context.toast("Marked seen")
    }
    val pinned = triage.pinned(key)
    ManageRow(if (pinned) "Unpin" else "Pin to the top", icon = if (pinned) HerdrIcons.PinOff else HerdrIcons.Pin, selected = pinned) {
        container.triage.change { (if (pinned) it.unpin(key) else it.pin(key)).copy(used = true) }
        context.toast(if (pinned) "Unpinned" else "Pinned to the top")
    }
    val snooze = triage.snoozeOf(agent, container.triage.now())
    ManageRow(
        if (snooze != null) "Stop snoozing" else "Snooze",
        icon = HerdrIcons.Clock,
        detail = snooze?.let { "Snoozed ${it.label(container.triage.now(), container.triage.zone())}" } ?: "Until it changes, or until a time",
        selected = snooze != null,
        chevron = snooze == null,
    ) {
        if (snooze != null) {
            container.triage.change { it.wake(key) }
            context.toast("Not snoozed any more")
        } else onSnooze()
    }
    val muted = key in triage.muted
    ManageRow(
        if (muted) "Unmute notifications" else "Mute notifications",
        icon = if (muted) HerdrIcons.Bell else HerdrIcons.BellOff,
        detail = if (muted) "Muted: nothing it says reaches the phone" else "No notifications from it; a watch you set still comes",
        selected = muted,
    ) {
        container.triage.change { (if (muted) it.unmute(key) else it.mute(key)).copy(used = true) }
        context.toast(if (muted) "Notifications from ${name(agent)} again" else "Muted: no notifications from ${name(agent)}")
    }
}
