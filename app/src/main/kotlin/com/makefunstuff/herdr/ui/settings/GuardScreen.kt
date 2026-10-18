package com.makefunstuff.herdr.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.makefunstuff.herdr.core.guard.Guarded
import com.makefunstuff.herdr.data.GuardState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.LocalGuard
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.Notice
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.design.RowDivider
import com.makefunstuff.herdr.ui.design.SecondaryButton
import com.makefunstuff.herdr.ui.design.SegmentedPills
import com.makefunstuff.herdr.ui.design.SwitchRow
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space

private val LOCK_AFTER = listOf(0 to "At once", 1 to "1 min", 5 to "5 min", 15 to "15 min")

/** Settings' way to the fingerprint and the app lock, with how they are set. */
@Composable
fun GuardRow(onOpen: () -> Unit) {
    val store = LocalContainer.current.guard
    val st by store.state.collectAsStateWithLifecycle()
    val c = Herdr.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onOpen).padding(vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(HerdrIcons.FingerprintPattern, null, tint = c.muted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Text("Fingerprint and app lock", style = MaterialTheme.typography.titleMedium, color = c.strong)
            Text(summary(st, store.unlock.canAsk()), style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
        Spacer(Modifier.width(Space.s))
        Icon(HerdrIcons.ChevronRight, null, tint = c.muted, modifier = Modifier.size(18.dp))
    }
}

private fun summary(st: GuardState, canAsk: Boolean): String {
    val n = Guarded.entries.count { st.asks(it) }
    val asks = when (n) {
        0 -> "Asks before nothing"
        Guarded.entries.size -> "Asks before merges, “always allow” and more"
        else -> "Asks before $n of ${Guarded.entries.size} actions"
    }
    return when {
        !canAsk -> "$asks · no screen lock on this phone"
        st.appLock -> "$asks · app lock on"
        else -> "$asks · app lock off"
    }
}

/**
 * The fingerprint (or the phone's PIN) before what can't be undone, one switch an action, and the
 * app lock. Turning a protection off asks for the fingerprint too, so the open app alone can't.
 */
@Composable
fun GuardScreen(nav: NavHostController) {
    val container = LocalContainer.current
    val guard = LocalGuard.current
    val store = container.guard
    val st by store.state.collectAsStateWithLifecycle()
    // Back from Android's settings: a screen lock may have been set up.
    var canAsk by remember { mutableStateOf(store.unlock.canAsk()) }
    LifecycleResumeEffect(Unit) {
        canAsk = store.unlock.canAsk()
        onPauseOrDispose {}
    }
    val c = Herdr.colors
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = c.ground,
        topBar = { HerdrTopBar("Fingerprint and app lock", subtitle = "Settings", onBack = { nav.popBackStack() }) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.screen).padding(top = Space.m, bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Text(
                "Ask for your fingerprint or the phone's PIN before what can't be undone, in the app and on a notification's buttons.",
                style = MaterialTheme.typography.bodyMedium,
                color = c.muted,
            )
            if (!canAsk) {
                Notice(
                    "This phone has no screen lock, so there is nothing to ask with: “always allow” and merges ask for a tap on Confirm instead, and the app lock is off.",
                    color = c.notice, icon = HerdrIcons.Lock,
                )
                SecondaryButton(onClick = { store.unlock.openSettings() }) {
                    Icon(HerdrIcons.FingerprintPattern, null, Modifier.size(18.dp)); Text("Set a screen lock")
                }
            }
            QuietCard(padded = true) {
                Column {
                    Text("Ask before", style = MaterialTheme.typography.titleSmall, color = c.muted, modifier = Modifier.padding(bottom = Space.xs))
                    Guarded.entries.forEachIndexed { i, g ->
                        if (i > 0) RowDivider(inset = 0.dp)
                        SwitchRow(g.title, detail = g.detail, checked = st.asks(g)) { on ->
                            fun set() = store.change { s -> s.copy(off = if (on) s.off - g.key else s.off + g.key) }
                            if (on) set() else guard.check("Stop asking before “${g.title}”?") { set() }
                        }
                    }
                }
            }
            QuietCard(padded = true) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                    SwitchRow(
                        "App lock",
                        detail = "Opening the app asks too, since it holds the SSH key to every machine. The recent apps show no picture of it.",
                        checked = st.appLock && canAsk,
                        enabled = canAsk,
                    ) { on -> guard.check(if (on) "Lock Herdr" else "Turn off the app lock?") { store.setAppLock(on) } }
                    if (st.appLock && canAsk) {
                        Text("Ask again after the app was away", style = MaterialTheme.typography.titleSmall)
                        SegmentedPills(
                            LOCK_AFTER.map { it.second },
                            LOCK_AFTER.indices.minBy { kotlin.math.abs(LOCK_AFTER[it].first - st.lockAfter) },
                            onSelect = { i -> store.change { it.copy(lockAfter = LOCK_AFTER[i].first) } },
                        )
                    }
                }
            }
        }
    }
}
