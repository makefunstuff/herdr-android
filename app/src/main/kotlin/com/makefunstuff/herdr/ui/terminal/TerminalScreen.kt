package com.makefunstuff.herdr.ui.terminal

import androidx.compose.foundation.background
import com.makefunstuff.herdr.core.terminal.TerminalPalette
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.core.terminal.Keys
import com.makefunstuff.herdr.core.terminal.TerminalStream
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.copyToClipboard
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(nav: NavController, machineId: String, paneId: String) {
    val container = LocalContainer.current
    val context = LocalContext.current
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
    DisposableEffect(Unit) { onDispose { stream?.close() } }

    fun send(text: String) {
        val out = Keys.withModifiers(text, ctrl, alt)
        ctrl = false
        alt = false
        stream?.send(out)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(pane?.displayName ?: paneId, style = MaterialTheme.typography.titleMedium)
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
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (keepHost) "Fit to phone" else "Desktop size") },
                            onClick = { menu = false; keepHost = !keepHost },
                        )
                        DropdownMenuItem(
                            text = { Text("Copy screen") },
                            onClick = { menu = false; view?.let { context.copyToClipboard("terminal", it.screenText(), "Screen copied") } },
                        )
                        DropdownMenuItem(text = { Text("Reconnect") }, onClick = { menu = false; generation++ })
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            Box(Modifier.weight(1f).fillMaxWidth().background(Color(TerminalPalette.Default.background))) {
                AndroidView(
                    factory = { ctx ->
                        TerminalView(ctx).also { v ->
                            v.onGridSize = { c, r -> grid = c to r }
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
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
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
