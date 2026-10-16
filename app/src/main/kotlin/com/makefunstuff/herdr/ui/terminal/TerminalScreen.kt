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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val PREFS = "terminal"
private const val FONT_SP = "font_sp"

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
            TerminalStream(conn, session.machine, paneId, cols, rows, container.scope).also { it.open() }
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
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(pane?.displayName ?: paneId, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            buildString {
                                append(session.machine.label)
                                stream?.emulator?.let { append(" · ${it.cols}×${it.rows}") }
                                if (keepHost) append(" · desktop size")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    if (!finding) IconButton(onClick = { finding = true }) { Icon(Icons.Default.Search, "Find") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (finding) "Hide find" else "Find in scrollback") },
                            onClick = { menu = false; finding = !finding },
                        )
                        DropdownMenuItem(
                            text = { Text("Select text") },
                            onClick = {
                                menu = false
                                view?.startSelection(CellPoint(0, 0))
                                selecting = true
                                context.toast("Drag to select, then Copy")
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Copy screen") },
                            onClick = { menu = false; view?.let { context.copyToClipboard("terminal", it.screenText(), "Screen copied") } },
                        )
                        DropdownMenuItem(text = { Text("Resize pane…") }, onClick = { menu = false; resizeOpen = true })
                        DropdownMenuItem(
                            text = { Text("Layout…") },
                            onClick = { menu = false; nav.navigate(Routes.layout(machineId, paneId)) },
                        )
                        DropdownMenuItem(
                            text = { Text(if (keepHost) "Fit to phone" else "Desktop size") },
                            onClick = { menu = false; keepHost = !keepHost },
                        )
                        DropdownMenuItem(text = { Text("Reconnect") }, onClick = { menu = false; generation++ })
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            if (finding) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 2.dp) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = {
                                query = it
                                search?.reset()
                                found = null
                            },
                            placeholder = { Text("Find in scrollback") },
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { find(true) }) { Icon(Icons.Default.KeyboardArrowUp, "Older") }
                            IconButton(onClick = { find(false) }) { Icon(Icons.Default.KeyboardArrowDown, "Newer") }
                            IconButton(
                                onClick = {
                                    finding = false
                                    search?.reset()
                                    found = null
                                },
                            ) { Icon(Icons.Default.Close, "Close") }
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
                val message = failure ?: when (val st = status) {
                    is TerminalStream.Status.Closed -> st.reason
                    TerminalStream.Status.Opening -> if (state.online) null else "Waiting for ${session.machine.label}…"
                    TerminalStream.Status.Live -> null
                }
                if (message != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    ) {
                        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(message, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { generation++ }) { Text("Reconnect") }
                        }
                    }
                }
            }
            if (selecting) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .navigationBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Drag to select",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                    TextButton(onClick = { copySelection() }) { Text("Copy") }
                    TextButton(
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
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .horizontalScroll(rememberScrollState())
                        .navigationBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
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
        ModalBottomSheet(onDismissRequest = { resizeOpen = false }) {
            ResizeSheet(
                onPick = {
                    resize(it)
                    resizeOpen = false
                },
            )
        }
    }
}

/** A compact key for the row above the keyboard. */
@Composable
private fun Key(label: String, selected: Boolean = false, highlighted: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .heightIn(min = 36.dp)
            .widthIn(min = 40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    selected -> colors.primary
                    highlighted -> colors.secondaryContainer
                    else -> colors.surfaceContainerHighest
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) colors.onPrimary else colors.onSurface,
        )
    }
}

/** Direction pad for `pane.resize`, which moves the pane's edge into a neighbouring split. */
@Composable
private fun ResizeSheet(onPick: (ResizeDirection) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Resize the pane", style = MaterialTheme.typography.titleMedium)
        Text(
            "Moves the pane's edge, so it only works when the tab is split. The pane grows toward the arrow.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(onClick = { onPick(ResizeDirection.Up) }) { Icon(Icons.Default.KeyboardArrowUp, "Grow up") }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconButton(onClick = { onPick(ResizeDirection.Left) }) { Icon(Icons.Default.KeyboardArrowLeft, "Grow left") }
            IconButton(onClick = { onPick(ResizeDirection.Right) }) { Icon(Icons.Default.KeyboardArrowRight, "Grow right") }
        }
        IconButton(onClick = { onPick(ResizeDirection.Down) }) { Icon(Icons.Default.KeyboardArrowDown, "Grow down") }
    }
}
