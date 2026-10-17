package com.makefunstuff.herdr.ui.quick

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.makefunstuff.herdr.Routes
import com.makefunstuff.herdr.core.quick.QuickCommand
import com.makefunstuff.herdr.core.quick.QuickKind
import com.makefunstuff.herdr.core.quick.QuickListing
import com.makefunstuff.herdr.core.quick.QuickScope
import com.makefunstuff.herdr.data.quick
import com.makefunstuff.herdr.ui.code.CodeSpot
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.common.clipboardText
import com.makefunstuff.herdr.ui.common.copyToClipboard
import com.makefunstuff.herdr.ui.common.toast
import com.makefunstuff.herdr.ui.design.BarIcon
import com.makefunstuff.herdr.ui.design.ErrorMessage
import com.makefunstuff.herdr.ui.design.HerdrDialog
import com.makefunstuff.herdr.ui.design.HerdrField
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.design.HerdrMenu
import com.makefunstuff.herdr.ui.design.HerdrSheet
import com.makefunstuff.herdr.ui.design.IconTile
import com.makefunstuff.herdr.ui.design.ListRow
import com.makefunstuff.herdr.ui.design.LoadingState
import com.makefunstuff.herdr.ui.design.MenuRow
import com.makefunstuff.herdr.ui.design.MenuSection
import com.makefunstuff.herdr.ui.design.Notice
import com.makefunstuff.herdr.ui.design.PrimaryButton
import com.makefunstuff.herdr.ui.design.QuietButton
import com.makefunstuff.herdr.ui.design.SecondaryButton
import com.makefunstuff.herdr.ui.design.StatusBadge
import com.makefunstuff.herdr.ui.design.SwitchRow
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.Space
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private sealed interface Step {
    data object Commands : Step
    data class Run(val id: String) : Step
    data class New(val listing: QuickListing?) : Step
}

/**
 * The quick commands of [place]: its project's and the machine's, the pinned ones first. A tap
 * runs one (asking first, or for its arguments, when its header says so) and shows its output;
 * "New command" asks an agent to write one.
 */
@Composable
fun QuickSheets(nav: NavController, place: QuickPlace, onDismiss: () -> Unit, start: String? = null) {
    var step by remember { mutableStateOf<Step>(start?.let { Step.Run(it) } ?: Step.Commands) }
    when (val s = step) {
        Step.Commands -> CommandsSheet(nav, place, onDismiss, onRun = { step = Step.Run(it) }, onNew = { step = Step.New(it) })
        is Step.Run -> RunSheet(nav, place.machine, s.id, onDismiss = onDismiss, onBack = { step = Step.Commands }, onRun = { step = Step.Run(it) })
        is Step.New -> NewCommandSheet(place, s.listing, onBack = { step = Step.Commands }, onDismiss = onDismiss)
    }
}

/** What a tap on a command waits for: a yes, or its arguments. */
private data class Asking(val command: QuickCommand, val inPane: Boolean)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommandsSheet(nav: NavController, place: QuickPlace, onDismiss: () -> Unit, onRun: (String) -> Unit, onNew: (QuickListing?) -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = container.hosts.session(place.machine)
    val host = session?.state?.collectAsStateWithLifecycle()?.value
    val pins by container.quick.pins.collectAsStateWithLifecycle()
    var again by remember { mutableIntStateOf(0) }
    var listing by remember(place) { mutableStateOf(container.quick.listed(place.machine, place.dir)) }
    var problem by remember { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf(true) }
    var asking by remember { mutableStateOf<Asking?>(null) }
    var menu by remember { mutableStateOf<QuickCommand?>(null) }
    var starting by remember { mutableStateOf<String?>(null) }
    val online = host?.online == true

    // Read every time it opens (the scripts may have changed since), and again on Refresh.
    LaunchedEffect(place, again, online) {
        if (session == null || !online) { reading = false; return@LaunchedEffect }
        reading = true
        try {
            listing = session.quick().list(place.dir).also { container.quick.list(place.machine, place.dir, it) }
            problem = null
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            problem = t.message ?: "Couldn't read the commands"
        }
        reading = false
    }

    fun run(command: QuickCommand, args: String, inPane: Boolean) {
        val s = session ?: return
        starting = command.name
        scope.launch {
            val ctx = place.context(listing, context.clipboardText().orEmpty(), host?.machine?.label.orEmpty(), java.time.LocalDate.now().toString())
            runCatching {
                if (inPane && command.kind == QuickKind.Script) {
                    val pane = s.startQuickInPane(command, ctx, args, place.workspaceId)
                    onDismiss()
                    nav.navigate(Routes.terminal(place.machine, pane))
                } else onRun(s.startQuick(command, ctx, args))
            }.onFailure { context.toast(it.message ?: "Couldn't start ${command.name}") }
            starting = null
        }
    }

    fun tap(command: QuickCommand, inPane: Boolean = command.pane) {
        if (command.confirm || command.args != null) asking = Asking(command, inPane) else run(command, "", inPane)
    }

    HerdrSheet(onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(start = Space.xl, end = Space.s, bottom = Space.s), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Quick commands", style = MaterialTheme.typography.titleLarge, color = Herdr.colors.strong)
                val where = place.label.ifEmpty { listing?.repo?.substringAfterLast('/')?.ifEmpty { null } ?: host?.machine?.label.orEmpty() }
                Text(listOfNotNull(where.ifEmpty { null }, listing?.branch?.ifEmpty { null }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = Herdr.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BarIcon(HerdrIcons.History, "Runs", { onDismiss(); nav.navigate(Routes.quickRuns(place.machine)) })
            BarIcon(HerdrIcons.RefreshCw, "Look again", { again++ }, enabled = online && !reading)
        }
        val list = listing
        when {
            session == null -> ErrorMessage("This machine isn't in the app any more", Modifier.padding(horizontal = Space.xl))
            !online && list == null -> Notice("${host?.machine?.label ?: "The machine"} is offline", Modifier.padding(horizontal = Space.l), icon = HerdrIcons.Clock)
            list == null && problem != null -> ErrorMessage(problem!!, Modifier.padding(horizontal = Space.xl)) { again++ }
            list == null -> LoadingState("Reading the commands", Modifier.padding(vertical = Space.xl))
            else -> {
                if (problem != null) ErrorMessage(problem!!, Modifier.padding(horizontal = Space.xl, vertical = Space.s)) { again++ }
                CommandList(list, pins[place.machine].orEmpty(), starting, onTap = { tap(it) }, onMenu = { menu = it })
                if (list.commands.isEmpty()) Empty(list)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            SecondaryButton(onClick = { onNew(listing) }, enabled = session != null, modifier = Modifier.weight(1f)) {
                Icon(HerdrIcons.Plus, null, Modifier.size(16.dp))
                Text("New command", Modifier.padding(start = Space.xs))
            }
        }
    }

    asking?.let { a -> AskDialog(a.command, onDismiss = { asking = null }) { args -> asking = null; run(a.command, args, a.inPane) } }

    menu?.let { c ->
        val pinned = c.key in pins[place.machine].orEmpty()
        HerdrMenu(expanded = true, onDismissRequest = { menu = null }, title = c.name, subtitle = c.path) {
            MenuRow("Run", HerdrIcons.Play, { menu = null; tap(c, inPane = false) })
            if (c.kind == QuickKind.Script) MenuRow("Run in a new pane", HerdrIcons.SquareTerminal, { menu = null; tap(c, inPane = true) })
            MenuRow(if (pinned) "Unpin" else "Pin to the top", if (pinned) HerdrIcons.PinOff else HerdrIcons.Pin, {
                menu = null
                container.quick.setPinned(place.machine, c.key, !pinned)
            })
            MenuRow("Read the file", HerdrIcons.FileCode, {
                menu = null
                onDismiss()
                nav.navigate(Routes.codeFile(CodeSpot(place.machine, c.folder), c.fileName))
            })
            MenuRow("Copy its path", HerdrIcons.Copy, { menu = null; context.copyToClipboard("Path", c.path) })
        }
    }
}

/** The commands: pinned, then the project's, then the machine's. */
@Composable
private fun CommandList(list: QuickListing, pins: List<String>, starting: String?, onTap: (QuickCommand) -> Unit, onMenu: (QuickCommand) -> Unit) {
    val pinned = pins.mapNotNull { k -> list.commands.firstOrNull { it.key == k } }
    val project = list.commands.filter { it.scope == QuickScope.Project && it !in pinned }
    val global = list.commands.filter { it.scope == QuickScope.Global && it !in pinned }
    @Composable
    fun group(title: String, items: List<QuickCommand>) {
        if (items.isEmpty()) return
        MenuSection(title)
        items.forEach { c -> CommandRow(c, pinned = c in pinned, starting = starting == c.name, onTap = { onTap(c) }, onMenu = { onMenu(c) }) }
    }
    Column(Modifier.padding(horizontal = Space.xs)) {
        group("Pinned", pinned)
        group(list.repo.substringAfterLast('/').ifEmpty { "This project" }, project)
        group("Everywhere", global)
    }
}

@Composable
private fun CommandRow(c: QuickCommand, pinned: Boolean, starting: Boolean, onTap: () -> Unit, onMenu: () -> Unit) {
    ListRow(
        c.name,
        subtitle = if (starting) "Starting…" else c.description.ifEmpty { c.fileName },
        leading = { IconTile(c.iconVector(), tint = if (c.kind == QuickKind.Prompt) Herdr.colors.accent else null) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                if (pinned) Icon(HerdrIcons.Pin, "Pinned", tint = Herdr.colors.muted, modifier = Modifier.size(16.dp))
                if (c.kind == QuickKind.Prompt) StatusBadge("AI", Herdr.colors.accent)
                else if (c.pane) StatusBadge("Pane", Herdr.colors.idle)
                BarIcon(HerdrIcons.Ellipsis, "More for ${c.name}", onMenu)
            }
        },
        enabled = !starting,
        onClickLabel = "Run ${c.name}",
        onLongClick = onMenu,
        onClick = onTap,
    )
}

/** No commands there yet: where they go, and how to make one. */
@Composable
private fun Empty(list: QuickListing) {
    Column(Modifier.padding(horizontal = Space.xl, vertical = Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Text("No quick commands here yet", style = MaterialTheme.typography.titleMedium, color = Herdr.colors.strong)
        Text(
            "Put an executable script, or a .prompt.md for an AI one, in " +
                (if (list.projectFolder.isNotEmpty()) "${short(list.projectFolder, list.home)} for this project, or in " else "") +
                "${short(list.globalFolder, list.home)} for everywhere on this machine. Or ask an agent to write one.",
            style = MaterialTheme.typography.bodyMedium, color = Herdr.colors.muted,
        )
    }
}

/** [path] with the home folder as `~`. */
fun short(path: String, home: String): String = if (home.isNotEmpty() && path.startsWith("$home/")) "~" + path.removePrefix(home) else path

/** Asks before [command] runs: yes or no, or its arguments (the question its header gives). */
@Composable
private fun AskDialog(command: QuickCommand, onDismiss: () -> Unit, onRun: (String) -> Unit) {
    var args by remember { mutableStateOf("") }
    HerdrDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (command.args != null) command.name else "Run ${command.name}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                if (command.description.isNotEmpty()) Text(command.description)
                if (command.args != null) HerdrField(
                    args, { args = it }, label = command.args, modifier = Modifier.fillMaxWidth(), singleLine = command.kind == QuickKind.Script,
                    minLines = if (command.kind == QuickKind.Prompt) 2 else 1,
                )
                else Text("It asks before it runs: it may change something on the machine.")
            }
        },
        dismissButton = { QuietButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = { PrimaryButton("Run") { onRun(args.trim()) } },
    )
}

/**
 * "New command": what it should do, whether a script or an AI prompt, for this project or
 * everywhere, and who writes it (the Coordinator, the agent at work here, the task's agent).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewCommandSheet(place: QuickPlace, listing: QuickListing?, onBack: () -> Unit, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = container.hosts.session(place.machine)
    val host = session?.state?.collectAsStateWithLifecycle()?.value
    var prompt by remember { mutableStateOf(false) }
    var global by remember { mutableStateOf(listing?.projectFolder.isNullOrEmpty()) }
    var what by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    // Who writes it: the agent at work here, the task's agent, or the Coordinator.
    val writers = buildList {
        if (place.pane != null) add("agent" to (place.agentName ?: "This agent"))
        if (place.task != null && host?.hasBoard == true) add("task" to "${place.taskName ?: "The task"}'s agent")
        if (host?.hasBoard == true) add("coordinator" to "Coordinator")
    }
    var writer by remember { mutableStateOf(writers.firstOrNull()?.first) }
    val folder = if (global || listing?.projectFolder.isNullOrEmpty()) listing?.globalFolder ?: "~/.config/herdr/commands" else listing!!.projectFolder

    HerdrSheet(onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(start = Space.s, end = Space.xl, bottom = Space.s), verticalAlignment = Alignment.CenterVertically) {
            BarIcon(HerdrIcons.ArrowLeft, "Back to the commands", onBack)
            Column(Modifier.weight(1f)) {
                Text("New command", style = MaterialTheme.typography.titleLarge, color = Herdr.colors.strong)
                Text("An agent writes it for you", style = MaterialTheme.typography.bodySmall, color = Herdr.colors.muted)
            }
        }
        Column(Modifier.padding(horizontal = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            com.makefunstuff.herdr.ui.design.SegmentedPills(listOf("Script", "AI prompt"), if (prompt) 1 else 0, { prompt = it == 1 })
            HerdrField(
                what, { what = it }, label = "What should it do?", modifier = Modifier.fillMaxWidth(), minLines = 3,
                placeholder = { Text(if (prompt) "Summarise what changed today in this repo" else "Run the tests and show only the failures") },
            )
            HerdrField(name, { name = it }, label = "Name (optional)", singleLine = true, modifier = Modifier.fillMaxWidth())
            if (!listing?.projectFolder.isNullOrEmpty()) {
                SwitchRow("Everywhere on this machine", if (global) short(folder, listing?.home.orEmpty()) else "Only in ${listing!!.repo.substringAfterLast('/')}: ${short(folder, listing.home)}", global) { global = it }
            }
            if (writers.isEmpty()) {
                Notice("No agent to ask here: open it from an agent's chat, or on a machine with the work board.", icon = HerdrIcons.Info)
            } else {
                Text("WHO WRITES IT", style = MaterialTheme.typography.labelSmall, color = Herdr.colors.muted, fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    writers.forEach { (id, label) -> com.makefunstuff.herdr.ui.design.HerdrChip(label, selected = writer == id, onClick = { writer = id }) }
                }
            }
            PrimaryButton(
                if (busy) "Sending…" else "Ask for it", modifier = Modifier.fillMaxWidth().padding(vertical = Space.s),
                enabled = !busy && what.isNotBlank() && writer != null && session != null,
            ) {
                val s = session ?: return@PrimaryButton
                val kind = if (prompt) QuickKind.Prompt else QuickKind.Script
                val text = com.makefunstuff.herdr.core.quick.QuickNew.message(kind, what, folder, name)
                busy = true
                scope.launch {
                    runCatching {
                        when (writer) {
                            "agent" -> {
                                val api = s.awaitApi()
                                val agent = api.snapshot().agents.firstOrNull { it.paneId == place.pane }
                                com.makefunstuff.herdr.core.chat.AgentInput.send(api, place.pane!!, text, named = agent != null,
                                    blocked = agent?.agentStatus == com.makefunstuff.herdr.core.api.AgentStatus.Blocked)
                                "Sent to ${place.agentName ?: "the agent"}"
                            }
                            "task" -> s.awaitBoard().act(com.makefunstuff.herdr.core.board.BoardAction.Tell, place.task!!, text).ifEmpty { "Sent to ${place.taskName}'s agent" }
                            else -> { s.awaitBoard().send(text); "Sent to the Coordinator" }
                        }
                    }.onSuccess { context.toast(it); onDismiss() }
                        .onFailure { context.toast(it.message ?: "Couldn't send it") }
                    busy = false
                }
            }
        }
    }
}
