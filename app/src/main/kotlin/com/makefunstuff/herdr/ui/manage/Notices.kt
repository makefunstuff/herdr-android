package com.makefunstuff.herdr.ui.manage

import com.makefunstuff.herdr.ui.design.SheetTitle
import com.makefunstuff.herdr.ui.design.StateBlock
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.QuietButton
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import com.makefunstuff.herdr.ui.design.ActionRow
import com.makefunstuff.herdr.ui.design.HerdrSheet
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(end = Space.s),
        ) {
            SheetTitle("Notifications", if (rows.isEmpty()) "Nothing new" else "${rows.size} recent", Modifier.weight(1f))
            if (rows.isNotEmpty()) QuietButton(onClick = onClear) { Text("Clear") }
        }
        if (rows.isEmpty()) {
            StateBlock(
                HerdrIcons.Notifications, "All quiet",
                "When an agent finishes or asks for you, herdr's notification shows up here.",
                Modifier.padding(bottom = Space.xl),
            )
            return@HerdrSheet
        }
        LazyColumn(Modifier.heightIn(max = 560.dp), contentPadding = PaddingValues(start = Space.s, end = Space.s, bottom = Space.l)) {
            items(rows, key = { "${it.machineId}/${it.notice.id}" }) { row ->
                NoticeItem(row, showMachine) { onOpenAgent(row.machineId, row.notice.paneId) }
            }
        }
    }
}

@Composable
private fun NoticeItem(row: NoticeRow, showMachine: Boolean, onOpen: () -> Unit) {
    val notice = row.notice
    val status = when (notice.kind) {
        Notice.Kind.Done -> AgentStatus.Done
        Notice.Kind.NeedsYou -> AgentStatus.Blocked
    }
    val meta = listOfNotNull(if (showMachine) row.machineLabel else null, notice.body.takeIf { it.isNotBlank() })
    ActionRow(
        title = notice.title,
        detail = meta.joinToString(" · ").ifBlank { null },
        icon = when (notice.kind) {
            Notice.Kind.Done -> HerdrIcons.CheckCircle
            Notice.Kind.NeedsYou -> HerdrIcons.Warning
        },
        iconTint = status.color(),
        onClick = onOpen,
        trailing = {
            Text(ago(notice.at), style = MaterialTheme.typography.labelMedium, color = Herdr.colors.faint)
        },
    )
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
