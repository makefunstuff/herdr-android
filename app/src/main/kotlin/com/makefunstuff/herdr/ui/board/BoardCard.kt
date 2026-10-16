package com.makefunstuff.herdr.ui.board

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.makefunstuff.herdr.ui.common.Pill
import com.makefunstuff.herdr.ui.common.StatusDot

/** The way into a machine's task board, shown where the machine has herdr-projects. */
@Composable
fun BoardCard(machineLabel: String, showMachine: Boolean, onOpen: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.width(10.dp))
                Text(if (showMachine) "Board · $machineLabel" else "Board", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Pill("tasks")
            }
            Text("Tasks, reviews and approvals", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
