package com.makefunstuff.herdr.ui.board

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.IconTile
import com.makefunstuff.herdr.ui.design.ListRow
import com.makefunstuff.herdr.ui.design.LocalRowBackground
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.design.RowDivider
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space

/**
 * The ways into a machine's work board, where it has herdr-projects: the task Board and the
 * Coordinator chat, as one grouped card. [needsYou] counts the board's tasks waiting on you, when known.
 */
@Composable
fun WorkBoardCard(
    machineLabel: String,
    showMachine: Boolean,
    onBoard: () -> Unit,
    onCoordinator: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Herdr.colors
    QuietCard(modifier) {
        CompositionLocalProvider(LocalRowBackground provides c.card) {
            ListRow(
                title = if (showMachine) "Board · $machineLabel" else "Board",
                subtitle = "Tasks, reviews and approvals",
                leading = { IconTile(HerdrIcons.ViewKanban) },
                chevron = true,
                onClick = onBoard,
            )
            RowDivider(inset = Space.l + 52.dp)
            ListRow(
                title = if (showMachine) "Coordinator · $machineLabel" else "Coordinator",
                subtitle = "Chat with the board's Coordinator",
                leading = { IconTile(HerdrIcons.Forum) },
                chevron = true,
                onClick = onCoordinator,
            )
        }
    }
}
