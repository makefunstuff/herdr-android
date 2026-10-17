package com.makefunstuff.herdr.ui.board

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.makefunstuff.herdr.ui.common.Pill
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space

/** The way into a machine's task board, shown where the machine has herdr-projects. */
@Composable
fun BoardCard(machineLabel: String, showMachine: Boolean, onOpen: () -> Unit) =
    EntryCard(HerdrIcons.SquareKanban, if (showMachine) "Board · $machineLabel" else "Board", "Tasks, reviews and approvals", "tasks", onOpen)

/** The way into a machine's Coordinator chat, shown where the machine has the work board. */
@Composable
fun CoordinatorCard(machineLabel: String, showMachine: Boolean, onOpen: () -> Unit) =
    EntryCard(HerdrIcons.MessagesSquare, if (showMachine) "Coordinator · $machineLabel" else "Coordinator", "Chat with the board's Coordinator", "work board", onOpen)

/** A card that opens a part of the work board: its mark, its name and what it is for, a small tag. */
@Composable
private fun EntryCard(icon: ImageVector, title: String, body: String, tag: String, onOpen: () -> Unit) {
    val c = Herdr.colors
    QuietCard(onClick = onOpen, padded = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = c.text, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = c.strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(body, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(Space.s))
            Pill(tag)
        }
    }
}
