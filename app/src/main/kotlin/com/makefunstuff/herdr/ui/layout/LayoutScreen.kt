package com.makefunstuff.herdr.ui.layout

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.layout.heightIn
import com.makefunstuff.herdr.ui.design.BarTitle
import com.makefunstuff.herdr.ui.design.HerdrDialog
import com.makefunstuff.herdr.ui.design.HerdrMenu
import com.makefunstuff.herdr.ui.design.HerdrMenuItem
import com.makefunstuff.herdr.ui.design.HerdrSheet
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
        topBar = {
            TopAppBar(
                title = {
                    BarTitle("Layout", snapshot.location(workspace, tab) ?: tab)
                },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    HerdrMenu(expanded = menu, onDismissRequest = { menu = false }, title = "Layout") {
                        HerdrMenuItem(
                            text = { Text("Save this layout…") },
                            enabled = tree != null,
                            onClick = { menu = false; saveOpen = true },
                        )
                        HerdrMenuItem(
                            text = { Text("Saved layouts") },
                            onClick = { menu = false; savedOpen = true },
                        )
                        HerdrMenuItem(
                            text = { Text("Copy layout as JSON") },
                            enabled = tree != null,
                            onClick = {
                                menu = false
                                tree?.let { context.copyToClipboard("layout", LayoutTemplates.encode(it.template()), "Layout copied") }
                            },
                        )
                        HerdrMenuItem(
                            text = { Text("Paste layout and apply") },
                            onClick = {
                                menu = false
                                val text = context.clipboardText()
                                if (text.isNullOrBlank()) {
                                    context.toast("The clipboard is empty")
                                } else {
                                    runCatching { LayoutTemplates.decode(text) }
                                        .onSuccess { apply(it, replace = false, label = null) }
                                        .onFailure { context.toast("That isn't a layout: ${it.message}") }
                                }
                            },
                        )
                        HerdrMenuItem(text = { Text("Refresh") }, onClick = { menu = false; reload++ })
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("The tab", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            if (tabLayout?.zoomed == true) Text("zoomed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        val layout = tabLayout
                        if (layout == null) {
                            Text("Loading…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            LayoutDiagram(layout, panes, Modifier.fillMaxWidth().aspectRatio(1.6f))
                        }
                    }
                }
            }

            item { Text("Split ratios", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp).semantics { heading() }) }
            val rows = tree?.let { ratioRows(it) } ?: emptyList()
            if (tree != null && rows.isEmpty()) {
                item {
                    Text(
                        "This tab has a single pane. Split it to get a ratio to change.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(rows, key = { it.path.joinToString("") { if (it) "1" else "0" } }) { row ->
                RatioSlider(row) { setRatio(row.path, it) }
            }

            item { Text("Panes", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp).semantics { heading() }) }
            items(panes, key = { it.paneId }) { p ->
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(p.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val rect = tabLayout?.panes?.firstOrNull { it.paneId == p.paneId }?.rect
                            Text(
                                buildString {
                                    if (p.paneId == tabLayout?.focusedPaneId) append("focused · ")
                                    rect?.let { append("${it.width}×${it.height}") }
                                }.ifBlank { p.paneId },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        androidx.compose.material3.FilledTonalButton(onClick = { nav.navigate(Routes.terminal(machineId, p.paneId)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Terminal") }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Saved layouts", style = MaterialTheme.typography.titleSmall)
                        if (saved.isEmpty()) {
                            Text(
                                "Save this tab's layout, then apply it here or on another tab.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            saved.forEach { s ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(s.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    TextButton(onClick = { apply(s.root, replace = false, label = s.name) }) { Text("New tab") }
                                    TextButton(onClick = { applying = s; replaceOpen = true }) { Text("Replace…") }
                                    IconButton(onClick = { saved = store.remove(s.name) }) { Icon(Icons.Default.Delete, "Delete ${s.name}") }
                                }
                            }
                        }
                        TextButton(onClick = { saveOpen = true }, enabled = tree != null) {
                            Icon(Icons.Default.Add, null)
                            Spacer(Modifier.width(4.dp))
                            Text("Save this layout")
                        }
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
                    OutlinedTextField(
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
                TextButton(
                    enabled = name.isNotBlank() && tree != null,
                    onClick = {
                        tree?.let { saved = store.save(name.trim(), it.template()) }
                        saveOpen = false
                        context.toast("Saved \"${name.trim()}\"")
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { saveOpen = false }) { Text("Cancel") } },
        )
    }

    if (savedOpen) {
        HerdrSheet(onDismissRequest = { savedOpen = false }) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Saved layouts", style = MaterialTheme.typography.titleMedium)
                if (saved.isEmpty()) {
                    Text(
                        "Nothing saved yet. Use Save this layout to keep the current tab's arrangement.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                saved.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.name, modifier = Modifier.weight(1f))
                        TextButton(onClick = { savedOpen = false; apply(s.root, replace = false, label = s.name) }) { Text("New tab") }
                        TextButton(onClick = { savedOpen = false; applying = s; replaceOpen = true }) { Text("Replace…") }
                    }
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
                TextButton(
                    onClick = {
                        replaceOpen = false
                        applying = null
                        apply(target.root, replace = true, label = null)
                    },
                ) { Text("Replace") }
            },
            dismissButton = { TextButton(onClick = { replaceOpen = false; applying = null }) { Text("Cancel") } },
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
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(row.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text("${(value * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { onCommit(value) },
            valueRange = 0.1f..0.9f,
        )
    }
}

/** The tab's panes as herdr lays them out, with split edges and the focused pane marked. */
@Composable
private fun LayoutDiagram(layout: TabLayout, panes: List<PaneInfo>, modifier: Modifier) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    val fill = MaterialTheme.colorScheme.surfaceContainerHighest
    val focused = MaterialTheme.colorScheme.primary
    val labelColor = MaterialTheme.colorScheme.onSurface
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
            drawRoundRect(fill, topLeft, sz, CornerRadius(6f))
            val isFocused = p.paneId == layout.focusedPaneId
            drawRoundRect(
                if (isFocused) focused else outline,
                topLeft,
                sz,
                CornerRadius(6f),
                style = Stroke(width = if (isFocused) 4f else 2f),
            )
            val name = panes.firstOrNull { it.paneId == p.paneId }?.displayName ?: p.paneId
            val text = measurer.measure(
                name,
                style = TextStyle(fontSize = 12.sp, color = labelColor),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                constraints = Constraints(maxWidth = (sz.width - 12f).toInt().coerceAtLeast(0)),
            )
            drawText(text, topLeft = Offset(topLeft.x + 6f, topLeft.y + 6f))
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
