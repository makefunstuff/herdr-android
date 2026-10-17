package demo.herdr.ui.machines

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import demo.herdr.core.host.HostState
import demo.herdr.core.host.LastAnswered

/**
 * A machine on the Machines screen: its name, where it is, and when it last
 * answered. A tap opens its workspaces.
 */
@Composable
fun MachineCard(state: HostState, onOpen: () -> Unit, modifier: Modifier = Modifier, now: Long = System.currentTimeMillis()) {
    val m = state.machine
    Card(onClick = onOpen, modifier = modifier) {
        Column {
            Row {
                Text(m.label, style = MaterialTheme.typography.titleMedium)
                StatusText(state, now)
            }
            Text(m.target, style = MaterialTheme.typography.bodySmall)
            if (m.via != null) Text("through ${m.via}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StatusText(state: HostState, now: Long) {
    val words = when {
        state.online && state.agents == 0 -> "Online"
        state.online -> "Online · ${state.agents} agents"
        // Offline: the last known state, and how old it is.
        else -> "Answered " + LastAnswered.words(state.answeredAt, now)
    }
    Text(words, style = MaterialTheme.typography.labelMedium)
}
