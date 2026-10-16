package com.makefunstuff.herdr.ui.manage

import androidx.compose.foundation.layout.heightIn
import com.makefunstuff.herdr.ui.design.HerdrDialog
import com.makefunstuff.herdr.ui.design.HerdrMenu
import com.makefunstuff.herdr.ui.design.HerdrMenuItem
import com.makefunstuff.herdr.ui.design.HerdrSheet
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.host.Notice
import com.makefunstuff.herdr.ui.common.StatusDot
import com.makefunstuff.herdr.ui.theme.color

/** One notice together with the machine it came from. */
data class NoticeRow(val machineId: String, val machineLabel: String, val notice: Notice)

/**
 * The session's notifications: what herdr popped up on the desktop when an agent finished or
 * started waiting. herdr's API has no list of them, so the app derives them from the agent-state
 * changes it follows (see Notices in core).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoticesSheet(
    rows: List<NoticeRow>,
    showMachine: Boolean,
    onOpenAgent: (machineId: String, paneId: String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    HerdrSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Notifications", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (rows.isEmpty()) "Nothing yet" else "${rows.size} recent",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (rows.isNotEmpty()) TextButton(onClick = onClear) { Text("Clear") }
            }
            if (rows.isEmpty()) {
                Text(
                    "When an agent finishes or asks for you, herdr's notification shows up here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
                return@Column
            }
            LazyColumn(Modifier.heightIn(max = 560.dp), contentPadding = PaddingValues(top = 4.dp)) {
                items(rows, key = { "${it.machineId}/${it.notice.id}" }) { row ->
                    NoticeItem(row, showMachine) { onOpenAgent(row.machineId, row.notice.paneId) }
                }
            }
        }
    }
}

@Composable
private fun NoticeItem(row: NoticeRow, showMachine: Boolean, onOpen: () -> Unit) {
    val notice = row.notice
    val color = when (notice.kind) {
        Notice.Kind.Done -> AgentStatus.Done.color()
        Notice.Kind.NeedsYou -> AgentStatus.Blocked.color()
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        StatusDot(color)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(notice.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(if (showMachine) row.machineLabel else null, notice.body.takeIf { it.isNotBlank() })
            if (meta.isNotEmpty()) {
                Text(
                    meta.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            ago(notice.at),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A short "how long ago" for a notice: 12s, 5m, 3h, 2d. */
fun ago(at: Long, now: Long = System.currentTimeMillis()): String {
    val seconds = ((now - at).coerceAtLeast(0)) / 1000
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m"
        seconds < 86_400 -> "${seconds / 3600}h"
        else -> "${seconds / 86_400}d"
    }
}
