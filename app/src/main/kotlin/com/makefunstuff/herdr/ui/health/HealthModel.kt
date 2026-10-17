package com.makefunstuff.herdr.ui.health

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.makefunstuff.herdr.core.api.WorktreeErrors
import com.makefunstuff.herdr.core.api.plainMessage
import com.makefunstuff.herdr.core.board.BoardFilter
import com.makefunstuff.herdr.core.board.BoardTask
import com.makefunstuff.herdr.core.health.HealthClient
import com.makefunstuff.herdr.core.health.HealthReader
import com.makefunstuff.herdr.core.health.HealthReport
import com.makefunstuff.herdr.core.health.HealthSample
import com.makefunstuff.herdr.core.health.LabeledProc
import com.makefunstuff.herdr.core.health.Leftover
import com.makefunstuff.herdr.core.health.ProcLabels
import com.makefunstuff.herdr.core.health.WorktreeDir
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.data.HealthStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A machine's health screen: what it reads (every few seconds while the screen is in front), who
 * each process belongs to (the board's tasks, herdr's panes), and what it stops or removes.
 */
class HealthModel(private val session: HostSession, private val store: HealthStore, private val scope: CoroutineScope) {
    var sample by mutableStateOf<HealthSample?>(null)
        private set
    /** Why the last look failed, while it does. */
    var problem by mutableStateOf<String?>(null)
        private set
    /** The board's tasks, with the archived ones; null where the machine has no board (or it wasn't read yet). */
    var tasks by mutableStateOf<List<BoardTask>?>(null)
        private set
    /** The worktree folders on disk; null until counted (that takes a while). */
    var worktrees by mutableStateOf<List<WorktreeDir>?>(null)
        private set
    var procs by mutableStateOf<List<LabeledProc>>(emptyList())
        private set
    var leftovers by mutableStateOf<List<Leftover>>(emptyList())
        private set

    /** What is being done now (shown on the action), or null. */
    var busy by mutableStateOf<String?>(null)
        private set

    /** One-off messages: results and refusals. */
    var message by mutableStateOf<String?>(null)

    private var tasksAt = 0L

    private suspend fun client(): HealthClient {
        val conn = session.awaitConnection()
        return HealthClient { cmd, timeout -> conn.run(cmd, timeout) }
    }

    /** One look; the board's tasks every half minute (or [tasksToo]). */
    suspend fun look(tasksToo: Boolean = false) {
        try {
            val c = client()
            val id = session.machine.id
            // The machine's clock, not the phone's: the hour ends at its newest reading.
            if (store.needsHostHistory(id)) launch { c.history().let { h -> h.maxOfOrNull { it.at }?.let { store.addHistory(id, h, it) } } }
            if (tasksToo || System.currentTimeMillis() - tasksAt > 30_000) readTasks()
            val s = c.sample(ProcLabels.keep(tasks))
            store.add(id, s)
            sample = s
            problem = null
            relabel()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            problem = t.plainMessage()
        }
    }

    private fun launch(block: suspend () -> Unit) = scope.launch { runCatching { block() } }

    private suspend fun readTasks() {
        session.checkBoard()
        if (!session.state.value.hasBoard) {
            tasks = null
            return
        }
        tasks = runCatching {
            val board = session.awaitBoard()
            board.tasks() + runCatching { board.tasks(BoardFilter(archived = true)) }.getOrDefault(emptyList())
        }.getOrElse { tasks }
        tasksAt = System.currentTimeMillis()
    }

    fun loadWorktrees() = scope.launch {
        runCatching { client().worktrees() }.onSuccess {
            worktrees = it
            relabel()
        }
    }

    private fun relabel() {
        val s = sample ?: return
        val state = session.state.value
        procs = ProcLabels.label(s, tasks, state.snapshot, state.machine.session)
        leftovers = ProcLabels.leftovers(procs, tasks, worktrees, state.snapshot)
    }

    /** What is low now, with what uses the most; null when nothing is. */
    val report: HealthReport? get() = sample?.let { s -> HealthReport.of(s, procs)?.takeIf { it.low.isNotEmpty() } }

    val gradle: List<LabeledProc> get() = procs.filter { it.own && it.kind.gradle }

    /** Stops [targets] (SIGTERM, then SIGKILL after 5 s), [what] naming them in the outcome. */
    fun stop(targets: List<LabeledProc>, what: String) = act("Stopping $what…") {
        val k = client().kill(ProcLabels.targets(targets))
        look()
        outcome(k, what)
    }

    /** Every leftover process of the finished tasks. */
    fun stopLeftovers() {
        val all = leftovers.flatMap { it.procs }
        val tasks = leftovers.count { it.procs.isNotEmpty() }
        stop(all, if (tasks == 1) "the leftovers of ${leftovers.first { it.procs.isNotEmpty() }.taskName}" else "the leftovers of $tasks tasks")
    }

    fun stopGradle() = stop(gradle, if (gradle.size == 1) "the Gradle daemon" else "${gradle.size} Gradle daemons")

    /**
     * Removes a finished task's worktree, also with changes in it when it [WorktreeDir.dirty] has them
     * (the dialog said so): through herdr when a workspace is open on it (that closes it too), else with git.
     */
    fun removeWorktree(l: Leftover) = act("Removing ${l.worktree?.name}…") {
        val dir = l.worktree ?: return@act "Already gone"
        val ws = l.workspace
        try {
            if (ws != null) {
                session.awaitApi().removeWorktree(ws.workspaceId, force = dir.dirty)
                session.refresh()
            } else {
                client().removeWorktree(dir.path, dir.dirty)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            throw java.io.IOException(WorktreeErrors.describe(t), t)
        }
        worktrees = worktrees?.filterNot { it.path == dir.path }
        relabel()
        "Removed ${dir.name}"
    }

    private fun outcome(k: HealthReader.Killed, what: String): String {
        val stopped = k.stopped.size
        return when {
            stopped == 0 && k.failed.isNotEmpty() -> "Couldn't stop $what: not allowed"
            stopped == 0 -> "Nothing to stop: $what had already ended"
            k.failed.isNotEmpty() -> "Stopped $stopped; ${k.failed.size} couldn't be stopped"
            k.forced.isNotEmpty() -> "Stopped $what (${k.forced.size} had to be killed)"
            else -> "Stopped $what"
        }
    }

    private fun act(words: String, block: suspend () -> String) {
        if (busy != null) return
        busy = words
        scope.launch {
            try {
                message = block()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                message = t.plainMessage()
            } finally {
                busy = null
            }
        }
    }
}
