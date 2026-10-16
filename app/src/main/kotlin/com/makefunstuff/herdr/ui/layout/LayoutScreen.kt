package com.makefunstuff.herdr.ui.layout

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.core.api.LayoutNode
import com.makefunstuff.herdr.core.api.LayoutTemplates
import com.makefunstuff.herdr.core.api.PaneInfo
import com.makefunstuff.herdr.core.api.TabLayout
import com.makefunstuff.herdr.data.LayoutStore
import com.makefunstuff.herdr.data.SavedLayout
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.clipboardText
import com.makefunstuff.herdr.ui.common.copyToClipboard
import com.makefunstuff.herdr.ui.common.toast
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.DangerButton
import com.makefunstuff.herdr.ui.design.HerdrDialog
import com.makefunstuff.herdr.ui.design.HerdrField
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrMenu
import com.makefunstuff.herdr.ui.design.HerdrSheet
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.IconTile
import com.makefunstuff.herdr.ui.design.ListRow
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.design.MenuRow
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.design.SecondaryButton
import com.makefunstuff.herdr.ui.design.SectionHeading
import com.makefunstuff.herdr.ui.design.SheetTitle
import com.makefunstuff.herdr.ui.design.StateBlock
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * A tab's layout: the split tree as a diagram, sliders for its ratios, and saved layouts to apply
 * to this or another tab. Everything goes through herdr, so the desktop sees the same change.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayoutScreen(nav: NavController, machineId: String, paneId: String) {
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
    val opening = snapshot?.pane(paneId)
    // The tab is followed rather than pinned to a pane: applying a saved layout over a tab closes
    // that tab's panes and opens a fresh tab (with a new id), so the screen moves to the result
    // instead of dying with the pane it was opened from.
    var tabId by remember(paneId) { mutableStateOf(opening?.tabId) }
    var workspaceId by remember(paneId) { mutableStateOf(opening?.workspaceId) }
    if (tabId == null && opening != null) {
        tabId = opening.tabId
        workspaceId = opening.workspaceId
    }
    val tab = tabId
    val workspace = workspaceId
    if (snapshot == null || tab == null || workspace == null) {
        EmptyState(if (snapshot == null) "Loading…" else "Pane not found", "It may have been closed.")
        return
    }
    val store = remember { LayoutStore(context) }
    val panes = snapshot.panes.filter { it.tabId == tab }
    // An empty list is normal for a moment while a replace settles; only a tab herdr no longer
    // lists is really gone.
    var swapping by remember { mutableStateOf(false) }
    if (panes.isEmpty() && !swapping && snapshot.tabs.none { it.tabId == tab }) {
        EmptyState("Tab closed", "It is no longer on the machine.")
        return
    }
    // Which pane in the tab to ask herdr about: the one we opened on while it lives, else the first.
    var anchor by remember(paneId) { mutableStateOf(paneId) }
    val anchorId = panes.firstOrNull { it.paneId == anchor }?.paneId ?: panes.firstOrNull()?.paneId

    var tree by remember(tabId) { mutableStateOf<LayoutNode?>(null) }
    var tabLayout by remember(tabId) { mutableStateOf<TabLayout?>(null) }
    var saved by remember { mutableStateOf(store.all()) }
    var menu by remember { mutableStateOf(false) }
    var saveOpen by remember { mutableStateOf(false) }
    var savedOpen by remember { mutableStateOf(false) }
    var applying by remember { mutableStateOf<SavedLayout?>(null) }
    var replaceOpen by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    fun plain(e: Throwable) = e.message ?: "That didn't work"

    val liveKey = snapshot.layout(tab)?.panes?.map { it.paneId to it.rect } ?: emptyList()
    LaunchedEffect(tab, liveKey, reload) {
        val paneNow = anchorId ?: return@LaunchedEffect
        try {
            val api = session.awaitApi()
            tree = api.exportLayout(tab).root
            tabLayout = api.paneLayout(paneNow)
            swapping = false
        } catch (e: Throwable) {
            context.toast(plain(e))
        }
    }

    fun setRatio(path: List<Boolean>, ratio: Float) {
        val paneNow = anchorId ?: return
        scope.launch {
            try {
                val api = session.awaitApi()
                tree = api.setSplitRatio(tab, path, ratio).root
                tabLayout = api.paneLayout(paneNow)
            } catch (e: Throwable) {
                context.toast(plain(e))
            }
        }
    }

    /** [replace] closes this tab's panes and puts the layout in their place. */
    fun apply(root: LayoutNode, replace: Boolean, label: String?) {
        scope.launch {
            try {
                val api = session.awaitApi()
                if (replace) {
                    val applied = api.applyLayout(root, replaceTabId = tab)
                    swapping = true
                    tabId = applied.tabId
                    workspaceId = applied.workspaceId
                    anchor = applied.focusedPaneId
                    context.toast("Replaced this tab's layout")
                } else {
                    api.applyLayout(root, workspaceId = workspace, label = label)
                    context.toast(if (label.isNullOrBlank()) "Opened a new tab" else "Opened tab \"$label\"")
                }
                session.refresh()
            } catch (e: Throwable) {
                context.toast(plain(e))
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Herdr.colors.ground,
        topBar = {
            HerdrTopBar("Layout", subtitle = snapshot.location(workspace, tab) ?: tab, onBack = { nav.popBackStack() }) {
                BarIcon(HerdrIcons.MoreVert, "More", { menu = true })
                HerdrMenu(expanded = menu, onDismissRequest = { menu = false }, title = "Layout", subtitle = snapshot.location(workspace, tab) ?: tab) {
                    MenuRow("Save this layout…", HerdrIcons.Add, { menu = false; saveOpen = true }, enabled = tree != null)
                    MenuRow("Saved layouts", HerdrIcons.Dashboard, { menu = false; savedOpen = true })
                    MenuRow("Copy layout as JSON", HerdrIcons.ContentCopy, {
                        menu = false
                        tree?.let { context.copyToClipboard("layout", LayoutTemplates.encode(it.template()), "Layout copied") }
                    }, enabled = tree != null)
                    MenuRow("Paste layout and apply", HerdrIcons.Download, {
                        menu = false
                        val text = context.clipboardText()
                        if (text.isNullOrBlank()) {
                            context.toast("The clipboard is empty")
                        } else {
                            runCatching { LayoutTemplates.decode(text) }
                                .onSuccess { apply(it, replace = false, label = null) }
                                .onFailure { context.toast("That isn't a layout: ${it.message}") }
                        }
                    })
                    MenuRow("Refresh", HerdrIcons.Refresh, { menu = false; reload++ })
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = Space.screen, end = Space.screen, top = Space.xs, bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            item {
                QuietCard(padded = true) {
                    val layout = tabLayout
                    if (layout == null) {
                        LoadingState("Reading the layout")
                    } else {
                        LayoutDiagram(layout, panes, Modifier.fillMaxWidth().aspectRatio(1.6f))
                        if (layout.zoomed) Text("Zoomed: one pane fills the tab", style = MaterialTheme.typography.labelMedium, color = Herdr.colors.muted, modifier = Modifier.padding(top = Space.s))
                    }
                }
            }

            item { SectionHeading("Split ratios", top = Space.l) }
            val rows = tree?.let { ratioRows(it) } ?: emptyList()
            if (tree != null && rows.isEmpty()) {
                item {
                    Text(
                        "This tab has a single pane. Split it to get a ratio to change.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Herdr.colors.muted,
                    )
                }
            }
            if (rows.isNotEmpty()) item {
                QuietCard(padded = true) {
                    rows.forEach { row -> key(row.path.joinToString("") { if (it) "1" else "0" }) { RatioSlider(row) { setRatio(row.path, it) } } }
                }
            }

            item { SectionHeading("Panes", top = Space.l) }
            item {
                QuietCard {
                    panes.forEach { p ->
                        val rect = tabLayout?.panes?.firstOrNull { it.paneId == p.paneId }?.rect
                        ListRow(
                            p.displayName,
                            subtitle = buildString {
                                if (p.paneId == tabLayout?.focusedPaneId) append("Focused · ")
                                rect?.let { append("${it.width}×${it.height}") }
                            }.ifBlank { p.paneId },
                            leading = { IconTile(HerdrIcons.Terminal, size = 36.dp) },
                            trailing = { Icon(HerdrIcons.ChevronRight, null, tint = Herdr.colors.faint) },
                            onClickLabel = "Open terminal",
                            onClick = { nav.navigate(Routes.terminal(machineId, p.paneId)) },
                        )
                    }
                }
            }

            item { SectionHeading("Saved layouts", top = Space.l) }
            item {
                QuietCard {
                    if (saved.isEmpty()) {
                        Text(
                            "Save this tab's layout, then apply it here or on another tab.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Herdr.colors.muted,
                            modifier = Modifier.padding(start = Space.l, end = Space.l, top = Space.l),
                        )
                    } else {
                        saved.forEach { s -> SavedLayoutRow(s.name, onNewTab = { apply(s.root, replace = false, label = s.name) }, onReplace = { applying = s; replaceOpen = true }, onDelete = { saved = store.remove(s.name) }) }
                    }
                    QuietButton(onClick = { saveOpen = true }, enabled = tree != null, modifier = Modifier.padding(Space.s)) {
                        Icon(HerdrIcons.Add, null, Modifier.size(18.dp))
                        Text("Save this layout")
                    }
                }
            }
        }
    }

    if (saveOpen) {
        var name by remember { mutableStateOf("") }
        HerdrDialog(
            onDismissRequest = { saveOpen = false },
            title = { Text("Save layout") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HerdrField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        placeholder = { Text("dev + logs") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "The layout is saved on this phone and starts fresh shells when applied.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                QuietButton(
                    enabled = name.isNotBlank() && tree != null,
                    onClick = {
                        tree?.let { saved = store.save(name.trim(), it.template()) }
                        saveOpen = false
                        context.toast("Saved \"${name.trim()}\"")
                    },
                ) { Text("Save") }
            },
            dismissButton = { QuietButton(onClick = { saveOpen = false }) { Text("Cancel") } },
        )
    }

    if (savedOpen) {
        HerdrSheet(onDismissRequest = { savedOpen = false }) {
            SheetTitle("Saved layouts")
            Column(Modifier.fillMaxWidth().padding(horizontal = Space.s).padding(bottom = Space.l).navigationBarsPadding()) {
                if (saved.isEmpty()) {
                    StateBlock(HerdrIcons.Dashboard, "Nothing saved yet", "Use Save this layout to keep the current tab's arrangement.")
                }
                saved.forEach { s ->
                    SavedLayoutRow(
                        s.name,
                        onNewTab = { savedOpen = false; apply(s.root, replace = false, label = s.name) },
                        onReplace = { savedOpen = false; applying = s; replaceOpen = true },
                        onDelete = null,
                    )
                }
            }
        }
    }

    if (replaceOpen && applying != null) {
        val target = applying!!
        HerdrDialog(
            onDismissRequest = { replaceOpen = false; applying = null },
            title = { Text("Replace this tab?") },
            text = { Text("The panes in this tab are closed and \"${target.name}\" takes their place. Anything running in them stops.") },
            confirmButton = {
                DangerButton("Replace") {
                    replaceOpen = false
                    applying = null
                    apply(target.root, replace = true, label = null)
                }
            },
            dismissButton = { QuietButton(onClick = { replaceOpen = false; applying = null }) { Text("Cancel") } },
        )
    }
}

private data class RatioRow(val path: List<Boolean>, val direction: String, val ratio: Float, val label: String)

/** Every split in the tree, in reading order, with the path `layout.set_split_ratio` takes. */
private fun ratioRows(node: LayoutNode): List<RatioRow> =
    node.splits().mapIndexed { i, s ->
        RatioRow(s.path, s.direction, s.ratio, "Split ${i + 1} · ${if (s.sideBySide) "columns" else "rows"}")
    }

@Composable
private fun RatioSlider(row: RatioRow, onCommit: (Float) -> Unit) {
    var value by remember(row.path, row.ratio) { mutableStateOf(row.ratio) }
    val c = Herdr.colors
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(row.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("${(value * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge, color = c.muted)
        }
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { onCommit(value) },
            valueRange = 0.1f..0.9f,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = c.field, activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
            ),
        )
    }
}

/** A saved layout: its name, then apply it as a new tab or in place of this one. */
@Composable
private fun SavedLayoutRow(name: String, onNewTab: () -> Unit, onReplace: () -> Unit, onDelete: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(start = Space.l, end = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        QuietButton(onClick = onNewTab) { Text("New tab") }
        QuietButton(onClick = onReplace) { Text("Replace…") }
        if (onDelete != null) BarIcon(HerdrIcons.Delete, "Delete $name", onDelete)
    }
}

/** The tab's panes as herdr lays them out, with split edges and the focused pane marked. */
@Composable
private fun LayoutDiagram(layout: TabLayout, panes: List<PaneInfo>, modifier: Modifier) {
    val outline = Herdr.colors.fieldFocused
    val fill = Herdr.colors.field
    val focused = Herdr.colors.accent
    val labelColor = Herdr.colors.text
    val measurer = rememberTextMeasurer()
    val area = layout.area
    Canvas(modifier) {
        if (area.width <= 0 || area.height <= 0) return@Canvas
        val sx = size.width / area.width
        val sy = size.height / area.height
        layout.panes.forEach { p ->
            val r = p.rect
            val topLeft = Offset((r.x - area.x) * sx + 2f, (r.y - area.y) * sy + 2f)
            val sz = Size(r.width * sx - 4f, r.height * sy - 4f)
            if (sz.width <= 0 || sz.height <= 0) return@forEach
            drawRoundRect(fill, topLeft, sz, CornerRadius(8.dp.toPx()))
            val isFocused = p.paneId == layout.focusedPaneId
            drawRoundRect(
                if (isFocused) focused else outline,
                topLeft,
                sz,
                CornerRadius(8.dp.toPx()),
                style = Stroke(width = if (isFocused) 2.dp.toPx() else 1.dp.toPx()),
            )
            val name = panes.firstOrNull { it.paneId == p.paneId }?.displayName ?: p.paneId
            val text = measurer.measure(
                name,
                style = TextStyle(fontSize = 12.sp, color = labelColor),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                constraints = Constraints(maxWidth = (sz.width - 12f).toInt().coerceAtLeast(0)),
            )
            drawText(text, topLeft = Offset(topLeft.x + 8.dp.toPx(), topLeft.y + 6.dp.toPx()))
        }
        layout.splits.forEach { s ->
            val r = s.rect
            if (s.direction == "right") {
                val x = (r.x - area.x) * sx + r.width * sx * s.ratio
                drawLine(outline, Offset(x, (r.y - area.y) * sy), Offset(x, (r.y + r.height - area.y) * sy), strokeWidth = 3f)
            } else {
                val y = (r.y - area.y) * sy + r.height * sy * s.ratio
                drawLine(outline, Offset((r.x - area.x) * sx, y), Offset((r.x + r.width - area.x) * sx, y), strokeWidth = 3f)
            }
        }
    }
}
