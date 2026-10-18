package com.makefunstuff.herdr.ui.health

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.makefunstuff.herdr.core.health.HealthClient
import com.makefunstuff.herdr.core.health.HealthReport
import com.makefunstuff.herdr.core.health.HealthSample
import com.makefunstuff.herdr.core.health.Sizes
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.machines.PlaceTile
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.delay

/**
 * The way into a machine's health from its screen, as a tile: the busiest of RAM, CPU and the fullest
 * disk, or what runs low in red. It takes a quick look (no processes) every half minute while shown.
 */
@Composable
fun HealthTile(session: HostSession, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val store = LocalContainer.current.health
    val latest by store.latest.collectAsStateWithLifecycle()
    val state by session.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val id = session.machine.id
    LaunchedEffect(id, state.online) {
        if (!state.online) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                runCatching {
                    val conn = session.awaitConnection()
                    store.add(id, HealthClient { cmd, t -> conn.run(cmd, t) }.quick())
                }
                delay(30_000)
            }
        }
    }
    val s = latest[id]
    val report = s?.let { HealthReport.of(it, emptyList()) }?.takeIf { it.low.isNotEmpty() }
    val c = Herdr.colors
    PlaceTile(
        if (report != null) HerdrIcons.TriangleAlert else HerdrIcons.Activity,
        "Health",
        when {
            report == null -> busiest(s)
            report.ram && report.low.size > 1 -> "Low on RAM and disk"
            report.ram -> "Low on RAM"
            else -> "Low on disk"
        },
        if (report != null) c.red else c.muted,
        modifier, onOpen, "Open health and cleanup",
        edge = if (report != null) c.red else c.line,
        iconTint = if (report != null) c.red else c.text,
    )
}

/** "RAM 67%", "CPU 91%" or "Disk 88%" (the fullest): whichever is highest. */
private fun busiest(s: HealthSample?): String {
    if (s == null) return "RAM, CPU, disks"
    val disk = s.disks.minByOrNull { it.availPercent }
    return listOfNotNull(
        s.memory?.let { it.usedPercent to "RAM ${Sizes.percent(it.usedPercent)}" },
        (s.cpuBusy ?: s.loadPercent).let { it to "CPU ${Sizes.percent(it)}" },
        disk?.let { it.usedPercent to "Disk ${Sizes.percent(it.usedPercent)}" },
    ).maxByOrNull { it.first }?.second.orEmpty()
}
