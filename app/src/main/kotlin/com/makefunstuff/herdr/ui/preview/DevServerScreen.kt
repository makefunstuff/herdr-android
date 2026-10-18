package com.makefunstuff.herdr.ui.preview

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.ports.DevServers
import com.makefunstuff.herdr.core.ports.ListeningPort
import com.makefunstuff.herdr.data.DraftStore
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.openUrl
import com.makefunstuff.herdr.ui.common.toast
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.ErrorMessage
import com.makefunstuff.herdr.ui.design.HerdrChip
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrMenu
import com.makefunstuff.herdr.ui.design.HerdrShapes
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.design.MenuRow
import com.makefunstuff.herdr.ui.design.SecondaryButton
import com.makefunstuff.herdr.ui.design.sheetColor
import com.makefunstuff.herdr.ui.images.ImageCodec
import com.makefunstuff.herdr.ui.terminal.AgentPickSheet
import com.makefunstuff.herdr.ui.terminal.ChatTargets
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import com.makefunstuff.herdr.ui.worktrees.workspaceFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A workspace's dev servers: what listens on the machine in it (vite on 5173, uvicorn on 8000), one
 * of them open in an in-app browser through the app's SSH connection (a port of the phone forwarded
 * to the machine's). "Phone size" lays the page out at the phone's width or a desktop's; "Screenshot
 * to agent" puts the page as it shows in an agent's message box.
 */
@Composable
fun DevServerScreen(nav: NavController, machineId: String, workspaceId: String, startPort: Int?) {
    val container = LocalContainer.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val session = container.hosts.session(machineId)
    if (session == null) {
        EmptyState("Machine not found", "It may have been removed.")
        return
    }
    val state by session.state.collectAsStateWithLifecycle()
    val snap = state.snapshot
    val ws = snap?.workspace(workspaceId)
    val wsName = ws?.label?.ifBlank { null } ?: workspaceId

    // What listens on the machine, read again with Refresh.
    var looks by remember { mutableIntStateOf(0) }
    var ports by remember { mutableStateOf<List<ListeningPort>?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(looks) {
        failed = null
        try {
            ports = DevServers.list { command, timeout -> session.awaitConnection().run(command, timeout) }
        } catch (e: Exception) {
            failed = e.message ?: "Couldn't read what listens on ${session.machine.label}"
        }
    }
    val folders = listOfNotNull(ws?.worktree?.checkoutPath, snap?.workspaceFolder(workspaceId)) +
        snap?.panes.orEmpty().filter { it.workspaceId == workspaceId }.mapNotNull { it.cwd }
    val here = ports?.let { DevServers.inWorkspace(it, workspaceId, folders) }.orEmpty()
    val others = ports?.let { all -> DevServers.own(all).filter { p -> here.none { it.port == p.port } } }.orEmpty()
    var showOthers by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf(startPort) }
    // The one tapped, else the workspace's first that serves a web page (or may: not asked).
    val port = (here + others).firstOrNull { it.port == chosen } ?: here.firstOrNull { it.web != false }

    // The page: a port of the phone forwarded to the server's, for as long as it is open.
    var phoneSize by remember { mutableStateOf(true) }
    var url by remember { mutableStateOf<String?>(null) }
    var unreachable by remember { mutableStateOf<String?>(null) }
    val page = remember { PageController() }
    LaunchedEffect(port?.port, port?.connectHost, looks) {
        url = null
        unreachable = null
        val p = port ?: return@LaunchedEffect
        val forward = try {
            val conn = session.awaitConnection()
            withContext(Dispatchers.IO) { conn.forward(p.connectHost, p.port) }
        } catch (e: Exception) {
            unreachable = e.message ?: "Couldn't open a tunnel to port ${p.port}"
            return@LaunchedEffect
        }
        url = "http://localhost:${forward.localPort}/"
        try {
            awaitCancellation()
        } finally {
            forward.close()
        }
    }
    BackHandler(enabled = page.canGoBack) { page.back() }

    var menu by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val targets = ChatTargets.of(snap, workspaceId)

    /** The page as it shows, in [agent]'s message box with a line saying what it is, and its chat open. */
    fun screenshotTo(agent: AgentInfo) {
        val p = port ?: return
        val raw = container.images.newFile("png")
        if (!page.screenshot(raw)) {
            raw.delete()
            context.toast("The page isn't on screen yet")
            return
        }
        scope.launch(Dispatchers.Main) {
            val image = withContext(Dispatchers.IO) {
                runCatching { ImageCodec.prepare(raw, "image/png", "localhost-${p.port}.png", container.images) }.getOrNull().also { raw.delete() }
            }
            if (image == null) {
                context.toast("Couldn't keep the screenshot")
                return@launch
            }
            val key = DraftStore.agent(machineId, agent.paneId)
            if (!container.images.add(key, image)) {
                context.toast("That message has as many pictures as it can take")
                return@launch
            }
            container.drafts.add(key, "localhost:${p.port}${page.address} (${p.name}), as the phone shows it" + if (phoneSize) ":" else " at desktop size:")
            nav.navigate(Routes.agent(machineId, agent.paneId))
        }
    }

    fun screenshot() {
        val only = targets.only
        when {
            targets.all.isEmpty() -> context.toast("No agent runs on ${session.machine.label}")
            only != null -> screenshotTo(only)
            else -> picking = true
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Herdr.colors.ground,
        topBar = {
            HerdrTopBar(
                port?.let { "localhost:${it.port}" } ?: "Dev servers",
                subtitle = buildString {
                    append(port?.name ?: wsName)
                    append(" on ${session.machine.label}")
                    if (port != null) append(" · through SSH")
                    if (port != null && !phoneSize) append(" · desktop size")
                },
                onBack = { nav.popBackStack() },
            ) {
                BarIcon(HerdrIcons.RefreshCw, "Refresh", {
                    looks++
                    page.reload()
                })
                BarIcon(HerdrIcons.Ellipsis, "More", { menu = true })
                HerdrMenu(expanded = menu, onDismissRequest = { menu = false }, title = wsName, subtitle = session.machine.label) {
                    MenuRow("Open in the phone's browser", HerdrIcons.ExternalLink, {
                        menu = false
                        url?.let { context.openUrl(it.trimEnd('/') + page.address) }
                    }, enabled = url != null)
                    MenuRow(if (phoneSize) "Desktop size" else "Phone size", if (phoneSize) HerdrIcons.Monitor else HerdrIcons.Smartphone, {
                        menu = false
                        phoneSize = !phoneSize
                    })
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val listed = here + if (showOthers || here.none { it.web != false }) others else emptyList()
            if (listed.isNotEmpty() || others.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().background(Herdr.colors.panel).horizontalScroll(rememberScrollState())
                        .padding(horizontal = Space.m, vertical = Space.s),
                    horizontalArrangement = Arrangement.spacedBy(Space.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    listed.forEach { p ->
                        HerdrChip(
                            p == port,
                            onClick = { chosen = p.port },
                            label = {
                                Text(
                                    ":${p.port} ${p.name}",
                                    color = if (p.web == false && p != port) Herdr.colors.muted else Color.Unspecified,
                                    maxLines = 1,
                                )
                            },
                            modifier = Modifier.semantics {
                                contentDescription = "Port ${p.port}, ${p.name}" + if (p.web == false) ", doesn't answer as a web page" else ""
                            },
                        )
                    }
                    if (listed.size < here.size + others.size) {
                        HerdrChip("+${others.size} on the machine", false, { showOthers = true })
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().background(Herdr.colors.panel)) {
                val list = ports
                when {
                    failed != null && list == null -> Column(Modifier.padding(Space.l)) { ErrorMessage(failed!!, onRetry = { looks++ }) }
                    list == null -> LoadingState("Looking at what listens on ${session.machine.label}…")
                    port == null -> EmptyState(
                        "No dev server in $wsName",
                        if (here.isEmpty() && others.isEmpty()) "Nothing of yours listens on ${session.machine.label}. Start one in the workspace (npm run dev, …), then refresh."
                        else "Nothing started in it serves a web page. Start one (npm run dev, …), then refresh, or open a port above.",
                        icon = HerdrIcons.Globe,
                    )
                    unreachable != null -> Column(Modifier.padding(Space.l)) { ErrorMessage(unreachable!!, onRetry = { looks++ }) }
                    url == null -> LoadingState("Opening port ${port.port} through SSH…")
                    else -> {
                        PageView(url!!, phoneSize, page, Modifier.fillMaxSize())
                        page.error?.let { why ->
                            Surface(color = sheetColor, shape = HerdrShapes.Card, modifier = Modifier.align(Alignment.Center).padding(Space.xl)) {
                                Column(Modifier.padding(Space.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.l)) {
                                    Text(
                                        "localhost:${port.port} didn't answer ($why).",
                                        style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                                    )
                                    SecondaryButton(onClick = { looks++; page.reload() }) {
                                        Icon(HerdrIcons.RefreshCcw, null, Modifier.size(18.dp))
                                        Text("Try again")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (port != null) {
                Row(
                    Modifier.fillMaxWidth().background(Herdr.colors.ground).navigationBarsPadding().padding(horizontal = Space.m, vertical = Space.s),
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SecondaryButton(onClick = ::screenshot, enabled = url != null && targets.all.isNotEmpty()) {
                        Icon(HerdrIcons.Upload, null, Modifier.size(18.dp))
                        Text("Screenshot to agent")
                    }
                    HerdrChip(
                        phoneSize,
                        onClick = { phoneSize = !phoneSize },
                        label = { Text("Phone size") },
                        leadingIcon = { Icon(HerdrIcons.Smartphone, null, Modifier.size(16.dp)) },
                    )
                }
            }
        }
    }

    if (picking) {
        AgentPickSheet(
            "Screenshot to agent",
            "Whose message box the picture of localhost:${port?.port} goes in",
            targets,
            snap,
            onPick = { picking = false; screenshotTo(it) },
            onDismiss = { picking = false },
        )
    }
}
