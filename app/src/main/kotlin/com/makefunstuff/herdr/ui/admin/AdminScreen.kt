package com.makefunstuff.herdr.ui.admin

import com.makefunstuff.herdr.ui.design.HerdrDialog
import com.makefunstuff.herdr.ui.design.HerdrMenu
import com.makefunstuff.herdr.ui.design.HerdrMenuItem
import com.makefunstuff.herdr.ui.design.HerdrSheet
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.core.admin.UpdateChannel
import com.makefunstuff.herdr.core.api.IntegrationInfo
import com.makefunstuff.herdr.core.api.PluginAction
import com.makefunstuff.herdr.core.api.PluginInfo
import com.makefunstuff.herdr.core.api.PluginLog
import com.makefunstuff.herdr.core.api.SavedMachineEntry
import com.makefunstuff.herdr.core.host.HostState
import com.makefunstuff.herdr.ui.common.EmptyState
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.Pill
import com.makefunstuff.herdr.ui.common.StatusDot
import com.makefunstuff.herdr.ui.common.detail
import com.makefunstuff.herdr.ui.common.label
import com.makefunstuff.herdr.ui.common.toast
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

private val TABS = listOf("Plugins", "Integrations", "Server", "Machines")
private val OK = Color(0xFF6CC993)
private val WARN = Color(0xFFE2B64A)
private val BAD = Color(0xFFEF7B70)
private val IDLE = Color(0xFF7A847F)

/** herdr's admin features for one machine: plugins, integrations, the server and saved machines. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(nav: NavController, machineId: String, initialTab: Int = 0) {
    val container = LocalContainer.current
    val session = container.hosts.session(machineId)
    if (session == null) {
        EmptyState("Machine not found", "It may have been removed.")
        return
    }
    val state by session.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val model = remember(session) { AdminModel(session, scope) }
    var tab by rememberSaveable { mutableStateOf(initialTab.coerceIn(0, TABS.lastIndex)) }
    val context = LocalContext.current

    // Load when the machine is (back) online, then re-read on a slow timer: herdr has no events for these.
    LaunchedEffect(state.online) {
        if (!state.online) return@LaunchedEffect
        model.refreshAll()
        while (true) {
            delay(15_000)
            when (tab) {
                0 -> model.refreshPlugins()
                1 -> model.refreshIntegrations()
            }
        }
    }
    // While a plugin command runs, follow its log closely.
    val running = (model.logs as? Load.Ready)?.value?.any { it.status == "running" } == true
    LaunchedEffect(running) {
        while (running) {
            delay(1_500)
            model.refreshLogs()
        }
    }
    LaunchedEffect(model.message) {
        // Toasts, not a snackbar: they show above bottom sheets too.
        model.message?.let { context.toast(it); model.message = null }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    title = {
                        Column {
                            Text("herdr on ${state.machine.label}")
                            Text(
                                listOfNotNull(state.herdrVersion?.let { "v$it" }, state.status.label()).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { session.retry(); model.refreshAll() }) { Icon(Icons.Filled.Refresh, "Refresh") }
                    },
                )
                PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                    TABS.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, maxLines = 1) }) }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> PluginsTab(model)
                1 -> IntegrationsTab(model)
                2 -> ServerTab(model, state)
                3 -> MachinesTab(model)
            }
        }
    }
}

// ---------- Plugins ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PluginsTab(model: AdminModel) {
    var openPlugin by remember { mutableStateOf<String?>(null) }
    var openLog by remember { mutableStateOf<PluginLog?>(null) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        loadState(model.plugins, "No plugins installed. Install them on the machine with herdr plugin install.") { plugins ->
            items(plugins, key = { it.pluginId }) { p ->
                PluginCard(p, model, onOpen = { openPlugin = p.pluginId })
            }
        }
        item { SectionTitle("Recent runs") }
        loadState(model.logs, "No plugin commands have run yet.") { logs ->
            items(logs.take(30), key = { it.logId }) { LogRow(it, showPlugin = true) { openLog = it } }
        }
    }
    val plugin = (model.plugins as? Load.Ready)?.value?.firstOrNull { it.pluginId == openPlugin }
    if (plugin != null) PluginSheet(plugin, model, onLog = { openLog = it }, onDismiss = { openPlugin = null })
    openLog?.let { LogSheet(it) { openLog = null } }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PluginCard(p: PluginInfo, model: AdminModel, onOpen: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val busy = model.isBusy("plugin:${p.pluginId}")
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = { menu = true }),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(p.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (p.version.isNotBlank()) Pill("v${p.version}")
                }
                p.description?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    listOf(p.origin, "${p.actions.size} action${if (p.actions.size == 1) "" else "s"}").joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(Modifier.width(64.dp), contentAlignment = Alignment.Center) {
                if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else Switch(checked = p.enabled, onCheckedChange = { model.setEnabled(p, it) })
            }
        }
        HerdrMenu(expanded = menu, onDismissRequest = { menu = false }) {
            HerdrMenuItem(text = { Text("Actions and logs") }, onClick = { menu = false; onOpen() })
            HerdrMenuItem(text = { Text(if (p.enabled) "Disable" else "Enable") }, onClick = { menu = false; model.setEnabled(p, !p.enabled) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PluginSheet(p: PluginInfo, model: AdminModel, onLog: (PluginLog) -> Unit, onDismiss: () -> Unit) {
    val actions = model.actions.filter { it.pluginId == p.pluginId && it.runsOn(model.os) }
    val logs = (model.logs as? Load.Ready)?.value?.filter { it.pluginId == p.pluginId }.orEmpty()
    HerdrSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.title, style = MaterialTheme.typography.titleLarge)
                        Text(p.pluginId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = p.enabled, enabled = !model.isBusy("plugin:${p.pluginId}"), onCheckedChange = { model.setEnabled(p, it) })
                }
            }
            p.description?.let { item { Text(it, style = MaterialTheme.typography.bodyMedium) } }
            p.warnings.forEach { w -> item { Text("⚠ $w", style = MaterialTheme.typography.bodySmall, color = WARN) } }
            item { SectionTitle("Actions") }
            if (actions.isEmpty()) {
                item { Hint(if (p.enabled) "This plugin has no actions." else "Enable the plugin to run its actions.") }
            }
            items(actions, key = { it.actionId }) { a -> ActionRow(a, model, enabled = p.enabled) }
            item { SectionTitle("Logs") }
            if (logs.isEmpty()) item { Hint("Nothing has run yet.") }
            items(logs, key = { it.logId }) { LogRow(it, showPlugin = false) { onLog(it) } }
        }
    }
}

@Composable
private fun ActionRow(a: PluginAction, model: AdminModel, enabled: Boolean) {
    val busy = model.isBusy("action:${a.pluginId}/${a.actionId}")
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(a.title.ifBlank { a.actionId }, style = MaterialTheme.typography.bodyLarge)
            Text(
                (a.description ?: a.actionId) + if (a.contexts.isNotEmpty()) " · ${a.contexts.joinToString()}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        else IconButton(onClick = { model.invoke(a) }, enabled = enabled) { Icon(Icons.Filled.PlayArrow, "Run ${a.title}") }
    }
}

@Composable
private fun LogRow(log: PluginLog, showPlugin: Boolean, onClick: () -> Unit) {
    val (color, text) = when (log.status) {
        "succeeded" -> OK to "ok"
        "failed" -> BAD to (log.exitCode?.let { "exit $it" } ?: "failed")
        else -> WARN to "running"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().combinedClickableCompat(onClick).padding(vertical = 6.dp),
    ) {
        StatusDot(color, size = 8)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                (if (showPlugin) "${log.pluginId} · " else "") + log.trigger,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${time(log.startedUnixMs)} · $text" + (log.error?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogSheet(log: PluginLog, onDismiss: () -> Unit) {
    HerdrSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("${log.pluginId} · ${log.trigger}", style = MaterialTheme.typography.titleMedium)
            Text(
                buildString {
                    append(log.status)
                    log.exitCode?.let { append(" · exit $it") }
                    append(" · started ").append(time(log.startedUnixMs))
                    log.finishedUnixMs?.let { append(" · took ").append(it - log.startedUnixMs).append(" ms") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            log.error?.let { Text(it, color = BAD) }
            Output("Command", log.command.joinToString(" "))
            Output("Output", log.stdout.orEmpty().ifBlank { "(none)" })
            if (!log.stderr.isNullOrBlank()) Output("Errors", log.stderr!!)
        }
    }
}

@Composable
private fun Output(title: String, text: String) {
    Text(title, style = MaterialTheme.typography.labelMedium)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)) {
        SelectionContainer {
            Text(
                text.trimEnd(),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(10.dp),
            )
        }
    }
}

// ---------- Integrations ----------

@Composable
private fun IntegrationsTab(model: AdminModel) {
    var confirmRemove by remember { mutableStateOf<IntegrationInfo?>(null) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Hint("Integrations install herdr's hooks into an agent so herdr can tell when it's working, blocked or done.")
        }
        loadState(model.integrations, "herdr reported no integrations.") { list ->
            val sorted = list.sortedWith(compareByDescending<IntegrationInfo> { it.installed }.thenByDescending { it.available })
            items(sorted, key = { it.target }) { i -> IntegrationRow(i, model, onRemove = { confirmRemove = i }) }
        }
    }
    confirmRemove?.let { i ->
        Confirm(
            title = "Remove the ${i.label} integration?",
            body = "herdr will stop knowing what ${i.label} agents are doing on this machine until it's installed again.",
            action = "Remove",
            onConfirm = { model.uninstall(i) },
            onDismiss = { confirmRemove = null },
        )
    }
}

@Composable
private fun IntegrationRow(i: IntegrationInfo, model: AdminModel, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val busy = model.isBusy("integration:${i.target}")
    val (color, status) = when {
        i.outdated -> WARN to "outdated"
        i.installed -> OK to "installed"
        !i.available -> IDLE to "${i.command} not found on this machine"
        else -> IDLE to "not installed"
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        StatusDot(color)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(i.label.ifBlank { i.target }, style = MaterialTheme.typography.bodyLarge)
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when {
            busy -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            i.outdated -> TextButton(onClick = { model.install(i) }) { Text("Update") }
            !i.installed -> TextButton(onClick = { model.install(i) }, enabled = i.available) { Text("Install") }
        }
        if (i.installed && !busy) {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                HerdrMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    HerdrMenuItem(text = { Text("Reinstall") }, onClick = { menu = false; model.install(i) })
                    HerdrMenuItem(text = { Text("Remove", color = BAD) }, onClick = { menu = false; onRemove() })
                }
            }
        }
    }
}

// ---------- Server ----------

@Composable
private fun ServerTab(model: AdminModel, state: HostState) {
    var confirmStop by remember { mutableStateOf(false) }
    var confirmUpdate by remember { mutableStateOf(false) }
    var pendingChannel by remember { mutableStateOf<UpdateChannel?>(null) }
    var showManifests by rememberSaveable { mutableStateOf(false) }
    val running = state.herdrVersion
    val installed = model.installedVersion
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (state.online) OK else IDLE)
                Spacer(Modifier.width(10.dp))
                Text(state.status.label(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            state.status.detail()?.let { Hint(it) }
            KeyValue("Session", state.machine.session)
            KeyValue("Running server", if (state.online) running?.let { "v$it" } ?: "—" else "not connected")
            KeyValue("Installed herdr", installed?.let { "v$it" } ?: "—")
            if (state.online && running != null && installed != null && running != installed) {
                Hint("The server still runs v$running. It switches to v$installed when it restarts (stop it below, then start herdr on the machine).")
            }
        }

        SectionCard {
            Text("Updates", style = MaterialTheme.typography.titleMedium)
            Text("Channel", style = MaterialTheme.typography.labelMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                UpdateChannel.entries.forEachIndexed { i, c ->
                    SegmentedButton(
                        selected = model.channel == c,
                        enabled = state.online && !model.isBusy("channel") && !model.isBusy("update"),
                        onClick = { if (model.channel != c) pendingChannel = c },
                        shape = SegmentedButtonDefaults.itemShape(i, UpdateChannel.entries.size),
                    ) { Text(c.wire.replaceFirstChar { it.uppercase() }) }
                }
            }
            Busy(model.isBusy("channel"), "Switching channel and installing its latest build…")
            Button(
                onClick = { confirmUpdate = true },
                enabled = state.online && !model.isBusy("update") && !model.isBusy("channel"),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Update herdr") }
            Busy(model.isBusy("update"), "Downloading and installing… this can take a minute.")
        }

        SectionCard {
            Text("Configuration", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { model.reloadConfig() }, enabled = state.online && !model.isBusy("reload-config"), modifier = Modifier.fillMaxWidth()) {
                Text("Reload config")
            }
            OutlinedButton(onClick = { model.reloadManifests() }, enabled = state.online && !model.isBusy("reload-manifests"), modifier = Modifier.fillMaxWidth()) {
                Text("Reload agent manifests")
            }
            when (val m = model.manifests) {
                is Load.Ready -> {
                    val problems = m.value.count { (it.remoteUpdateError ?: it.warning) != null }
                    TextButton(onClick = { showManifests = !showManifests }) {
                        Text(
                            (if (showManifests) "Hide" else "Show") + " ${m.value.size} agent manifests" +
                                if (problems > 0) " · $problems with problems" else "",
                        )
                    }
                }
                is Load.Failed -> com.makefunstuff.herdr.ui.design.ErrorMessage(m.message)
                Load.Loading -> {}
            }
            val shown = (model.manifests as? Load.Ready)?.value.orEmpty()
            if (showManifests) {
                shown.forEach { a ->
                    KeyValue(a.agent, listOfNotNull(a.activeVersion, a.sourceKind.ifBlank { null }).joinToString(" · "))
                    (a.remoteUpdateError ?: a.warning)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = WARN) }
                }
            }
        }

        SectionCard {
            Text("Danger zone", style = MaterialTheme.typography.titleMedium, color = BAD)
            Hint("Stopping the server closes every pane and agent in this session. Start it again by running herdr on the machine.")
            OutlinedButton(
                onClick = { confirmStop = true },
                enabled = state.online && !model.isBusy("stop"),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Stop server", color = BAD) }
        }
    }

    if (confirmStop) {
        val panes = state.snapshot?.panes?.size ?: 0
        val agents = state.snapshot?.agents?.size ?: 0
        Confirm(
            title = "Stop herdr on ${state.machine.label}?",
            body = "This closes $panes pane${if (panes == 1) "" else "s"} and $agents agent${if (agents == 1) "" else "s"} in session ${state.machine.session}. Their work in progress is lost.",
            action = "Stop server",
            onConfirm = { model.stopServer {} },
            onDismiss = { confirmStop = false },
        )
    }
    pendingChannel?.let { c ->
        Confirm(
            title = "Switch to ${c.wire}?",
            body = if (c == UpdateChannel.Preview) {
                "herdr installs the latest preview build right away. Preview builds get fixes first but can be less stable. The running server keeps its version until it restarts."
            } else {
                "herdr installs the latest stable build right away. The running server keeps its version until it restarts."
            },
            action = "Switch",
            destructive = false,
            onConfirm = { model.setChannel(c) },
            onDismiss = { pendingChannel = null },
        )
    }
    if (confirmUpdate) {
        UpdateDialog(
            canHandoff = true,
            onConfirm = { handoff -> model.update(handoff) },
            onDismiss = { confirmUpdate = false },
        )
    }
}

@Composable
private fun UpdateDialog(canHandoff: Boolean, onConfirm: (Boolean) -> Unit, onDismiss: () -> Unit) {
    var handoff by remember { mutableStateOf(false) }
    HerdrDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update herdr?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Installs the newest herdr on this channel. The running server keeps working on its current version until it restarts.")
                if (canHandoff) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = handoff, onCheckedChange = { handoff = it })
                        Text("Move the running session to the new version now (live handoff)", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm(handoff) }) { Text("Update") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------- Saved machines ----------

@Composable
private fun MachinesTab(model: AdminModel) {
    var adding by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<SavedMachineEntry?>(null) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { Hint("Machines this herdr can open from its sidebar (herdr machine). These are herdr's own, separate from the app's machine list.") }
            loadState(model.machines, "No saved machines. Tap Add to save one.") { list ->
                items(list, key = { it.id.ifEmpty { it.target } }) { m -> SavedMachineRow(m, model, onRemove = { confirmRemove = m }) }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { adding = true },
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text("Add") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
    if (adding) AddMachineSheet(model) { adding = false; model.addError = null }
    confirmRemove?.let { m ->
        Confirm(
            title = "Remove ${m.label.ifBlank { m.target }}?",
            body = "herdr forgets this saved machine. Nothing on ${m.target} is changed.",
            action = "Remove",
            onConfirm = { model.removeMachine(m) },
            onDismiss = { confirmRemove = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedMachineRow(m: SavedMachineEntry, model: AdminModel, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val busy = model.isBusy("machine:${m.id}")
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { menu = true }, onLongClick = { menu = true }).padding(vertical = 8.dp),
        ) {
            StatusDot(if (m.enabled) OK else IDLE)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(m.label.ifBlank { m.target }, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${m.target.removePrefix("ssh://")} · session ${m.session}" + if (!m.enabled) " · disabled" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
        HerdrMenu(expanded = menu, onDismissRequest = { menu = false }) {
            HerdrMenuItem(text = { Text("Remove", color = BAD) }, enabled = !busy, onClick = { menu = false; onRemove() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddMachineSheet(model: AdminModel, onDismiss: () -> Unit) {
    var target by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var sessionName by remember { mutableStateOf("") }
    val busy = model.isBusy("machine-add")
    val valid = target.isNotBlank() && !target.trim().startsWith("-") && target.trim().none { it.isWhitespace() }
    HerdrSheet(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(
            Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Save a machine in herdr", style = MaterialTheme.typography.titleLarge)
            Hint("herdr connects to it from this machine to prepare it, so this machine must reach it over SSH without a password prompt.")
            OutlinedTextField(target, { target = it }, label = { Text("SSH target") }, placeholder = { Text("user@host or ssh alias") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(label, { label = it }, label = { Text("Label (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(sessionName, { sessionName = it }, label = { Text("Remote session (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Busy(busy, "Connecting and preparing herdr there…")
            model.addError?.let { Text(it, color = BAD, style = MaterialTheme.typography.bodyMedium) }
            Button(
                onClick = { model.addMachine(target.trim(), label.trim(), sessionName.trim(), onDone = onDismiss) },
                enabled = valid && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save machine") }
        }
    }
}

// ---------- Shared bits ----------

private fun <T> LazyListScope.loadState(load: Load<T>, empty: String, content: LazyListScope.(T) -> Unit) {
    when (load) {
        Load.Loading -> item { com.makefunstuff.herdr.ui.design.LoadingState() }
        is Load.Failed -> item { com.makefunstuff.herdr.ui.design.ErrorState("Couldn't load this", load.message) }
        is Load.Ready -> if ((load.value as? Collection<*>)?.isEmpty() == true) item { Hint(empty) } else content(load.value)
    }
}

@Composable
private fun Confirm(title: String, body: String, action: String, onConfirm: () -> Unit, onDismiss: () -> Unit, destructive: Boolean = true) {
    HerdrDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) { Text(action, color = if (destructive) BAD else Color.Unspecified) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Column(Modifier.padding(top = 12.dp)) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Busy(busy: Boolean, text: String) {
    if (!busy) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Hint(text)
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier = combinedClickable(onClick = onClick)

private fun time(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))
