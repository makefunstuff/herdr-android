package com.makefunstuff.herdr.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.makefunstuff.herdr.core.push.QuietHours
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.design.HerdrChip
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrMenu
import com.makefunstuff.herdr.ui.design.HerdrShapes
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.Notice
import com.makefunstuff.herdr.ui.design.PrimaryButton
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.design.RowDivider
import com.makefunstuff.herdr.ui.design.SegmentedPills
import com.makefunstuff.herdr.ui.design.SwitchRow
import com.makefunstuff.herdr.ui.design.hairline
import com.makefunstuff.herdr.ui.theme.CodeStyle
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space

private val MINUTES = listOf(0, 15, 30, 45)

/**
 * Quiet hours (Settings → Notifications): during Bedtime, Driving or Do Not Disturb, or on a
 * schedule, notifications that can wait are held and come as one when quiet hours end; what needs
 * you still comes through.
 */
@Composable
fun QuietHoursScreen(nav: NavHostController) {
    val container = LocalContainer.current
    val st by container.push.state.collectAsStateWithLifecycle()
    val reason by container.quietWork.reason.collectAsStateWithLifecycle()
    val held by container.inbox.heldCount.collectAsStateWithLifecycle()
    // Back from Android's settings, or a while later: the phone's mode or the hour may have changed.
    LifecycleResumeEffect(Unit) {
        container.quietWork.check()
        onPauseOrDispose {}
    }
    val q = st.quiet
    fun change(f: (QuietHours) -> QuietHours) = container.push.change { it.copy(quiet = f(it.quiet)) }
    /** The time being picked: true the start, false the end. */
    var picking by remember { mutableStateOf<Boolean?>(null) }

    val c = Herdr.colors
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = c.ground,
        topBar = { HerdrTopBar("Quiet hours", subtitle = "Notifications", onBack = { nav.popBackStack() }) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.screen).padding(top = Space.m, bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Text(
                "Notifications that can wait are held during quiet hours and come as one when they end. What needs you still comes through.",
                style = MaterialTheme.typography.bodyMedium,
                color = c.muted,
            )
            if (!st.on) {
                Notice("Notifications while the app is closed are off, so there is nothing to hold. Turn them on in Settings.", icon = HerdrIcons.BellOff)
            } else if (q.on) {
                QuietNow(reason, held, onShow = { container.inbox.release(quiet = false) })
            }

            QuietCard(padded = true) {
                Column {
                    SwitchRow(
                        "During Bedtime, Driving and Do Not Disturb",
                        detail = "While the phone is in Do Not Disturb or a mode that turns it on, such as Bedtime or Driving, or in car mode with Android Auto.",
                        checked = q.followPhone,
                    ) { on -> change { it.copy(followPhone = on) } }
                    RowDivider(inset = 0.dp)
                    SwitchRow(
                        "On a schedule",
                        detail = if (q.schedule) q.scheduleLabel else "Every night, or on the days you choose.",
                        checked = q.schedule,
                    ) { on -> change { it.copy(schedule = on) } }
                    if (q.schedule) Schedule(q, onPick = { picking = it }, onDays = { days -> change { it.copy(days = days) } })
                }
            }

            QuietCard(padded = true) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                    Text("During quiet hours", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    Rule(HerdrIcons.BellRing, "Comes through", "An agent asks you something, a board task waits for you (ready to approve, plan ready, asks you, stopped), or something went wrong (a review or a check failed).")
                    Rule(HerdrIcons.Moon, "Waits", "An agent finished, a watch you set fired, and the Live Updates of running tasks and agents. They come as one notification when quiet hours end, and the Live Updates come back.")
                    Text(
                        "Android still decides whether what comes through makes a sound: to hear it during Bedtime or Driving, allow Herdr in that mode's settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.muted,
                    )
                }
            }
        }
    }

    picking?.let { start ->
        TimeSheet(
            title = if (start) "Quiet from" else "Quiet until",
            minutes = if (start) q.start else q.end,
            onPick = { m -> change { if (start) it.copy(start = m) else it.copy(end = m) } },
            onDismiss = { picking = null },
        )
    }
}

/** Whether it is quiet now, and what waits. */
@Composable
private fun QuietNow(reason: String?, held: Int, onShow: () -> Unit) {
    val c = Herdr.colors
    val waiting = when (held) {
        0 -> "Nothing waits yet."
        1 -> "1 notification waits."
        else -> "$held notifications wait."
    }
    if (reason == null) {
        Notice("Not quiet now: every notification shows.", icon = HerdrIcons.Bell)
        return
    }
    Column {
        Notice("Quiet now: $reason. $waiting", color = c.accent, icon = HerdrIcons.Moon)
        if (held > 0) Row { QuietButton(onClick = onShow) { Text("Show them now") } }
    }
}

/** From and to, and the days. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Schedule(q: QuietHours, onPick: (Boolean) -> Unit, onDays: (Set<Int>) -> Unit) {
    val c = Herdr.colors
    Column(Modifier.padding(top = Space.s, bottom = Space.xs), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            TimeBox("From", QuietHours.clock(q.start), Modifier.weight(1f)) { onPick(true) }
            TimeBox("To", QuietHours.clock(q.end), Modifier.weight(1f)) { onPick(false) }
        }
        if (q.start == q.end) {
            Text("From and to are the same, so the chosen days are quiet all day.", style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
        Text("Starts on", style = MaterialTheme.typography.titleSmall)
        Days(q.days, onDays)
        if (q.days.isEmpty()) Text("No day is chosen, so the schedule never turns quiet.", style = MaterialTheme.typography.bodySmall, color = c.dangerText)
    }
}

/**
 * The seven days in one track, as [com.makefunstuff.herdr.ui.design.SegmentedPills] draws it, but
 * each one on or off by itself: chosen on `--bg-hover` in strong text.
 */
@Composable
internal fun Days(days: Set<Int>, onDays: (Set<Int>) -> Unit) {
    val c = Herdr.colors
    Row(
        Modifier.fillMaxWidth().clip(HerdrShapes.Field).background(c.card).hairline(HerdrShapes.Field).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        QuietHours.DAY_NAMES.forEachIndexed { i, name ->
            val day = i + 1
            val on = day in days
            Box(
                Modifier.weight(1f).heightIn(min = 44.dp).clip(HerdrShapes.Badge)
                    .background(if (on) c.hover else Color.Transparent)
                    .toggleable(value = on, role = Role.Checkbox, onValueChange = { onDays(if (it) days + day else days - day) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1, color = if (on) c.strong else c.muted)
            }
        }
    }
}

/** A time to tap: its label, and the time in the code face. */
@Composable
internal fun TimeBox(label: String, time: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Herdr.colors
    Column(
        modifier.heightIn(min = 64.dp).clip(HerdrShapes.Field).background(c.card).hairline(HerdrShapes.Field)
            .clickable(role = Role.Button, onClickLabel = "Change", onClick = onClick)
            .padding(horizontal = Space.m, vertical = Space.s),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = c.muted)
        Text(time, style = CodeStyle.copy(fontSize = MaterialTheme.typography.titleLarge.fontSize), color = c.strong)
    }
}

/** What happens to one kind of notification. */
@Composable
private fun Rule(icon: ImageVector, title: String, detail: String) {
    val c = Herdr.colors
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = c.muted, modifier = Modifier.padding(top = 2.dp).size(20.dp))
        Column(Modifier.padding(start = Space.m)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.strong)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
    }
}

/** A time to the quarter of an hour: the hour, then the minutes. Each tap counts at once. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TimeSheet(title: String, minutes: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    var m by remember { mutableStateOf(minutes) }
    fun pick(next: Int) {
        m = next
        onPick(next)
    }
    HerdrMenu(expanded = true, onDismissRequest = onDismiss, title = title, subtitle = QuietHours.clock(m)) {
        Column(Modifier.padding(horizontal = Space.m), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                for (h in 0 until 24) {
                    HerdrChip("%02d".format(h), selected = m / 60 == h, onClick = { pick(h * 60 + m % 60) }, modifier = Modifier.width(56.dp))
                }
            }
            SegmentedPills(
                MINUTES.map { ":%02d".format(it) },
                MINUTES.indexOf(m % 60).coerceAtLeast(0),
                onSelect = { pick(m / 60 * 60 + MINUTES[it]) },
            )
            PrimaryButton("Done", Modifier.fillMaxWidth().padding(bottom = Space.s)) { onDismiss() }
        }
    }
}
