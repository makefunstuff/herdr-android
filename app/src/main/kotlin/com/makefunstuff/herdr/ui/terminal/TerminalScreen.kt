package com.makefunstuff.herdr.ui.terminal

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
import com.makefunstuff.herdr.core.terminal.TerminalEmulator
import com.makefunstuff.herdr.core.terminal.TerminalPalette
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
import com.makefunstuff.herdr.ui.theme.Radius
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val PREFS = "terminal"
private const val FONT_SP = "font_sp"
private const val SILENT_MS = 15_000L

private val EXTRA_KEYS = listOf(
    "esc" to Keys.ESC, "tab" to Keys.TAB, "↑" to Keys.UP, "↓" to Keys.DOWN, "←" to Keys.LEFT, "→" to Keys.RIGHT,
    "⏎" to Keys.ENTER, "⇧tab" to Keys.SHIFT_TAB, "|" to "|", "/" to "/", "-" to "-", "~" to "~",
    "home" to Keys.HOME, "end" to Keys.END, "pgup" to Keys.PAGE_UP, "pgdn" to Keys.PAGE_DOWN,
)

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

    val prefs = remember { context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE) }
    // The pane is resized to fit the phone; "Desktop size" keeps the host's size and pans instead.
    var keepHost by remember(paneId) { mutableStateOf(false) }
    var grid by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var stream by remember { mutableStateOf<TerminalStream?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    var view by remember { mutableStateOf<TerminalView?>(null) }
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    // Scrollback extras: find, selection and resizing.
    var finding by remember(paneId) { mutableStateOf(false) }
    var query by remember(paneId) { mutableStateOf("") }
    var found by remember(paneId) { mutableStateOf<CopySearchResult?>(null) }
    var searching by remember(paneId) { mutableStateOf(false) }
    var search by remember(paneId) { mutableStateOf<ScrollbackSearch?>(null) }
    var selecting by remember(paneId) { mutableStateOf(false) }
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
    LaunchedEffect(stream, view) { view?.emulator = stream?.emulator }
    LaunchedEffect(ctrl, alt, view) {
        view?.ctrlSticky = ctrl
        view?.altSticky = alt
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

    fun send(text: String) {
        val out = Keys.withModifiers(text, ctrl, alt)
        ctrl = false
        alt = false
        stream?.send(out)
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

    /** Copies the selected cells, using herdr's own selection so wrapped lines come out right. */
    fun copySelection() {
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
                if (text.isBlank()) context.toast("Nothing selected") else context.copyToClipboard("terminal", text)
            } catch (e: Throwable) {
                context.toast(plain(e))
            }
            view?.clearSelection()
            selecting = false
        }
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
                if (!finding) BarIcon(HerdrIcons.Search, "Find", { finding = true })
                BarIcon(HerdrIcons.MoreVert, "More", { menu = true })
                HerdrMenu(expanded = menu, onDismissRequest = { menu = false }, title = pane?.displayName ?: paneId, subtitle = session.machine.label) {
                    MenuRow(if (finding) "Hide find" else "Find in scrollback", HerdrIcons.Search, { menu = false; finding = !finding })
                    MenuRow("Select text", HerdrIcons.SelectAll, {
                        menu = false
                        view?.startSelection(CellPoint(0, 0))
                        selecting = true
                        context.toast("Drag to select, then Copy")
                    })
                    MenuRow("Copy screen", HerdrIcons.ContentCopy, { menu = false; view?.let { context.copyToClipboard("terminal", it.screenText(), "Screen copied") } })
                    MenuRow("Resize pane…", HerdrIcons.OpenInFull, { menu = false; resizeOpen = true })
                    MenuRow("Layout…", HerdrIcons.Dashboard, { menu = false; nav.navigate(Routes.layout(machineId, paneId)) })
                    MenuRow(if (keepHost) "Fit to phone" else "Desktop size", if (keepHost) HerdrIcons.Smartphone else HerdrIcons.Computer, { menu = false; keepHost = !keepHost })
                    MenuRow("Reconnect", HerdrIcons.Sync, { menu = false; generation++ })
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
                            BarIcon(HerdrIcons.KeyboardArrowUp, "Older", { find(true) })
                            BarIcon(HerdrIcons.KeyboardArrowDown, "Newer", { find(false) })
                            BarIcon(HerdrIcons.Close, "Close", {
                                finding = false
                                search?.reset()
                                found = null
                            })
                        }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().background(Color(TerminalPalette.Default.background))) {
                AndroidView(
                    factory = { ctx ->
                        TerminalView(ctx).also { v ->
                            v.onGridSize = { c, r -> grid = c to r }
                            v.onCellTap = { cell -> v.showKeyboard(); tapCell(cell) }
                            v.onCellLongPress = { cell -> v.startSelection(cell); selecting = true }
                            v.onLongPress = { context.copyToClipboard("terminal", v.screenText(), "Screen copied") }
                            v.onModifiersConsumed = { ctrl = false; alt = false }
                            v.setFontSizeSp(prefs.getFloat(FONT_SP, 11f))
                            v.onFontSizeChanged = { sp -> prefs.edit().putFloat(FONT_SP, sp).apply() }
                            view = v
                        }
                    },
                    update = { v ->
                        v.onInput = { text -> stream?.send(text) }
                        v.onScroll = { lines -> if (lines != 0) stream?.scroll(lines > 0, kotlin.math.abs(lines)) }
                        if (v.fitToView != !keepHost) v.fitToView = !keepHost
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
                            .background(Herdr.colors.tint(Herdr.colors.amber).compositeOver(Herdr.colors.ground))
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
                                Icon(HerdrIcons.Sync, null, Modifier.size(18.dp))
                                Text("Reconnect")
                            }
                        }
                    }
                }
            }
            if (selecting) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Herdr.colors.ground)
                        .navigationBarsPadding()
                        .padding(horizontal = Space.s, vertical = Space.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Drag to select",
                        style = MaterialTheme.typography.labelLarge,
                        color = Herdr.colors.muted,
                        modifier = Modifier.weight(1f).padding(start = Space.s),
                    )
                    PrimaryButton(onClick = { copySelection() }) { Text("Copy") }
                    QuietButton(
                        onClick = {
                            view?.clearSelection()
                            selecting = false
                        },
                    ) { Text("Cancel") }
                }
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Herdr.colors.ground)
                        .horizontalScroll(rememberScrollState())
                        .navigationBarsPadding()
                        .padding(horizontal = Space.xs),
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Key("⌨", highlighted = true) { view?.showKeyboard() }
                    Key("ctrl", selected = ctrl) { ctrl = !ctrl }
                    Key("alt", selected = alt) { alt = !alt }
                    EXTRA_KEYS.forEach { (label, keys) -> Key(label) { send(keys) } }
                }
            }
        }
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

/** A key for the row above the keyboard: a 48dp target with a 40dp grey key drawn in it. */
@Composable
private fun Key(label: String, selected: Boolean = false, highlighted: Boolean = false, onClick: () -> Unit) {
    val c = Herdr.colors
    Box(
        Modifier.heightIn(min = 52.dp).widthIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .heightIn(min = 40.dp)
                .widthIn(min = 44.dp)
                .clip(RoundedCornerShape(Radius.s + 2.dp))
                .background(
                    when {
                        selected -> MaterialTheme.colorScheme.primary
                        highlighted -> c.fieldFocused
                        else -> c.field
                    },
                )
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else c.text,
            )
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
        PadKey(HerdrIcons.KeyboardArrowUp, "Grow up") { onPick(ResizeDirection.Up) }
        Row(horizontalArrangement = Arrangement.spacedBy(72.dp)) {
            PadKey(HerdrIcons.ChevronLeft, "Grow left") { onPick(ResizeDirection.Left) }
            PadKey(HerdrIcons.ChevronRight, "Grow right") { onPick(ResizeDirection.Right) }
        }
        PadKey(HerdrIcons.KeyboardArrowDown, "Grow down") { onPick(ResizeDirection.Down) }
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
