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
import com.makefunstuff.herdr.core.push.HealthWords
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.Pill
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.delay

/**
 * The way into a machine's health from its screen: RAM, CPU and the fullest disk at a glance, in red
 * when the machine runs low. It takes a quick look (no processes) every half minute while shown.
 */
@Composable
fun HealthCard(session: HostSession, onOpen: () -> Unit) {
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
    QuietCard(onClick = onOpen, onClickLabel = "Open health and cleanup", padded = true, edge = if (report != null) c.red else c.line) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (report != null) HerdrIcons.TriangleAlert else HerdrIcons.Activity, null, tint = if (report != null) c.red else c.text, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                Text(
                    if (report != null) HealthWords.words(state.machine.label, report).first else "Health",
                    style = MaterialTheme.typography.titleMedium, color = if (report != null) c.red else c.strong,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(summary(s), style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(Space.s))
            Pill("cleanup")
        }
    }
}

/** "RAM 67% · CPU 12% · / 4.7 GB free": the fullest disk. */
private fun summary(s: HealthSample?): String {
    if (s == null) return "RAM, CPU, disks and leftovers"
    val disk = s.disks.minByOrNull { it.availPercent }
    return listOfNotNull(
        s.memory?.let { "RAM ${Sizes.percent(it.usedPercent)}" },
        (s.cpuBusy ?: s.loadPercent).let { "CPU ${Sizes.percent(it)}" },
        disk?.let { "${it.mount} ${Sizes.kb(it.availKb)} free" },
    ).joinToString(" · ")
}
