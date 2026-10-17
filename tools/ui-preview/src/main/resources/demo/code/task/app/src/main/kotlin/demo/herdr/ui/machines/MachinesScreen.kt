package demo.herdr.ui.machines

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import demo.herdr.data.HostManager

/** Every machine, as a card. */
@Composable
fun MachinesScreen(hosts: HostManager, onOpen: (String) -> Unit) {
    LazyColumn {
        items(hosts.all.toList(), key = { it.state.value.machine.id }) { session ->
            MachineCard(session.state.value, onOpen = { onOpen(session.state.value.machine.id) })
        }
    }
}
