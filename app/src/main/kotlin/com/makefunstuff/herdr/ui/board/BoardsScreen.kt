package com.makefunstuff.herdr.ui.board

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.LocalPanes
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.core.home.BoardGroup
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.ErrorMessage
import com.makefunstuff.herdr.ui.design.HerdrChip
import com.makefunstuff.herdr.ui.design.HerdrFab
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.design.SecondaryButton
import com.makefunstuff.herdr.ui.design.SectionHeading
import com.makefunstuff.herdr.ui.share.Ask
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.channels.Channel

/**
 * The Board tab: every machine's work board in one list, a group per machine (what waits for you
 * first, then what is at work and what is to do), read again while it is in front. A task opens on
 * its machine's board; a group's heading opens that whole board (done and archived tasks, search).
 */
@Composable
fun BoardsScreen(nav: NavController) {
    val container = LocalContainer.current
    val panes = LocalPanes.current
    val hosts by container.hosts.hosts.collectAsStateWithLifecycle()
    val boards = rememberBoards()
    val problems = rememberBoardProblems()
    val readNow = remember { Channel<Unit>(Channel.CONFLATED) }
    ReadBoards(readNow, everyMs = 10_000)
    val withBoard = hosts.filter { it.hasBoard }
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    // A machine without a board (or not checked yet since it connected) shows all.
    val machine = chosen?.takeIf { id -> withBoard.any { it.machine.id == id } }
    val groups = remember(boards, machine) { BoardGroup.of(boards, machine) }
    val waiting = remember(boards) { BoardGroup.waiting(boards) }
    // Adding a task: on the machine shown, or the one chosen first.
    var adding by remember { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Herdr.colors.ground,
        topBar = {
            HerdrTopBar("Board", subtitle = if (boards.isEmpty()) null else if (waiting > 0) "$waiting need you" else "Nothing waits for you") {
                BarIcon(HerdrIcons.Search, "Search tasks, agents and chats", { panes.open(nav, Routes.search()) })
                BarIcon(HerdrIcons.Layers, "Review finished work", { panes.open(nav, Routes.review()) })
                BarIcon(HerdrIcons.CalendarClock, "Routines", { panes.open(nav, Routes.routines(machine)) })
                BarIcon(HerdrIcons.MessagesSquare, "Ask the Coordinator", { panes.open(nav, Routes.ask(Ask.COORDINATOR)) })
            }
        },
        floatingActionButton = {
            val online = withBoard.filter { it.online }
            if (online.isNotEmpty()) HerdrFab(null, "Add a task") {
                val only = machine ?: online.singleOrNull()?.machine?.id
                if (only != null) adding = only else choosing = true
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (withBoard.size > 1) {
                LazyRow(contentPadding = PaddingValues(horizontal = Space.screen), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    item { HerdrChip("All machines", selected = machine == null, onClick = { chosen = null }) }
                    items(withBoard, key = { it.machine.id }) { h ->
                        HerdrChip(h.machine.label, selected = machine == h.machine.id, onClick = { chosen = if (machine == h.machine.id) null else h.machine.id })
                    }
                }
            }
            when {
                withBoard.isEmpty() -> EmptyState(
                    if (hosts.none { it.online }) "Not connected yet" else "No work board",
                    if (hosts.none { it.online }) "The boards show once a machine with herdr-projects answers." else "None of your machines has herdr-projects. Its tasks show here once one does.",
                    icon = HerdrIcons.SquareKanban,
                )
                boards.isEmpty() && problems.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingState("Reading the boards") }
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = Space.screen, end = Space.screen, top = Space.xs, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    problems.forEach { (label, p) ->
                        item(key = "problem/$label") { ErrorMessage("$label's board: $p", onRetry = { readNow.trySend(Unit) }) }
                    }
                    groups.forEachIndexed { i, g ->
                        item(key = "h/${g.machineId}") {
                            SectionHeading(
                                listOfNotNull(
                                    g.machineLabel,
                                    g.needsYou.size.takeIf { it > 0 }?.let { "$it need${if (it == 1) "s" else ""} you" },
                                    g.working.size.takeIf { it > 0 }?.let { "$it working" },
                                ).joinToString(" · "),
                                Modifier.clickable(onClickLabel = "Open ${g.machineLabel}'s board") { panes.openBoard(nav, g.machineId) }.heightIn(min = 48.dp),
                                top = if (i == 0) Space.xs else Space.m,
                                trailing = { Icon(HerdrIcons.ChevronRight, null, tint = Herdr.colors.muted, modifier = Modifier.size(20.dp)) },
                            )
                        }
                        if (g.tasks.isEmpty()) item(key = "empty/${g.machineId}") {
                            Text("No open tasks", style = MaterialTheme.typography.bodyMedium, color = Herdr.colors.muted, modifier = Modifier.padding(start = Space.xs))
                        }
                        items(g.tasks, key = { "${g.machineId}/${it.id}" }) { t ->
                            TaskRow(t, onOpen = { panes.openBoard(nav, g.machineId, t.id, alone = true) }, onMenu = { panes.openBoard(nav, g.machineId, t.id, alone = true) })
                        }
                        if (g.done > 0) item(key = "done/${g.machineId}") {
                            Text(
                                "${g.done} done · on ${g.machineLabel}'s board",
                                style = MaterialTheme.typography.labelLarge, color = Herdr.colors.accent,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { panes.openBoard(nav, g.machineId) }.padding(horizontal = Space.xs, vertical = Space.m),
                            )
                        }
                    }
                }
            }
        }
    }

    if (choosing) {
        BoardPanel("Add a task on", onDismiss = { choosing = false }) {
            withBoard.filter { it.online }.forEach { h ->
                SecondaryButton(h.machine.label, Modifier.fillMaxWidth()) { choosing = false; adding = h.machine.id }
            }
        }
    }
    adding?.let { id ->
        container.hosts.session(id)?.let { session ->
            val repos = boards.firstOrNull { it.machineId == id }?.tasks.orEmpty().map { it.repo }.distinct().sorted()
            BoardDialogs(
                pending = Pending.Add, session = session, repos = repos, onDismiss = { adding = null },
                onWords = { _, _ -> }, onConfirm = {}, onAnyway = {},
                onSaved = { adding = null; readNow.trySend(Unit) },
            )
        }
    }
}
