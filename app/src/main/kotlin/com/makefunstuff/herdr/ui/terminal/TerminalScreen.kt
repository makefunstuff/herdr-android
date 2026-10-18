package com.makefunstuff.herdr.ui.terminal

import com.makefunstuff.herdr.ui.design.confirmTap
import com.makefunstuff.herdr.core.chat.PasswordPrompt
import com.makefunstuff.herdr.ui.common.PasswordCard
import com.makefunstuff.herdr.ui.theme.Mono
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.core.api.CopySearchResult
import com.makefunstuff.herdr.core.api.HerdrApiException
import com.makefunstuff.herdr.core.api.PaneScroll
import com.makefunstuff.herdr.core.api.ResizeDirection
import com.makefunstuff.herdr.core.api.ScrollbackSearch
import com.makefunstuff.herdr.core.api.TextPoint
import com.makefunstuff.herdr.core.api.urlAt
import com.makefunstuff.herdr.core.terminal.Keys
import com.makefunstuff.herdr.core.terminal.BarKey
import com.makefunstuff.herdr.core.terminal.Sticky
import com.makefunstuff.herdr.core.terminal.TerminalQuote
import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.data.DraftStore
import com.makefunstuff.herdr.core.terminal.TerminalEmulator
import com.makefunstuff.herdr.core.terminal.TerminalStream
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.copyToClipboard
import com.makefunstuff.herdr.ui.common.openUrl
import com.makefunstuff.herdr.ui.common.toast
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.PrimaryButton
import com.makefunstuff.herdr.ui.design.SecondaryButton
import com.makefunstuff.herdr.ui.design.HerdrField
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrMenu
import com.makefunstuff.herdr.ui.design.HerdrShapes
import com.makefunstuff.herdr.ui.design.HerdrSheet
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.MenuRow
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.design.SheetTitle
import com.makefunstuff.herdr.ui.design.sheetColor
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.terminalPalette
import androidx.compose.ui.graphics.toArgb
import com.makefunstuff.herdr.ui.theme.Radius
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val SILENT_MS = 15_000L

private val NO_REVISION: StateFlow<Long> = MutableStateFlow(0L)
private val NO_STATUS: StateFlow<TerminalStream.Status> = MutableStateFlow(TerminalStream.Status.Opening)

/** The URL under a viewport cell, read from the phone's own copy of the screen. */
private fun urlAt(emu: TerminalEmulator?, cell: CellPoint): String? {
    val line = emu?.lineText(cell.row) ?: return null
    return urlAt(line, cell.col)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(nav: NavController, machineId: String, paneId: String) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = container.hosts.session(machineId)
    if (session == null) {
        EmptyState("Machine not found", "It may have been removed.")
        return
    }
    val state by session.state.collectAsStateWithLifecycle()
    val snapshot = state.snapshot
    val pane = snapshot?.pane(paneId)
    val hostRect = snapshot?.paneRect(paneId)

    // Its text size, its own (Settings → Text size), which pinching the terminal changes too.
    val sizes by container.textSizes.state.collectAsStateWithLifecycle()
    // The pane is resized to fit the phone; "Desktop size" keeps the host's size and pans instead.
    var keepHost by remember(paneId) { mutableStateOf(false) }
    var grid by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var stream by remember { mutableStateOf<TerminalStream?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    var view by remember { mutableStateOf<TerminalView?>(null) }
    // The key bar: its keys and settings (kept), the sticky modifiers, and which page shows.
    val bar by container.keyBar.state.collectAsStateWithLifecycle()
    var sticky by remember { mutableStateOf(Sticky()) }
    var fnPage by remember { mutableStateOf(false) }
    var editKeys by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    // Scrollback extras: find, selection and resizing.
    var finding by remember(paneId) { mutableStateOf(false) }
    var query by remember(paneId) { mutableStateOf("") }
    var found by remember(paneId) { mutableStateOf<CopySearchResult?>(null) }
    var searching by remember(paneId) { mutableStateOf(false) }
    var search by remember(paneId) { mutableStateOf<ScrollbackSearch?>(null) }
    var selecting by remember(paneId) { mutableStateOf(false) }
    // Selected text on its way to an agent's chat, while the sheet asks which one.
    var quoting by remember(paneId) { mutableStateOf<String?>(null) }
    var resizeOpen by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }

    val keep = keepHost
    val ready = (keep && hostRect != null) || grid != null
    LaunchedEffect(generation, ready) {
        if (!ready) return@LaunchedEffect
        val opened = runCatching {
            val conn = session.awaitConnection(timeoutMs = 30_000)
            val (cols, rows) = if (keep && hostRect != null && hostRect.width > 0 && hostRect.height > 0) {
                hostRect.width to hostRect.height
            } else {
                grid ?: (80 to 24)
            }
            TerminalStream(conn, session.state.value.herdrMachine, paneId, cols, rows, container.scope).also { it.open() }
        }
        val s = opened.getOrNull()
        if (s == null) {
            stream = null
            failure = opened.exceptionOrNull()?.message ?: "Couldn't reach the machine"
            return@LaunchedEffect
        }
        failure = null
        stream = s
        try {
            awaitCancellation()
        } finally {
            s.close()
        }
    }

    // In fit mode, follow the view's grid; switching back to host size restores the host's.
    LaunchedEffect(stream, grid, keepHost) {
        val s = stream ?: return@LaunchedEffect
        if (!keepHost) grid?.let { (c, r) -> s.resize(c, r) }
        else if (hostRect != null && hostRect.width > 0) s.resize(hostRect.width, hostRect.height)
    }

    val revision by (stream?.revision ?: NO_REVISION).collectAsStateWithLifecycle()
    val status by (stream?.status ?: NO_STATUS).collectAsStateWithLifecycle()
    // herdr that can't talk to the server may neither draw nor exit; say so rather than stay blank.
    var silent by remember { mutableStateOf(false) }
    LaunchedEffect(stream) {
        silent = false
        val s = stream ?: return@LaunchedEffect
        delay(SILENT_MS)
        if (s.status.value == TerminalStream.Status.Opening) silent = true
    }
    LaunchedEffect(revision, view) { view?.invalidate() }
    // The line the cursor stands on asks for a password: a masked field over the keys for it, until
    // it is answered or put away (then not again until the pane asks anew).
    val asks = remember(revision, stream) {
        stream?.emulator?.let { e ->
            val line = e.lineText(e.cursorY)
            if (line.drop(e.cursorX).isBlank()) PasswordPrompt.ask(line.take(e.cursorX)) else null
        }
    }
    var putAway by remember(paneId) { mutableStateOf<String?>(null) }
    val hostView = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(asks) { if (asks == null) putAway = null }
    LaunchedEffect(stream, view) { view?.emulator = stream?.emulator }
    LaunchedEffect(sticky, view) {
        view?.ctrlSticky = sticky.ctrl.on
        view?.altSticky = sticky.alt.on
    }
    LaunchedEffect(finding) { if (finding) searchFocus.requestFocus() }
    DisposableEffect(Unit) { onDispose { stream?.close() } }
    BackHandler(enabled = selecting || finding) {
        if (selecting) {
            view?.clearSelection()
            selecting = false
        } else {
            finding = false
            search?.reset()
            found = null
        }
    }

    /** A key of the bar: sent with the sticky modifiers, or the bar's own (a modifier, the F-keys, the keyboard). */
    fun press(key: BarKey) {
        when (key) {
            is BarKey.Send -> {
                stream?.send(key.bytes(sticky.mods))
                sticky = sticky.used()
            }
            is BarKey.Modifier -> sticky = sticky.tapped(key.which)
            BarKey.FKeys -> fnPage = !fnPage
            BarKey.Keyboard -> view?.showKeyboard()
        }
    }

    fun plain(e: Throwable) = e.message ?: "That didn't work"

    /** Scrolls the pane so absolute row [row] is visible, about a third down the screen. */
    suspend fun locateRow(row: Long) {
        val api = session.awaitApi()
        val scroll = api.pane(paneId).scroll ?: return
        val offset = scroll.offsetToShow(row)
        if (offset != scroll.offsetFromBottom) api.scrollPane(paneId, offset)
    }

    fun find(backward: Boolean) {
        val q = query.trim()
        if (q.isEmpty()) return
        searching = true
        scope.launch {
            try {
                val s = search ?: ScrollbackSearch(session.awaitApi(), paneId).also { search = it }
                val result = s.find(q, backward)
                found = result
                result.currentMatch?.let { locateRow(it.start.row) }
            } catch (e: Throwable) {
                context.toast(plain(e))
            }
            searching = false
        }
    }

    /**
     * The selected cells' text, read with herdr's own selection so wrapped lines come out right, to
     * [use]; then the selection ends.
     */
    fun withSelection(use: (String) -> Unit) {
        val (anchor, cursor) = view?.selection ?: return
        scope.launch {
            try {
                val api = session.awaitApi()
                val scroll = api.pane(paneId).scroll ?: PaneScroll()
                val text = api.readSelection(
                    paneId,
                    TextPoint(scroll.absoluteRow(anchor.row), anchor.col),
                    TextPoint(scroll.absoluteRow(cursor.row), cursor.col),
                )
                // Back on the main thread: [use] may open another screen.
                if (text.isBlank()) context.toast("Nothing selected") else withContext(Dispatchers.Main) { use(text) }
            } catch (e: Throwable) {
                context.toast(plain(e))
            }
            view?.clearSelection()
            selecting = false
        }
    }

    fun copySelection() = withSelection { context.copyToClipboard("terminal", it) }

    /** Where a quote says it came from: the pane, and the machine. */
    val quoteFrom = "${pane?.displayName ?: paneId} on ${session.machine.label}"
    val targets = ChatTargets.of(snapshot, pane?.workspaceId)

    /** [quote] at the end of [agent]'s message box, and its chat open. */
    fun quoteTo(agent: AgentInfo, quote: String) {
        container.drafts.add(DraftStore.agent(machineId, agent.paneId), quote)
        nav.navigate(Routes.agent(machineId, agent.paneId))
    }

    /** "Quote in chat": in the workspace's agent's message box, or the one picked when there are several. */
    fun quoteSelection() = withSelection { text ->
        val quote = TerminalQuote.of(text, quoteFrom)
        val only = targets.only
        when {
            targets.all.isEmpty() -> context.toast("No agent runs on ${session.machine.label}")
            only != null -> quoteTo(only, quote)
            else -> quoting = quote
        }
    }

    /** "Send to Coordinator": in the machine's Coordinator chat's message box. */
    fun sendToCoordinator() = withSelection { text ->
        container.drafts.add(DraftStore.coordinator(machineId), TerminalQuote.of(text, quoteFrom))
        nav.navigate(Routes.coordinator(machineId))
    }

    fun openLink(url: String) {
        if (url.startsWith("http://") || url.startsWith("https://")) context.openUrl(url) else context.toast("Link: $url")
    }

    /** A tap on a cell: herdr resolves the link there; the phone's browser opens a URL. */
    fun tapCell(cell: CellPoint) {
        val emu = view?.emulator
        scope.launch {
            try {
                val api = session.awaitApi()
                val region = api.resolveLink(paneId, cell.row, cell.col).firstOrNull { cell.col in it.startCol..it.endCol }
                if (region != null) {
                    val activation = api.activateLink(paneId, region.row, region.startCol)
                    val url = activation.url
                    when {
                        activation.handled -> context.toast("Opened on the host")
                        url != null -> openLink(url)
                        else -> context.toast("That link has no address")
                    }
                }
            } catch (e: HerdrApiException) {
                // Links need the pane to be visible on the host; fall back to the URL on screen.
                val fallback = urlAt(emu, cell)
                if (fallback != null) openLink(fallback) else if (e.code != "stale_target") context.toast(plain(e))
            } catch (e: Throwable) {
                context.toast(plain(e))
            }
        }
    }

    fun resize(direction: ResizeDirection) {
        scope.launch {
            try {
                val result = session.awaitApi().resizePane(paneId, direction)
                if (!result.changed) context.toast(result.reason ?: "The pane can't resize that way")
            } catch (e: Throwable) {
                context.toast(plain(e))
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Herdr.colors.ground,
        topBar = {
            HerdrTopBar(
                pane?.displayName ?: paneId,
                subtitle = buildString {
                    append(session.machine.label)
                    stream?.emulator?.let { append(" · ${it.cols}×${it.rows}") }
                    if (keepHost) append(" · desktop size")
                },
                onBack = { nav.popBackStack() },
            ) {
                BarIcon(HerdrIcons.Sun, "Keep screen on", { container.keyBar.setKeepScreenOn(!bar.keepScreenOn) }, selected = bar.keepScreenOn)
                if (!finding) BarIcon(HerdrIcons.Search, "Find", { finding = true })
                BarIcon(HerdrIcons.Ellipsis, "More", { menu = true })
                HerdrMenu(expanded = menu, onDismissRequest = { menu = false }, title = pane?.displayName ?: paneId, subtitle = session.machine.label) {
                    MenuRow(if (finding) "Hide find" else "Find in scrollback", HerdrIcons.Search, { menu = false; finding = !finding })
                    MenuRow("Select text", HerdrIcons.TextSelect, {
                        menu = false
                        view?.startSelection(CellPoint(0, 0))
                        selecting = true
                        context.toast("Drag to select, then Copy or Quote")
                    })
                    MenuRow("Copy screen", HerdrIcons.Copy, { menu = false; view?.let { context.copyToClipboard("terminal", it.screenText(), "Screen copied") } })
                    MenuRow("Resize pane…", HerdrIcons.Maximize2, { menu = false; resizeOpen = true })
                    MenuRow("Layout…", HerdrIcons.LayoutDashboard, { menu = false; nav.navigate(Routes.layout(machineId, paneId)) })
                    pane?.workspaceId?.let { ws ->
                        MenuRow("Dev servers", HerdrIcons.Globe, { menu = false; nav.navigate(Routes.preview(machineId, ws)) })
                    }
                    MenuRow("Edit key bar…", HerdrIcons.Keyboard, { menu = false; editKeys = true })
                    MenuRow(if (bar.keepScreenOn) "Let the screen sleep" else "Keep screen on", HerdrIcons.Sun, { menu = false; container.keyBar.setKeepScreenOn(!bar.keepScreenOn) })
                    MenuRow(if (keepHost) "Fit to phone" else "Desktop size", if (keepHost) HerdrIcons.Smartphone else HerdrIcons.Monitor, { menu = false; keepHost = !keepHost })
                    MenuRow("Reconnect", HerdrIcons.RefreshCcw, { menu = false; generation++ })
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            if (finding) {
                Surface(color = Herdr.colors.ground) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = Space.m, vertical = Space.xs)) {
                        HerdrField(
                            value = query,
                            onValueChange = {
                                query = it
                                search?.reset()
                                found = null
                            },
                            placeholder = { Text("Find in scrollback") },
                            leadingIcon = { Icon(HerdrIcons.Search, null, Modifier.size(20.dp)) },
                            shape = HerdrShapes.Pill,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { find(true) }),
                            modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when {
                                    searching -> "Searching…"
                                    found == null -> ""
                                    found!!.total == 0L -> "No matches"
                                    else -> "${(found!!.currentGlobal ?: 0) + 1} of ${found!!.total}"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = Herdr.colors.muted,
                                modifier = Modifier.weight(1f).padding(start = Space.xs),
                            )
                            BarIcon(HerdrIcons.ChevronUp, "Older", { find(true) })
                            BarIcon(HerdrIcons.ChevronDown, "Newer", { find(false) })
                            BarIcon(HerdrIcons.X, "Close", {
                                finding = false
                                search?.reset()
                                found = null
                            })
                        }
                    }
                }
            }
            val hc = Herdr.colors
            val termPalette = remember(hc) { hc.terminalPalette() }
            Box(Modifier.weight(1f).fillMaxWidth().background(hc.panel)) {
                AndroidView(
                    factory = { ctx ->
                        TerminalView(ctx).also { v ->
                            androidx.core.content.res.ResourcesCompat.getFont(ctx, com.makefunstuff.herdr.R.font.jetbrains_mono_regular)?.let(v::setTypeface)
                            v.onGridSize = { c, r -> grid = c to r }
                            v.onCellTap = { cell -> v.showKeyboard(); tapCell(cell) }
                            v.onCellLongPress = { cell -> v.startSelection(cell); selecting = true }
                            v.onLongPress = { context.copyToClipboard("terminal", v.screenText(), "Screen copied") }
                            // A key typed with the sticky modifiers: the armed ones go, the kept ones stay.
                            v.onModifiersConsumed = {
                                sticky = sticky.used()
                                v.ctrlSticky = sticky.ctrl.on
                                v.altSticky = sticky.alt.on
                            }
                            v.setFontSizeSp(sizes.terminal)
                            v.onFontSizeChanged = { sp -> container.textSizes.setTerminal(sp) }
                            view = v
                        }
                    },
                    update = { v ->
                        v.setColors(termPalette, hc.termSelection.toArgb())
                        v.onInput = { text -> stream?.send(text) }
                        v.onScroll = { lines -> if (lines != 0) stream?.scroll(lines > 0, kotlin.math.abs(lines)) }
                        if (v.fitToView != !keepHost) v.fitToView = !keepHost
                        v.keepScreenOn = bar.keepScreenOn
                        if (kotlin.math.abs(v.fontSizeSp - sizes.terminal) > 0.01f) v.setFontSizeSp(sizes.terminal)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                val herdr = state.herdr
                val message = failure ?: when (val st = status) {
                    is TerminalStream.Status.Closed -> st.reason + (herdr?.let { "\n\n" + it.explain(session.machine.label) } ?: "")
                    TerminalStream.Status.Opening -> when {
                        !state.online -> "Waiting for ${session.machine.label}…"
                        silent -> "herdr hasn't sent this pane yet." + (herdr?.let { "\n\n" + it.explain(session.machine.label) } ?: "")
                        else -> null
                    }
                    TerminalStream.Status.Live -> null
                }
                if (message == null && herdr?.mismatch == true) {
                    Text(
                        "herdr ${herdr.describe} differs from the server (${herdr.serverVersion})",
                        style = MaterialTheme.typography.labelSmall,
                        color = Herdr.colors.text,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .background(Herdr.colors.tint(Herdr.colors.notice).compositeOver(Herdr.colors.panel))
                            .padding(horizontal = Space.m, vertical = Space.xs),
                    )
                }
                if (message != null) {
                    Surface(
                        color = sheetColor,
                        shape = HerdrShapes.Card,
                        modifier = Modifier.align(Alignment.Center).padding(Space.xl),
                    ) {
                        Column(Modifier.padding(Space.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.l)) {
                            Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            SecondaryButton(onClick = { generation++ }) {
                                Icon(HerdrIcons.RefreshCcw, null, Modifier.size(18.dp))
                                Text("Reconnect")
                            }
                        }
                    }
                }
            }
            if (asks != null && asks != putAway && !selecting) {
                PasswordCard(
                    asks,
                    onSend = { stream?.send(it + Keys.ENTER); hostView.confirmTap() },
                    onDismiss = { putAway = asks },
                    modifier = Modifier.padding(horizontal = Space.s, vertical = Space.xs),
                )
            }
            if (selecting) {
                SelectionBar(
                    onCopy = ::copySelection,
                    onQuote = ::quoteSelection,
                    onCoordinator = if (state.hasBoard) ::sendToCoordinator else null,
                    onCancel = {
                        view?.clearSelection()
                        selecting = false
                    },
                )
            } else {
                KeyBarRows(
                    keys = bar.bar,
                    sticky = sticky,
                    fnPage = fnPage,
                    onKey = ::press,
                    onCursor = { stream?.send(it) },
                    onEdit = { editKeys = true },
                    modifier = Modifier.background(Herdr.colors.ground).navigationBarsPadding(),
                )
            }
        }
    }

    if (editKeys) {
        HerdrSheet(onDismissRequest = { editKeys = false }) { KeyBarEditor(container.keyBar, bar) }
    }

    quoting?.let { quote ->
        AgentPickSheet(
            "Quote in chat",
            "Which agent's message box it goes in",
            targets,
            snapshot,
            onPick = { quoting = null; quoteTo(it, quote) },
            onDismiss = { quoting = null },
        )
    }

    if (resizeOpen) {
        HerdrSheet(onDismissRequest = { resizeOpen = false }) {
            ResizeSheet(
                onPick = {
                    resize(it)
                    resizeOpen = false
                },
            )
        }
    }
}

/**
 * What selected text can do, in place of the key bar: copy it, quote it in an agent's chat, send it to
 * the Coordinator (when the machine has the board). The close button ends the selection.
 */
@Composable
private fun SelectionBar(onCopy: () -> Unit, onQuote: () -> Unit, onCoordinator: (() -> Unit)?, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Herdr.colors.ground).navigationBarsPadding().padding(horizontal = Space.s, vertical = Space.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Drag to select",
                style = MaterialTheme.typography.labelLarge,
                color = Herdr.colors.muted,
                modifier = Modifier.weight(1f).padding(start = Space.s),
            )
            BarIcon(HerdrIcons.X, "Cancel the selection", onCancel)
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = Space.xs),
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PrimaryButton(onClick = onCopy) { Text("Copy") }
            SecondaryButton(onClick = onQuote) { Text("Quote in chat") }
            if (onCoordinator != null) SecondaryButton(onClick = onCoordinator) { Text("Send to Coordinator") }
        }
    }
}

/** Direction pad for `pane.resize`, which moves the pane's edge into a neighbouring split. */
@Composable
private fun ResizeSheet(onPick: (ResizeDirection) -> Unit) {
    SheetTitle("Resize the pane", "Moves the pane's edge, so it only works when the tab is split. The pane grows toward the arrow.")
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Space.xl).padding(bottom = Space.xl).navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        PadKey(HerdrIcons.ChevronUp, "Grow up") { onPick(ResizeDirection.Up) }
        Row(horizontalArrangement = Arrangement.spacedBy(72.dp)) {
            PadKey(HerdrIcons.ChevronLeft, "Grow left") { onPick(ResizeDirection.Left) }
            PadKey(HerdrIcons.ChevronRight, "Grow right") { onPick(ResizeDirection.Right) }
        }
        PadKey(HerdrIcons.ChevronDown, "Grow down") { onPick(ResizeDirection.Down) }
    }
}

@Composable
private fun PadKey(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(56.dp).clip(CircleShape).background(Herdr.colors.field).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Herdr.colors.text, modifier = Modifier.size(28.dp)) }
}
