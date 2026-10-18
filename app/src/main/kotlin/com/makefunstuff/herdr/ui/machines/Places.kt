package com.makefunstuff.herdr.ui.machines

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.makefunstuff.herdr.core.board.BoardGlance
import com.makefunstuff.herdr.core.board.CoordinatorView
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.health.HealthTile
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import com.makefunstuff.herdr.ui.usage.UsageRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * A machine's places, over its workspaces: Board, Coordinator and Health as tiles in one row, each
 * with its live line. Up to three fit a phone's row; Usage takes the row's place when there is room
 * (no board), else a one-line row under the tiles.
 */
@Composable
internal fun MachinePlaces(
    session: HostSession,
    onBoard: () -> Unit,
    onCoordinator: () -> Unit,
    onHealth: () -> Unit,
    onUsage: () -> Unit,
) {
    val state by session.state.collectAsStateWithLifecycle()
    val board = state.hasBoard
    val tiles = listOfNotNull(
        "board".takeIf { board }, "coordinator".takeIf { board }, "health".takeIf { state.online },
        "usage".takeIf { state.online && !board },
    )
    if (tiles.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            val tile = Modifier.weight(1f).fillMaxHeight()
            tiles.forEach { t ->
                when (t) {
                    "board" -> BoardTile(session, tile, onBoard)
                    "coordinator" -> CoordinatorTile(session, tile, onCoordinator)
                    "health" -> HealthTile(session, tile, onHealth)
                    "usage" -> UsageRow(session, onUsage, tile = true, modifier = tile)
                }
            }
        }
    }
    if (state.online && board) UsageRow(session, onUsage, modifier = Modifier.padding(top = Space.s))
}

/** The work board's tile: what needs you on it, else what is at work. It reads the board every half minute while shown. */
@Composable
private fun BoardTile(session: HostSession, modifier: Modifier, onOpen: () -> Unit) {
    val container = LocalContainer.current
    val owner = LocalLifecycleOwner.current
    val state by session.state.collectAsStateWithLifecycle()
    val lists by container.boards.lists.collectAsStateWithLifecycle()
    val id = session.machine.id
    LaunchedEffect(id, state.online, state.boardPath) {
        if (!state.online || state.boardPath == null) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                container.boards.readOne(id, freshMs = 10_000)
                delay(30_000)
            }
        }
    }
    val c = Herdr.colors
    val read = lists[id]
    val glance = read?.tasks?.let { BoardGlance.of(it) }
    val (line, color) = when {
        glance == null -> (if (read?.problem != null) "Didn't answer" else "Tasks") to c.muted
        glance.needYou > 0 -> "${glance.needYou} need you" to c.red
        glance.working > 0 -> "${glance.working} working" to c.working
        glance.queued > 0 -> "${glance.queued} queued" to c.muted
        else -> "Nothing waits" to c.muted
    }
    PlaceTile(HerdrIcons.SquareKanban, "Board", line, color, modifier, onOpen, "Open the board")
}

/** The Coordinator's tile: whether it is idle, at work or waiting for you, read every half minute while shown. */
@Composable
private fun CoordinatorTile(session: HostSession, modifier: Modifier, onOpen: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val state by session.state.collectAsStateWithLifecycle()
    var view by remember { mutableStateOf<CoordinatorView?>(null) }
    var read by remember { mutableStateOf(false) }
    LaunchedEffect(session.machine.id, state.online, state.boardPath) {
        if (!state.online || state.boardPath == null) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    view = session.awaitBoard().list().coordinator
                    read = true
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                }
                delay(30_000)
            }
        }
    }
    val c = Herdr.colors
    val v = view
    val (line, color) = when {
        !read -> "Chat" to c.muted
        v == null -> "Adds tasks" to c.muted
        v.error.isNotEmpty() -> "Couldn't start" to c.red
        v.state == "blocked" -> "Waits for you" to c.red
        v.busy -> "Working" to c.working
        v.state == "idle" -> "Idle" to c.muted
        else -> "Not running" to c.muted
    }
    PlaceTile(HerdrIcons.MessagesSquare, "Coordinator", line, color, modifier, onOpen, "Open the Coordinator")
}

/**
 * A place on a machine as a tile: its icon, its name and one live line in [color]. [edge] marks one
 * that wants a look (low on memory, near a limit).
 */
@Composable
internal fun PlaceTile(
    icon: ImageVector,
    title: String,
    line: String,
    color: Color,
    modifier: Modifier,
    onOpen: () -> Unit,
    openLabel: String,
    edge: Color = Herdr.colors.line,
    iconTint: Color = Herdr.colors.text,
) {
    val c = Herdr.colors
    QuietCard(
        modifier.semantics(mergeDescendants = true) { contentDescription = "$title: $line" },
        edge = edge, onClick = onOpen, onClickLabel = openLabel,
    ) {
        Column(Modifier.padding(horizontal = Space.m, vertical = Space.m)) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(Space.s))
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
