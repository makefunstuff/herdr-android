package com.makefunstuff.herdr.ui.admin

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.makefunstuff.herdr.ui.design.HerdrFab
import com.makefunstuff.herdr.ui.design.RowDivider
import com.makefunstuff.herdr.ui.design.QuietButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.ui.unit.Dp
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.DangerButton
import com.makefunstuff.herdr.ui.design.FieldLabel
import com.makefunstuff.herdr.ui.design.HerdrDialog
import com.makefunstuff.herdr.ui.design.HerdrField
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrMenu
import com.makefunstuff.herdr.ui.design.HerdrSheet
import com.makefunstuff.herdr.ui.design.HerdrTopBar
import com.makefunstuff.herdr.ui.design.InfoLine
import com.makefunstuff.herdr.ui.design.ListRow
import com.makefunstuff.herdr.ui.design.MenuRow
import com.makefunstuff.herdr.ui.design.PrimaryButton
import com.makefunstuff.herdr.ui.design.QuietCard
import com.makefunstuff.herdr.ui.design.RoundAction
import com.makefunstuff.herdr.ui.design.SecondaryButton
import com.makefunstuff.herdr.ui.design.SectionHeading
import com.makefunstuff.herdr.ui.design.SegmentedPills
import com.makefunstuff.herdr.ui.design.Spinner
import com.makefunstuff.herdr.ui.design.StateBlock
import com.makefunstuff.herdr.ui.design.StatusBadge
import com.makefunstuff.herdr.ui.design.SwitchRow
import com.makefunstuff.herdr.ui.design.switchColors
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

private val TABS = listOf("Plugins", "Integrations", "Server", "Machines")
private val OK: Color @Composable get() = Herdr.colors.green
private val WARN: Color @Composable get() = Herdr.colors.amber
private val BAD: Color @Composable get() = Herdr.colors.red
private val IDLE: Color @Composable get() = Herdr.colors.grey

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
    var adding by remember { mutableStateOf(false) }
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
        containerColor = Herdr.colors.ground,
        topBar = {
            Column(Modifier.background(Herdr.colors.ground)) {
                HerdrTopBar(
                    "herdr on ${state.machine.label}",
                    subtitle = listOfNotNull(state.herdrVersion?.let { "v$it" }, state.status.label()).joinToString(" · "),
                    onBack = { nav.popBackStack() },
                ) {
                    BarIcon(HerdrIcons.Refresh, "Refresh", { session.retry(); model.refreshAll() })
                }
                PrimaryScrollableTabRow(
                    selectedTabIndex = tab,
                    edgePadding = Space.s,
                    containerColor = Herdr.colors.ground,
                    contentColor = Herdr.colors.text,
                    divider = { RowDivider(inset = 0.dp) },
                    indicator = {
                        TabRowDefaults.PrimaryIndicator(Modifier.tabIndicatorOffset(tab, matchContentSize = true), width = Dp.Unspecified, color = MaterialTheme.colorScheme.primary)
                    },
                ) {
                    TABS.forEachIndexed { i, t ->
                        Tab(
                            selected = tab == i, onClick = { tab = i },
                            selectedContentColor = Herdr.colors.text, unselectedContentColor = Herdr.colors.muted,
                            text = { Text(t, maxLines = 1, style = MaterialTheme.typography.labelLarge) },
                        )
                    }
                }
            }
        },
        floatingActionButton = { if (tab == 3) HerdrFab("Add") { adding = true } },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> PluginsTab(model)
                1 -> IntegrationsTab(model)
                2 -> ServerTab(model, state)
                3 -> MachinesTab(model, adding, onAdding = { adding = it })
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
    LazyColumn(contentPadding = PaddingValues(Space.screen), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        loadState(model.plugins, "No plugins installed", "Install them on the machine with herdr plugin install.", HerdrIcons.Extension) { plugins ->
            items(plugins, key = { it.pluginId }) { p ->
                PluginCard(p, model, onOpen = { openPlugin = p.pluginId })
            }
        }
        item { SectionTitle("Recent runs") }
        loadState(model.logs, "No runs yet", "Plugin commands show up here when they run.", HerdrIcons.History) { logs ->
            item { QuietCard { logs.take(30).forEach { LogRow(it, showPlugin = true) { openLog = it } } } }
        }
    }
    val plugin = (model.plugins as? Load.Ready)?.value?.firstOrNull { it.pluginId == openPlugin }
    if (plugin != null) PluginSheet(plugin, model, onLog = { openLog = it }, onDismiss = { openPlugin = null })
    openLog?.let { LogSheet(it) { openLog = null } }
}

@Composable
private fun PluginCard(p: PluginInfo, model: AdminModel, onOpen: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val busy = model.isBusy("plugin:${p.pluginId}")
    QuietCard(onClick = onOpen, onLongClick = { menu = true }, padded = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Text(p.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (p.version.isNotBlank()) Pill("v${p.version}")
                }
                p.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Herdr.colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                Text(
                    listOf(p.origin, "${p.actions.size} action${if (p.actions.size == 1) "" else "s"}").joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium, color = Herdr.colors.faint,
                )
            }
            Box(Modifier.width(64.dp), contentAlignment = Alignment.Center) {
                if (busy) Spinner()
                else androidx.compose.material3.Switch(checked = p.enabled, onCheckedChange = { model.setEnabled(p, it) }, colors = switchColors())
            }
        }
        HerdrMenu(expanded = menu, onDismissRequest = { menu = false }, title = p.title) {
            MenuRow("Actions and logs", HerdrIcons.PlayArrow, { menu = false; onOpen() })
            MenuRow(if (p.enabled) "Disable" else "Enable", HerdrIcons.PowerSettingsNew, { menu = false; model.setEnabled(p, !p.enabled) })
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
            Modifier.fillMaxWidth().heightIn(max = 560.dp).navigationBarsPadding(),
            contentPadding = PaddingValues(start = Space.xl, end = Space.xl, bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.title, style = MaterialTheme.typography.titleLarge)
                        Text(p.pluginId, style = MaterialTheme.typography.bodySmall, color = Herdr.colors.muted)
                    }
                    androidx.compose.material3.Switch(checked = p.enabled, enabled = !model.isBusy("plugin:${p.pluginId}"), onCheckedChange = { model.setEnabled(p, it) }, colors = switchColors())
                }
            }
            p.description?.let { item { Text(it, style = MaterialTheme.typography.bodyMedium) } }
            p.warnings.forEach { w -> item { com.makefunstuff.herdr.ui.design.Notice(w, color = WARN, icon = HerdrIcons.Warning) } }
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
                color = Herdr.colors.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (busy) Spinner()
        else RoundAction(HerdrIcons.PlayArrow, "Run ${a.title}", enabled = enabled) { model.invoke(a) }
    }
}

@Composable
private fun LogRow(log: PluginLog, showPlugin: Boolean, onClick: () -> Unit) {
    val (color, text) = when (log.status) {
        "succeeded" -> OK to "ok"
        "failed" -> BAD to (log.exitCode?.let { "exit $it" } ?: "failed")
        else -> WARN to "running"
    }
    ListRow(
        (if (showPlugin) "${log.pluginId} · " else "") + log.trigger,
        subtitle = "${time(log.startedUnixMs)} · $text" + (log.error?.let { " · $it" } ?: ""),
        leading = { StatusDot(color) },
        chevron = true,
        onClick = onClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogSheet(log: PluginLog, onDismiss: () -> Unit) {
    HerdrSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Space.xl).padding(bottom = Space.xl).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Text("${log.pluginId} · ${log.trigger}", style = MaterialTheme.typography.titleLarge)
            Text(
                buildString {
                    append(log.status)
                    log.exitCode?.let { append(" · exit $it") }
                    append(" · started ").append(time(log.startedUnixMs))
                    log.finishedUnixMs?.let { append(" · took ").append(it - log.startedUnixMs).append(" ms") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = Herdr.colors.muted,
            )
            log.error?.let { com.makefunstuff.herdr.ui.design.ErrorMessage(it) }
            Output("Command", log.command.joinToString(" "))
            Output("Output", log.stdout.orEmpty().ifBlank { "(none)" })
            if (!log.stderr.isNullOrBlank()) Output("Errors", log.stderr!!)
        }
    }
}

@Composable
private fun Output(title: String, text: String) {
    FieldLabel(title)
    SelectionContainer { com.makefunstuff.herdr.ui.common.CodeBlock(text.trimEnd()) }
}

// ---------- Integrations ----------

@Composable
private fun IntegrationsTab(model: AdminModel) {
    var confirmRemove by remember { mutableStateOf<IntegrationInfo?>(null) }
    LazyColumn(contentPadding = PaddingValues(Space.screen), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        item {
            Hint("Integrations install herdr's hooks into an agent so herdr can tell when it's working, blocked or done.")
        }
        loadState(model.integrations, "No integrations", "herdr reported none on this machine.", HerdrIcons.Hub) { list ->
            val sorted = list.sortedWith(compareByDescending<IntegrationInfo> { it.installed }.thenByDescending { it.available })
            val (here, missing) = sorted.partition { it.installed || it.available }
            if (here.isNotEmpty()) item(key = "here") {
                QuietCard { here.forEach { i -> IntegrationRow(i, model, onRemove = { confirmRemove = i }) } }
            }
            if (missing.isNotEmpty()) {
                item(key = "missing-h") { SectionTitle("Not on this machine") }
                item(key = "missing") {
                    QuietCard { missing.forEach { i -> IntegrationRow(i, model, onRemove = { confirmRemove = i }) } }
                }
            }
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
        i.outdated -> WARN to "Outdated"
        i.installed -> OK to "Installed"
        !i.available -> null to "${i.command} isn't installed here"
        else -> null to "Not set up"
    }
    ListRow(
        i.label.ifBlank { i.target },
        subtitle = status,
        leading = { StatusDot(color ?: Herdr.colors.off) },
        trailing = {
            when {
                busy -> Spinner()
                i.outdated -> QuietButton(onClick = { model.install(i) }) { Text("Update") }
                !i.installed && i.available -> QuietButton(onClick = { model.install(i) }) { Text("Install") }
                i.installed -> Box {
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button) { menu = true }.semantics { contentDescription = "More" },
                        contentAlignment = Alignment.Center,
                    ) { Icon(HerdrIcons.MoreVert, null, tint = Herdr.colors.muted) }
                    HerdrMenu(expanded = menu, onDismissRequest = { menu = false }, title = i.label.ifBlank { i.target }) {
                        MenuRow("Reinstall", HerdrIcons.RestartAlt, { menu = false; model.install(i) })
                        MenuRow("Remove", HerdrIcons.Delete, { menu = false; onRemove() }, destructive = true)
                    }
                }
            }
        },
    )
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
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.screen),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(state.status.label(), if (state.online) OK else IDLE)
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
            FieldLabel("Channel")
            val channelsEnabled = state.online && !model.isBusy("channel") && !model.isBusy("update")
            SegmentedPills(
                UpdateChannel.entries.map { c -> c.wire.replaceFirstChar { it.uppercase() } },
                UpdateChannel.entries.indexOf(model.channel),
                { i -> val c = UpdateChannel.entries[i]; if (channelsEnabled && model.channel != c) pendingChannel = c },
            )
            Busy(model.isBusy("channel"), "Switching channel and installing its latest build…")
            PrimaryButton(
                onClick = { confirmUpdate = true },
                enabled = state.online && !model.isBusy("update") && !model.isBusy("channel"),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Update herdr") }
            Busy(model.isBusy("update"), "Downloading and installing… this can take a minute.")
        }

        SectionCard {
            Text("Configuration", style = MaterialTheme.typography.titleMedium)
            SecondaryButton(onClick = { model.reloadConfig() }, enabled = state.online && !model.isBusy("reload-config"), modifier = Modifier.fillMaxWidth()) {
                Text("Reload config")
            }
            SecondaryButton(onClick = { model.reloadManifests() }, enabled = state.online && !model.isBusy("reload-manifests"), modifier = Modifier.fillMaxWidth()) {
                Text("Reload agent manifests")
            }
            when (val m = model.manifests) {
                is Load.Ready -> {
                    val problems = m.value.count { (it.remoteUpdateError ?: it.warning) != null }
                    QuietButton(onClick = { showManifests = !showManifests }) {
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
            Text("Danger zone", style = MaterialTheme.typography.titleMedium, color = Herdr.colors.red)
            Hint("Stopping the server closes every pane and agent in this session. Start it again by running herdr on the machine.")
            SecondaryButton(
                onClick = { confirmStop = true },
                enabled = state.online && !model.isBusy("stop"),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Stop server", color = if (state.online) Herdr.colors.red else Herdr.colors.off) }
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
                    SwitchRow("Live handoff", "Move the running session to the new version now", handoff) { handoff = it }
                }
            }
        },
        confirmButton = { QuietButton(onClick = { onDismiss(); onConfirm(handoff) }) { Text("Update") } },
        dismissButton = { QuietButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------- Saved machines ----------

@Composable
private fun MachinesTab(model: AdminModel, adding: Boolean, onAdding: (Boolean) -> Unit) {
    var confirmRemove by remember { mutableStateOf<SavedMachineEntry?>(null) }
    LazyColumn(contentPadding = PaddingValues(Space.screen), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        item { Hint("Machines this herdr can open from its sidebar (herdr machine). These are herdr's own, separate from the app's machine list.") }
        loadState(model.machines, "No saved machines", "Tap + to save one.", HerdrIcons.Lan) { list ->
            item { QuietCard { list.forEach { m -> SavedMachineRow(m, model, onRemove = { confirmRemove = m }) } } }
        }
    }
    if (adding) AddMachineSheet(model) { onAdding(false); model.addError = null }
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
        ListRow(
            m.label.ifBlank { m.target },
            subtitle = "${m.target.removePrefix("ssh://")} · session ${m.session}" + if (!m.enabled) " · disabled" else "",
            leading = { StatusDot(if (m.enabled) OK else IDLE) },
            trailing = if (busy) ({ Spinner() }) else null,
            onLongClick = { menu = true },
            onClick = { menu = true },
        )
        HerdrMenu(expanded = menu, onDismissRequest = { menu = false }, title = m.label.ifBlank { m.target }) {
            MenuRow("Remove", HerdrIcons.Delete, { menu = false; onRemove() }, enabled = !busy, destructive = true)
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
            Modifier.fillMaxWidth().imePadding()
                .padding(horizontal = Space.xl).padding(bottom = Space.l).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Text("Save a machine in herdr", style = MaterialTheme.typography.titleLarge)
            Hint("herdr connects to it from this machine to prepare it, so this machine must reach it over SSH without a password prompt.")
            HerdrField(target, { target = it }, label = { Text("SSH target") }, placeholder = { Text("user@host or ssh alias") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            HerdrField(label, { label = it }, label = { Text("Label (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            HerdrField(sessionName, { sessionName = it }, label = { Text("Remote session (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Busy(busy, "Connecting and preparing herdr there…")
            model.addError?.let { com.makefunstuff.herdr.ui.design.ErrorMessage(it) }
            PrimaryButton(
                onClick = { model.addMachine(target.trim(), label.trim(), sessionName.trim(), onDone = onDismiss) },
                enabled = valid && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save machine") }
        }
    }
}

// ---------- Shared bits ----------

private fun <T> LazyListScope.loadState(load: Load<T>, emptyTitle: String, emptyBody: String, icon: ImageVector, content: LazyListScope.(T) -> Unit) {
    when (load) {
        Load.Loading -> item { com.makefunstuff.herdr.ui.design.LoadingState() }
        is Load.Failed -> item { com.makefunstuff.herdr.ui.design.ErrorState("Couldn't load this", load.message) }
        is Load.Ready -> if ((load.value as? Collection<*>)?.isEmpty() == true) item { StateBlock(icon, emptyTitle, emptyBody) } else content(load.value)
    }
}

@Composable
private fun Confirm(title: String, body: String, action: String, onConfirm: () -> Unit, onDismiss: () -> Unit, destructive: Boolean = true) {
    HerdrDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            if (destructive) DangerButton(action) { onDismiss(); onConfirm() }
            else PrimaryButton(action) { onDismiss(); onConfirm() }
        },
        dismissButton = { QuietButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    QuietCard(padded = true) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) { content() }
    }
}

@Composable
private fun SectionTitle(text: String) = SectionHeading(text, top = Space.l)

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Herdr.colors.muted)
}

@Composable
private fun KeyValue(key: String, value: String) = InfoLine(key, value)

@Composable
private fun Busy(busy: Boolean, text: String) {
    if (!busy) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spinner(size = 18.dp)
        Spacer(Modifier.width(Space.s))
        Hint(text)
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier = combinedClickable(onClick = onClick)

private fun time(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

