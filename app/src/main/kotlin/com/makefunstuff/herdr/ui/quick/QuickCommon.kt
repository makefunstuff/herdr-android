package com.makefunstuff.herdr.ui.quick

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.core.quick.QuickArgs
import com.makefunstuff.herdr.core.quick.QuickClient
import com.makefunstuff.herdr.core.quick.QuickCommand
import com.makefunstuff.herdr.core.quick.QuickContext
import com.makefunstuff.herdr.core.quick.QuickKind
import com.makefunstuff.herdr.core.quick.QuickListing
import com.makefunstuff.herdr.core.quick.QuickPrompt
import com.makefunstuff.herdr.core.quick.QuickRun
import com.makefunstuff.herdr.core.quick.RunState
import com.makefunstuff.herdr.core.ssh.HerdrCommands.quote
import com.makefunstuff.herdr.data.quick
import com.makefunstuff.herdr.ui.design.HerdrIcons
import com.makefunstuff.herdr.ui.theme.Herdr

/**
 * Where the quick commands were opened: a machine, and the folder of its workspace, worktree, agent
 * or task (empty: the machine's commands alone). What else is known there lets a command go to the
 * right pane, and "New command" to the right agent.
 */
@Immutable
data class QuickPlace(
    val machine: String,
    val dir: String = "",
    /** What it is, under the sheet's title: a workspace's or an agent's name, a task's key. */
    val label: String = "",
    /** The workspace a "run in a new pane" opens its tab in; null opens a workspace of its own. */
    val workspaceId: String? = null,
    /** The agent's pane it was opened from, and its name. */
    val pane: String? = null,
    val agentName: String? = null,
    /** The board task it was opened from: its id and its key (`AND-9`). */
    val task: String? = null,
    val taskName: String? = null,
    /** The file open there and the lines chosen in it, for a prompt's `{{file}}` and `{{selection}}`. */
    val file: String = "",
    val selection: String = "",
)

/** A command's icon: the Lucide one its header names, else its kind's. */
fun QuickCommand.iconVector(): ImageVector = iconFor(icon, kind)

fun QuickRun.iconVector(): ImageVector = iconFor(icon, kind)

private fun iconFor(name: String, kind: QuickKind): ImageVector =
    name.takeIf { it.isNotBlank() }?.let { HerdrIcons.named(it) } ?: if (kind == QuickKind.Prompt) HerdrIcons.Sparkles else HerdrIcons.SquareTerminal

/** A run's state in words. */
fun QuickRun.stateLabel(): String = when (state) {
    RunState.Starting -> "Starting"
    RunState.Setup -> "Setting up"
    RunState.Running -> if (kind == QuickKind.Prompt) activity.ifEmpty { "Working" } else "Running"
    RunState.Done -> if (kind == QuickKind.Script) "Done" else "Answered"
    RunState.Failed -> if (exit != null) "Exit $exit" else "Failed"
    RunState.Stopped -> "Stopped"
    RunState.Limit -> "At its limit"
    RunState.Lost -> if (kind == QuickKind.Prompt) "Interrupted" else "Lost"
}

@Composable
fun RunState.color(): Color = Herdr.colors.let { c ->
    when (this) {
        RunState.Starting, RunState.Setup, RunState.Running -> c.working
        RunState.Done -> c.done
        RunState.Failed -> c.red
        RunState.Limit, RunState.Lost -> c.notice
        RunState.Stopped -> c.idle
    }
}

/** `45 s`, `3 min`, `1 h 5 min`. */
fun duration(seconds: Long): String = when {
    seconds < 60 -> "$seconds s"
    seconds < 3600 -> "${seconds / 60} min"
    else -> "${seconds / 3600} h" + (seconds % 3600 / 60).takeIf { it > 0 }?.let { " $it min" }.orEmpty()
}

/** `just now`, `5 min ago`, `3 h ago`, `2 d ago`: [at] and [now] on one clock, in seconds. */
fun runAgo(at: Long, now: Long): String {
    if (at <= 0 || now <= 0) return ""
    val s = maxOf(0, now - at)
    return when {
        s < 45 -> "just now"
        s < 3600 -> "${maxOf(1, s / 60)} min ago"
        s < 86400 -> "${s / 3600} h ago"
        else -> "${s / 86400} d ago"
    }
}

/** What a command runs with: the folder and its repository, what is chosen there, the clipboard. */
fun QuickPlace.context(listing: QuickListing?, clipboard: String, machineLabel: String, today: String): QuickContext = QuickContext(
    cwd = listing?.repo?.ifEmpty { null } ?: dir,
    repo = listing?.repo.orEmpty(),
    branch = listing?.branch.orEmpty(),
    selection = selection,
    file = file,
    clipboard = clipboard,
    machine = machineLabel,
    date = today,
)

/** Starts [command] on [session]'s machine; the new run's id. */
suspend fun HostSession.startQuick(command: QuickCommand, ctx: QuickContext, args: String, nowMs: Long = System.currentTimeMillis()): String {
    val id = QuickClient.newId(nowMs)
    val q = quick()
    if (command.kind == QuickKind.Prompt) q.ask(id, command, ctx, args, QuickPrompt.fill(command.body, args, ctx), nowMs)
    else q.start(id, command, ctx, args, nowMs)
    return id
}

/**
 * Runs the script [command] in a new herdr pane instead (a tab of [workspaceId], or a workspace of
 * its own): typed into its shell, in the command's folder. The pane's id, to open its terminal.
 */
suspend fun HostSession.startQuickInPane(command: QuickCommand, ctx: QuickContext, args: String, workspaceId: String?): String {
    val api = awaitApi()
    val env = mapOf("HERDR_REPO" to ctx.repo, "HERDR_CWD" to ctx.cwd, "HERDR_ARGS" to args, "HERDR_COMMAND" to command.path)
    val pane = api.openShell(workspaceId, command.name, ctx.cwd.ifEmpty { null }, env)
    api.sendText(pane, (listOf(command.path) + QuickArgs.split(args)).joinToString(" ") { quote(it) })
    api.sendKeys(pane, listOf("enter"))
    refresh()
    return pane
}

/** Runs [run] again as it was: its command as the folder has it now, with the same arguments. */
suspend fun HostSession.rerunQuick(run: QuickRun, clipboard: String, machineLabel: String, today: String): String {
    val listing = quick().list(run.cwd)
    val command = listing.commands.firstOrNull { it.path == run.path }
        ?: throw java.io.IOException("${run.name} isn't in its folder any more")
    val ctx = QuickPlace(machine.id, run.cwd).context(listing, clipboard, machineLabel, today).copy(cwd = run.cwd.ifEmpty { listing.repo })
    return startQuick(command, ctx, run.args)
}
