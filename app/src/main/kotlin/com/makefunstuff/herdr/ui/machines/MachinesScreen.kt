package com.makefunstuff.herdr.ui.machines

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.host.HostState
import com.makefunstuff.herdr.core.host.HostStatus
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.Pill
import com.makefunstuff.herdr.ui.common.StatusDot
import com.makefunstuff.herdr.ui.common.color
import com.makefunstuff.herdr.ui.common.copyToClipboard
import com.makefunstuff.herdr.ui.common.detail
import com.makefunstuff.herdr.ui.common.label
import com.makefunstuff.herdr.ui.theme.color

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MachinesScreen(nav: NavController) {
    val container = LocalContainer.current
    val hosts by container.hosts.hosts.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("Machines") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { nav.navigate(Routes.edit()) },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Add machine") },
            )
        },
    ) { padding ->
        if (hosts.isEmpty()) {
            EmptyState(
                "Connect your first machine",
                "Any Linux or macOS computer you can SSH into that runs herdr. You can import the rest of your herdr machines from it afterwards.",
            ) { Button(onClick = { nav.navigate(Routes.edit()) }) { Text("Add machine") } }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
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
    val m = host.machine
    val snap = host.snapshot
    val dim = !host.online
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(host.status.color())
                Spacer(Modifier.width(10.dp))
                Text(m.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Pill(host.status.label(), color = host.status.color())
            }
            Text(
                buildString {
                    append(m.target)
                    append(" · session ").append(m.session)
                    host.herdrVersion?.let { append(" · herdr ").append(it) }
                    via?.let { append(" · via ").append(it) }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (snap != null) {
                val counts = snap.agents.groupingBy { it.agentStatus }.eachCount()
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    counts[AgentStatus.Blocked]?.let { Pill("$it blocked", color = AgentStatus.Blocked.color()) }
                    counts[AgentStatus.Working]?.let { Pill("$it working", color = AgentStatus.Working.color()) }
                    counts[AgentStatus.Done]?.let { Pill("$it done", color = AgentStatus.Done.color()) }
                    Pill("${snap.workspaces.size} workspaces")
                }
                if (dim) {
                    Text("Showing the last known state", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            host.status.detail()?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val status = host.status
            if (status is HostStatus.Attention || status is HostStatus.Reconnecting) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (status is HostStatus.Attention && status.kind == HostStatus.Attention.Kind.Auth) {
                        OutlinedButton(onClick = { publicKey?.let { context.copyToClipboard("public key", it, "Public key copied") } }) {
                            Text("Copy public key")
                        }
                    }
                    if (status is HostStatus.Attention && status.kind == HostStatus.Attention.Kind.HostKey) {
                        OutlinedButton(onClick = onEdit) { Text("Review") }
                    }
                    OutlinedButton(onClick = onRetry) { Text("Retry") }
                }
            }
        }
    }
}
