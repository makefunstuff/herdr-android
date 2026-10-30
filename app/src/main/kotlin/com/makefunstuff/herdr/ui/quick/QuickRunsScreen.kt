package com.makefunstuff.herdr.ui.quick

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.core.quick.QuickKind
import com.makefunstuff.herdr.core.quick.QuickRun
import com.makefunstuff.herdr.core.quick.RunList
import com.makefunstuff.herdr.data.quick
import com.makefunstuff.herdr.ui.code.shortPath
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.clipboardText
import com.makefunstuff.herdr.ui.common.toast
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.ErrorMessage
import com.makefunstuff.herdr.ui.design.HerdrFab
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrMenu
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.IconTile
import com.makefunstuff.herdr.ui.design.ListRow
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.design.MenuRow
import com.makefunstuff.herdr.ui.design.Notice
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.design.RowDivider
import com.makefunstuff.herdr.ui.design.StatusBadge
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A machine's quick-command runs, newest first, scripts and AI ones: how each went, a line of what
 * it said, and when. A tap opens one (its output, Stop, Run again…); a notification of one that
 * ended opens it here ([openRun]). The machine's own commands are a tap away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickRunsScreen(nav: NavController, machineId: String, openRun: String? = null) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = container.hosts.session(machineId)
    if (session == null) {
        EmptyState("Machine not found", "It may have been removed.")
        return
    }
    val host by session.state.collectAsStateWithLifecycle()
    var list by remember { mutableStateOf<RunList?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var again by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var open by rememberSaveable { mutableStateOf(openRun) }
    var commands by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf<QuickRun?>(null) }

    // Read now, and every few seconds while one of them runs.
    LaunchedEffect(again, host.online, open) {
        if (!host.online) return@LaunchedEffect
        while (true) {
            try {
                val read = session.quick().runs(60)
                list = read
                container.quick.saw(machineId, read.runs)
                problem = null
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                problem = t.message ?: "Couldn't read the runs"
            }
            refreshing = false
            if (list?.runs?.any { it.state.running } != true) return@LaunchedEffect
            delay(3000)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Herdr.colors.ground,
        topBar = {
            HerdrTopBar("Quick commands", subtitle = "Runs on ${host.machine.label}", onBack = { nav.popBackStack() }) {
                BarIcon(HerdrIcons.RefreshCw, "Look again", { again++ })
            }
        },
        floatingActionButton = { if (host.classic) HerdrFab("Commands", icon = HerdrIcons.Zap) { commands = true } },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val l = list
            when {
                l == null && !host.online -> EmptyState("${host.machine.label} is offline", "Its runs show once it answers again.", icon = HerdrIcons.Monitor)
                l == null && problem != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ErrorMessage(problem!!) { again++ } }
                l == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingState("Reading the runs") }
                else -> PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refreshing = true; again++ }, modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = Space.screen, end = Space.screen, top = Space.s, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(Space.s),
                    ) {
                        if (!host.online) item { Notice("Offline · showing what was last read", color = Herdr.colors.notice, icon = HerdrIcons.Clock) }
                        problem?.let { p -> item { ErrorMessage(p) { again++ } } }
                        if (l.runs.isEmpty()) item {
                            Box(Modifier.padding(top = Space.xxl)) {
                                EmptyState(
                                    "No runs yet",
                                    "What you run from Commands shows here, with its output: scripts, and AI commands that carry on while the phone is away.",
                                    icon = HerdrIcons.Zap,
                                )
                            }
                        }
                        if (l.runs.isNotEmpty()) item {
                            QuietCard {
                                l.runs.forEachIndexed { i, r ->
                                    if (i > 0) RowDivider(inset = 68.dp)
                                    RunRow(r, l.now, host.shortPath(r.cwd), onOpen = { open = r.id }, onMenu = { menu = r })
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    open?.let { id ->
        RunSheet(nav, machineId, id, onDismiss = { open = null; again++ }, onRun = { open = it })
    }
    if (commands) QuickSheets(nav, QuickPlace(machineId, label = host.machine.label), onDismiss = { commands = false; again++ })
    menu?.let { r ->
        HerdrMenu(expanded = true, onDismissRequest = { menu = null }, title = r.name, subtitle = r.stateLabel()) {
            MenuRow("Open", HerdrIcons.ChevronRight, { menu = null; open = r.id })
            if (!r.state.running && r.repeatable) MenuRow("Run again", HerdrIcons.RotateCw, {
                menu = null
                scope.launch {
                    runCatching { session.rerunQuick(r, context.clipboardText().orEmpty(), host.machine.label, java.time.LocalDate.now().toString()) }
                        .onSuccess { open = it }.onFailure { context.toast(it.message ?: "Couldn't run it again") }
                }
            })
            if (!r.state.running) MenuRow("Remove from the machine", HerdrIcons.Trash2, {
                menu = null
                scope.launch {
                    runCatching { session.quick().forget(r.id) }.onFailure { context.toast(it.message ?: "Couldn't remove it") }
                    again++
                }
            }, destructive = true)
        }
    }
}

@Composable
private fun RunRow(r: QuickRun, now: Long, where: String, onOpen: () -> Unit, onMenu: () -> Unit) {
    ListRow(
        r.name,
        subtitle = r.error.takeIf { it.isNotEmpty() && !r.state.running } ?: r.preview.ifEmpty { where },
        subtitleColor = if (r.error.isNotEmpty() && !r.state.running) Herdr.colors.red else androidx.compose.ui.graphics.Color.Unspecified,
        meta = runAgo(r.ended.takeIf { it > 0 } ?: r.started, now).ifEmpty { null },
        leading = { IconTile(r.iconVector(), tint = if (r.kind == QuickKind.Prompt) Herdr.colors.accent else null) },
        below = { StatusBadge(r.stateLabel(), r.state.color(), Modifier.padding(top = Space.xs)) },
        onClickLabel = "Open its output",
        onLongClick = onMenu,
        onClick = onOpen,
    )
}
