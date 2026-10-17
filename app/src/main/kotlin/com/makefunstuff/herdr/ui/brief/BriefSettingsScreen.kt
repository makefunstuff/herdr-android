package com.makefunstuff.herdr.ui.brief

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.core.brief.BriefSchedule
import com.makefunstuff.herdr.core.push.QuietHours
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.Notice
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.design.RowDivider
import com.makefunstuff.herdr.ui.design.SecondaryButton
import com.makefunstuff.herdr.ui.design.SegmentedPills
import com.makefunstuff.herdr.ui.design.SwitchRow
import com.makefunstuff.herdr.ui.settings.Days
import com.makefunstuff.herdr.ui.settings.TimeBox
import com.makefunstuff.herdr.ui.settings.TimeSheet
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space

private const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

/**
 * When the brief comes (Settings, or the brief's own bar): on or off, at which times and on which
 * days, and whether it is read aloud as it comes (never, in the car, always).
 */
@Composable
fun BriefSettingsScreen(nav: NavHostController) {
    val container = LocalContainer.current
    val st by container.briefWork.store.state.collectAsStateWithLifecycle()
    val phone = container.phone
    var allowed by remember { mutableStateOf(phone.notificationsAllowed()) }
    LifecycleResumeEffect(Unit) {
        allowed = phone.notificationsAllowed()
        onPauseOrDispose {}
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = phone.notificationsAllowed() }
    fun allow() = if (Build.VERSION.SDK_INT >= 33) ask.launch(POST_NOTIFICATIONS) else phone.openNotificationSettings()
    val s = st.schedule
    fun change(f: (BriefSchedule) -> BriefSchedule) = container.briefWork.store.schedule(f)
    /** The time being changed (its minutes), or -1 for a new one; null when none is. */
    var picking by remember { mutableStateOf<Int?>(null) }

    val c = Herdr.colors
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = c.ground,
        topBar = { HerdrTopBar("When the brief comes", subtitle = "Brief", onBack = { nav.popBackStack() }) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.screen).padding(top = Space.m, bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Text(
                "What your agents and the board did since you last looked, on every machine: what merged, what went wrong, what waits for you. " +
                    "As a notification at the times you choose, and read aloud for the drive if you like.",
                style = MaterialTheme.typography.bodyMedium,
                color = c.muted,
            )
            if (s.on && !allowed) {
                Notice("Android blocks this app's notifications, so the brief can't show.", color = c.red, icon = HerdrIcons.BellOff)
                SecondaryButton("Allow notifications") { allow() }
            }

            QuietCard(padded = true) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                    SwitchRow(
                        "Send me the brief",
                        detail = if (s.on) s.label else "A notification at the times you choose, every day or on the days you pick.",
                        checked = s.on,
                    ) { on ->
                        change { it.copy(on = on) }
                        if (on && !allowed) allow()
                    }
                    if (s.on) {
                        RowDivider(inset = 0.dp)
                        Text("At", style = MaterialTheme.typography.titleSmall)
                        s.times.forEach { m ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TimeBox(if (m < 12 * 60) "Morning" else if (m < 17 * 60) "Afternoon" else "Evening", QuietHours.clock(m), Modifier.weight(1f)) { picking = m }
                                if (s.times.size > 1) BarIcon(HerdrIcons.X, "Remove ${QuietHours.clock(m)}", { change { it.copy(times = it.times - m) } })
                            }
                        }
                        if (s.times.size < BriefSchedule.MAX_TIMES) {
                            Row {
                                QuietButton(onClick = { picking = -1 }) {
                                    Icon(HerdrIcons.Plus, null, Modifier.size(18.dp)); Text("Add a time, such as for the drive home")
                                }
                            }
                        }
                        Text("On", style = MaterialTheme.typography.titleSmall)
                        Days(s.days) { days -> change { it.copy(days = days) } }
                        if (s.days.isEmpty()) Text("No day is chosen, so the brief never comes.", style = MaterialTheme.typography.bodySmall, color = c.dangerText)
                    }
                }
            }

            QuietCard(padded = true) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(HerdrIcons.Car, null, tint = c.muted, modifier = Modifier.size(20.dp))
                        Text(
                            "Read it aloud as it comes",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = Space.m).semantics { heading() },
                        )
                    }
                    SegmentedPills(
                        BriefSchedule.ReadAloud.entries.map { it.label },
                        s.readAloud.ordinal,
                        onSelect = { i -> change { it.copy(readAloud = BriefSchedule.ReadAloud.entries[i]) } },
                    )
                    Text(
                        when (s.readAloud) {
                            BriefSchedule.ReadAloud.Never -> "It comes as a notification only. Its Read aloud button, and the one on the brief, read it whenever you like."
                            BriefSchedule.ReadAloud.InCar -> "While the phone is in car mode (Android Auto, a car dock), the phone reads it as it comes, over the music. Otherwise it is a notification."
                            BriefSchedule.ReadAloud.Always -> "The phone reads it as it comes, wherever it is, over the music."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = c.muted,
                    )
                }
            }

            Text(
                "Since you last looked means since the app was last on screen, or a brief was read aloud to its end. " +
                    "The app reaches every machine to put it together, over the same SSH connection. Where herdr-push runs " +
                    "(Settings → Notifications), its journal tells everything that happened; elsewhere the brief compares with what the app showed you.",
                style = MaterialTheme.typography.bodySmall,
                color = c.muted,
            )
            Row { SecondaryButton(onClick = { nav.navigate(Routes.BRIEF) }) { Icon(HerdrIcons.Newspaper, null, Modifier.size(18.dp)); Text("Open the brief now") } }
        }
    }

    picking?.let { editing ->
        TimeSheet(
            title = if (editing < 0) "Add a time" else "Brief at",
            // A new one starts at a time not set yet: the drive home, noon, the morning, the evening.
            minutes = if (editing < 0) listOf(17 * 60 + 30, 12 * 60, 7 * 60 + 30, 21 * 60).firstOrNull { it !in s.times } ?: (12 * 60) else editing,
            onPick = { m ->
                change { if (editing < 0 || editing !in it.times) it.withTime(m) else it.copy(times = it.times - editing).withTime(m) }
                picking = m
            },
            onDismiss = { picking = null },
        )
    }
}
