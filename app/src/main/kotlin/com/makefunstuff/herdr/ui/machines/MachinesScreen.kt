package com.makefunstuff.herdr.ui.machines

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.host.HostState
import com.makefunstuff.herdr.core.host.HostStatus
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.color
import com.makefunstuff.herdr.ui.common.copyToClipboard
import com.makefunstuff.herdr.ui.common.detail
import com.makefunstuff.herdr.ui.common.label
import com.makefunstuff.herdr.ui.design.HerdrFab
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.StatusBadge
import com.makefunstuff.herdr.ui.common.Pill
import com.makefunstuff.herdr.ui.common.StatusDot
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.PrimaryButton
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.design.SecondaryButton
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import com.makefunstuff.herdr.ui.theme.color

@Composable
fun MachinesScreen(nav: NavController) {
    val container = LocalContainer.current
    val hosts by container.hosts.hosts.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Herdr.colors.ground,
        topBar = {
            HerdrTopBar("Machines") {
                if (hosts.isNotEmpty()) BarIcon(HerdrIcons.Code, "Code on the machines", { nav.navigate(Routes.CODE) })
                // Settings, used now and then, sit here rather than in the bottom bar.
                BarIcon(HerdrIcons.Settings, "Settings", { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } })
            }
        },
        floatingActionButton = {
            if (hosts.isNotEmpty()) HerdrFab("Add machine") { nav.navigate(Routes.edit()) }
        },
    ) { padding ->
        if (hosts.isEmpty()) {
            EmptyState(
                "Connect your first machine",
                "Any Linux or macOS computer you can SSH into that runs herdr. You can import the rest of your herdr machines from it afterwards.",
                icon = HerdrIcons.Monitor,
            ) { PrimaryButton("Add machine") { nav.navigate(Routes.edit()) } }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(start = Space.screen, end = Space.screen, top = Space.xs, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            items(hosts, key = { it.machine.id }) { host ->
                MachineCard(
                    host,
                    via = host.machine.viaMachineId?.let { id -> hosts.firstOrNull { it.machine.id == id }?.machine?.label },
                    onOpen = { nav.navigate(Routes.machine(host.machine.id)) },
                    onRetry = { container.hosts.session(host.machine.id)?.retry() },
                    onEdit = { nav.navigate(Routes.edit(host.machine.id)) },
                )
            }
        }
    }
}

@Composable
private fun MachineCard(host: HostState, via: String?, onOpen: () -> Unit, onRetry: () -> Unit, onEdit: () -> Unit) {
    val context = LocalContext.current
    val publicKey by LocalContainer.current.identity.publicKey.collectAsStateWithLifecycle()
    val c = Herdr.colors
    val m = host.machine
    val snap = host.snapshot
    val status = host.status
    QuietCard(
        Modifier.semantics(mergeDescendants = true) { stateDescription = status.label() },
        padded = true,
        onClick = onOpen, onClickLabel = "Open ${m.label}",
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(status.color())
            Spacer(Modifier.width(Space.m))
            Text(m.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            StatusBadge(status.label(), status.color())
        }
        Text(
            buildString {
                append(m.target)
                append(" · session ").append(m.session)
                host.herdrVersion?.let { append(" · herdr ").append(it) }
                via?.let { append(" · via ").append(it) }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = c.muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 20.dp, top = Space.xxs),
        )
        if (snap != null) {
            val counts = snap.agents.groupingBy { it.agentStatus }.eachCount()
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                Modifier.padding(start = 20.dp, top = Space.s),
                horizontalArrangement = Arrangement.spacedBy(Space.xs),
                verticalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                counts[AgentStatus.Blocked]?.let { Pill("$it blocked", color = AgentStatus.Blocked.color()) }
                counts[AgentStatus.Working]?.let { Pill("$it working", color = AgentStatus.Working.color()) }
                counts[AgentStatus.Done]?.let { Pill("$it done", color = AgentStatus.Done.color()) }
                Pill("${snap.workspaces.size} workspace${if (snap.workspaces.size == 1) "" else "s"}")
            }
        }
        val note = listOfNotNull(
            if (snap != null && !host.online) "Showing the last known state" else null,
            status.detail(),
        )
        if (note.isNotEmpty()) Text(
            note.joinToString("\n"),
            style = MaterialTheme.typography.bodySmall,
            color = c.muted,
            modifier = Modifier.padding(start = 20.dp, top = Space.s),
        )
        if (status is HostStatus.Attention || status is HostStatus.Reconnecting) {
            Row(
                Modifier.padding(start = 20.dp, top = Space.s),
                horizontalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                if (status is HostStatus.Attention && status.kind == HostStatus.Attention.Kind.Auth) {
                    SecondaryButton("Copy public key") { publicKey?.let { context.copyToClipboard("public key", it, "Public key copied") } }
                }
                if (status is HostStatus.Attention && status.kind == HostStatus.Attention.Kind.HostKey) {
                    SecondaryButton("Review", onClick = onEdit)
                }
                SecondaryButton("Retry", onClick = onRetry)
            }
        }
    }
}
