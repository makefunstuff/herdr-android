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
import androidx.compose.runtime.CompositionLocalProvider
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
import com.makefunstuff.herdr.ui.design.BarAdd
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.IconTile
import com.makefunstuff.herdr.ui.design.ListRow
import com.makefunstuff.herdr.ui.design.LocalRowBackground
import com.makefunstuff.herdr.ui.design.PrimaryButton
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.design.SecondaryButton
import com.makefunstuff.herdr.ui.design.StatusLabel
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
                if (hosts.isNotEmpty()) BarAdd("Add machine") { nav.navigate(Routes.edit()) }
            }
        },
    ) { padding ->
        if (hosts.isEmpty()) {
            EmptyState(
                "Connect your first machine",
                "Any Linux or macOS computer you can SSH into that runs herdr. You can import the rest of your herdr machines from it afterwards.",
                icon = HerdrIcons.Dns,
            ) { PrimaryButton("Add machine") { nav.navigate(Routes.edit()) } }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(start = Space.screen, end = Space.screen, top = Space.xs, bottom = Space.xl),
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
        onClick = onOpen, onClickLabel = "Open ${m.label}",
    ) {
        CompositionLocalProvider(LocalRowBackground provides c.card) {
            ListRow(
                title = m.label,
                subtitle = buildString {
                    append(m.target)
                    via?.let { append(" · via ").append(it) }
                },
                leading = { IconTile(HerdrIcons.Dns, dot = status.color()) },
                trailing = {
                    Text(status.label(), style = MaterialTheme.typography.labelMedium, color = if (host.online) c.faint else status.color(), maxLines = 1)
                },
                modifier = Modifier.padding(top = Space.xs),
            )
        }
        val details = buildList {
            if (snap != null) {
                val counts = snap.agents.groupingBy { it.agentStatus }.eachCount()
                counts[AgentStatus.Blocked]?.let { add("$it need${if (it == 1) "s" else ""} you" to AgentStatus.Blocked.color()) }
                counts[AgentStatus.Working]?.let { add("$it working" to AgentStatus.Working.color()) }
                counts[AgentStatus.Done]?.let { add("$it done" to AgentStatus.Done.color()) }
                add("${snap.workspaces.size} workspace${if (snap.workspaces.size == 1) "" else "s"}" to null)
            }
            host.herdrVersion?.let { add("herdr $it" to null) }
        }
        if (details.isNotEmpty()) {
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                Modifier.padding(start = 68.dp, end = Space.l, bottom = Space.m),
                horizontalArrangement = Arrangement.spacedBy(Space.m),
                verticalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                details.forEach { (text, color) ->
                    if (color != null) StatusLabel(text, color) else Text(text, style = MaterialTheme.typography.labelMedium, color = c.faint)
                }
            }
        }
        val note = listOfNotNull(
            status.detail(),
            if (snap != null && !host.online) "Showing the last known state" else null,
        )
        if (note.isNotEmpty()) Text(
            note.joinToString("\n"),
            style = MaterialTheme.typography.bodySmall,
            color = c.muted,
            modifier = Modifier.padding(start = 68.dp, end = Space.l, bottom = Space.m),
        )
        if (status is HostStatus.Attention || status is HostStatus.Reconnecting) {
            Row(
                Modifier.padding(start = 68.dp - Space.xs, end = Space.l, bottom = Space.m),
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
