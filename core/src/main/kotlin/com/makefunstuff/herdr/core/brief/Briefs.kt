package com.makefunstuff.herdr.core.brief

import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.api.SessionSnapshot
import com.makefunstuff.herdr.core.board.BoardStatus
import com.makefunstuff.herdr.core.board.BoardTask
import com.makefunstuff.herdr.core.push.LiveItems

/** What the brief got from one machine. */
data class MachineLook(
    val id: String,
    val label: String,
    /** Its board's tasks now; null where it has no board, or the board didn't answer ([boardFailed]). */
    val tasks: List<BoardTask>? = null,
    /** It has the work board, but `list` didn't answer. */
    val boardFailed: Boolean = false,
    /** Its agents now; null when herdr didn't answer. */
    val snapshot: SessionSnapshot? = null,
    /** What a blocked agent asks, by pane, read from its screen. */
    val questions: Map<String, String> = emptyMap(),
    /** herdr-push's journal there; null where it keeps none. */
    val journal: Journal? = null,
    /** What the app showed of it when the person last looked; used where there is no journal. */
    val baseline: Baseline? = null,
    /** It couldn't be reached: why. */
    val problem: String? = null,
)

/**
 * Puts the brief together: what each machine's journal says happened in the window (or, without one,
 * what changed since the app last showed it), and how its tasks and agents stand now.
 *
 * Each task shows once, where it tells the most: went wrong (and still is), waits for you, done in
 * the window, at work. A board task's own agents (its worker, its reviewer) are the task, and the
 * Coordinator's replies are its chat, so neither is listed as an agent. Another machine's tasks on a
 * board are left to that machine.
 */
object Briefs {
    /** What went wrong: these need you, but are told apart from the rest that waits. */
    val FAILED = setOf(BoardStatus.Error, BoardStatus.ReviewFailed, BoardStatus.Stopped)

    /** A journal whose last look is older than this stopped keeping track (herdr-push isn't running). */
    const val STALE_MS = 10L * 60 * 1000

    fun build(looks: List<MachineLook>, since: Long, now: Long): Brief {
        val parts = looks.map { one(it, since, now) }
        fun order(items: List<BriefItem>) = items.sortedWith(compareByDescending<BriefItem> { it.new }.thenByDescending { it.at })
        return Brief(
            at = now,
            since = since,
            machines = parts.map { it.machine },
            failed = order(parts.flatMap { it.failed }),
            waiting = order(parts.flatMap { it.waiting }),
            done = parts.flatMap { it.done }.sortedByDescending { it.at },
            finished = parts.flatMap { it.finished }.sortedByDescending { it.at },
            working = parts.flatMap { it.working },
            started = parts.sumOf { it.started },
            added = parts.sumOf { it.added },
        )
    }

    private class Part(
        val machine: BriefMachine,
        val failed: List<BriefItem> = emptyList(),
        val waiting: List<BriefItem> = emptyList(),
        val done: List<BriefItem> = emptyList(),
        val finished: List<BriefItem> = emptyList(),
        val working: List<BriefItem> = emptyList(),
        val started: Int = 0,
        val added: Int = 0,
    )

    private fun one(m: MachineLook, since: Long, now: Long): Part {
        if (m.problem != null) return Part(BriefMachine(m.id, m.label, BriefMachine.Coverage.Unreached, m.problem))
        val j = m.journal
        // The machine's clock on the phone's: the journal says what its clock read as it answered.
        val offset = if (j != null && j.now > 0) now - j.now * 1000 else 0L
        fun phone(at: Long) = at * 1000 + offset
        val window = j?.entries.orEmpty().filter { phone(it.at) >= since }
        val base = if (j == null) m.baseline else null

        val missing = listOfNotNull(
            if (m.boardFailed) "its board didn't answer" else null,
            if (m.snapshot == null) "herdr didn't answer" else null,
        ).joinToString(" and ").ifEmpty { null }?.replaceFirstChar { it.uppercase() }
        val machine = when {
            j != null -> {
                val from = phone(j.from ?: 0)
                val upto = j.upto?.let(::phone) ?: now
                val live = upto >= now - STALE_MS
                if (from <= since && live) BriefMachine(m.id, m.label, BriefMachine.Coverage.Journal, missing)
                else BriefMachine(m.id, m.label, BriefMachine.Coverage.Partial, missing, from = if (from > since) from else 0, upto = if (live) 0 else upto)
            }
            base?.tasks != null || base?.agents != null ->
                BriefMachine(m.id, m.label, BriefMachine.Coverage.Compared, missing, upto = maxOf(base.tasksAt, base.agentsAt))
            else -> BriefMachine(m.id, m.label, BriefMachine.Coverage.NowOnly, missing)
        }

        // This machine's own tasks: another machine's are that machine's to tell.
        val tasks = m.tasks.orEmpty().filter { it.machine.isEmpty() }
        val taskEntries = window.filterIsInstance<JournalEntry.Task>().filter { it.task.machine.isEmpty() }
        val lastEntry = taskEntries.associateBy { it.task.id }
        val snapshot = m.snapshot

        /** Whether [t]'s state now came about in the window, and when. */
        fun happened(t: BoardTask): Pair<Boolean, Long> = when {
            j != null -> lastEntry[t.id]?.takeIf { it.task.status == t.status }?.let { true to phone(it.at) } ?: (false to 0L)
            base?.tasks != null -> (base.tasks[t.id] != t.status.name) to 0L
            // Never seen before: all of it is news.
            else -> true to 0L
        }

        fun item(kind: BriefItem.Kind, t: BoardTask, status: String = t.status.label, at: Pair<Boolean, Long> = happened(t)) = BriefItem(
            kind = kind,
            machineId = m.id,
            machineLabel = m.label,
            title = t.title,
            name = t.name,
            status = status,
            detail = t.msg,
            at = at.second,
            new = at.first,
            taskId = t.id,
            pr = t.pr,
            board = t.status.name,
            progress = t.progress?.takeIf { kind == BriefItem.Kind.Working },
        )

        // Agents: whose they are, and what to call them.
        val ownerTasks = tasks + taskEntries.map { it.task }
        // The board names them after the task and titles them with it; an agent on the same work as a task is the task too.
        fun owned(a: AgentInfo, workspace: String) = ownerTasks.any {
            LiveItems.belongs(a.name.orEmpty(), workspace, it.id, it.name) || (a.title != null && a.title == it.title)
        }
        fun coordinator(a: AgentInfo) = LiveItems.isCoordinator(a.name.orEmpty())
        fun workspaceOf(a: AgentInfo) = snapshot?.workspace(a.workspaceId)?.label.orEmpty()
        fun agentItem(kind: BriefItem.Kind, a: AgentInfo, status: String, detail: String, at: Pair<Boolean, Long>) = BriefItem(
            kind = kind,
            machineId = m.id,
            machineLabel = m.label,
            title = if (coordinator(a)) "Coordinator" else a.subtitle ?: a.displayName,
            name = if (coordinator(a)) "Coordinator" else a.displayName,
            status = status,
            detail = detail,
            at = at.second,
            new = at.first,
            paneId = a.paneId,
        )
        val agentEntries = window.filterIsInstance<JournalEntry.Agent>()
        fun agentHappened(a: AgentInfo): Pair<Boolean, Long> = when {
            j != null -> agentEntries.lastOrNull { it.agent.paneId == a.paneId && Baseline.mark(it.agent) == Baseline.mark(a) }
                ?.let { true to phone(it.at) } ?: (false to 0L)
            base?.agents != null -> (base.agents[a.paneId] != Baseline.mark(a)) to 0L
            else -> true to 0L
        }

        val failed = tasks.filter { it.needsYou && it.status in FAILED }.map { item(BriefItem.Kind.Failed, it) }
        val asking = snapshot?.agents.orEmpty().filter { it.agentStatus == AgentStatus.Blocked && !owned(it, workspaceOf(it)) }
        val waiting = tasks.filter { it.needsYou && it.status !in FAILED }.map { item(BriefItem.Kind.Waiting, it) } +
            asking.map { a -> agentItem(BriefItem.Kind.Waiting, a, "asks you", m.questions[a.paneId].orEmpty(), agentHappened(a)) }

        val doneNow = tasks.filter { it.status == BoardStatus.Done }.associateBy { it.id }
        val done: List<Pair<BoardTask, Long>> = when {
            j != null -> taskEntries.filter { it.task.status == BoardStatus.Done }
                .associateBy { it.task.id }.values
                // Reopened since: it isn't done any more. One gone from the list was archived or cleared, and stays done.
                .filter { e -> m.tasks == null || e.task.id in doneNow || tasks.none { it.id == e.task.id } }
                .map { (doneNow[it.task.id] ?: it.task) to phone(it.at) }
            base?.tasks != null -> doneNow.values.filter { base.tasks[it.id] != BoardStatus.Done.name && base.tasks[it.id] != BoardStatus.Archived.name }.map { it to 0L }
            else -> emptyList()
        }
        val doneItems = done.map { (t, at) ->
            val kind = when {
                t.research -> BriefItem.Kind.Report
                t.pr.isNotEmpty() -> BriefItem.Kind.Merged
                else -> BriefItem.Kind.Done
            }
            val words = when (kind) {
                BriefItem.Kind.Report -> "report ready"
                BriefItem.Kind.Merged -> "merged"
                else -> "done"
            }
            item(kind, t, words, true to at)
        }

        val asks = asking.map { it.paneId }.toSet()
        val finished: List<BriefItem> = when {
            j != null -> agentEntries.filter { it.agent.agentStatus == AgentStatus.Done }
                .associateBy { it.agent.paneId }.values
                .filter { !coordinator(it.agent) && !owned(it.agent, it.workspace) && it.agent.paneId !in asks }
                // What it worked on as it finished; it may be on something else now.
                .map { e -> agentItem(BriefItem.Kind.Finished, e.agent, "finished", e.where, true to phone(e.at)) }
            base?.agents != null -> snapshot?.agents.orEmpty()
                .filter { it.agentStatus == AgentStatus.Done && base.agents[it.paneId] != Baseline.mark(it) }
                .filter { !coordinator(it) && !owned(it, workspaceOf(it)) }
                .map { agentItem(BriefItem.Kind.Finished, it, "finished", snapshot!!.location(it.workspaceId, it.tabId), true to 0L) }
            else -> emptyList()
        }

        val running = tasks.filter(LiveItems::running)
        val working = running.map { item(BriefItem.Kind.Working, it, it.status.label, false to 0L) } +
            snapshot?.agents.orEmpty()
                .filter { it.agentStatus == AgentStatus.Working && !coordinator(it) && !owned(it, workspaceOf(it)) }
                .map { agentItem(BriefItem.Kind.Working, it, "working", snapshot!!.location(it.workspaceId, it.tabId), false to 0L) }

        val waitedBefore = setOf("new", "todo", "queued")
        val (started, added) = when {
            j != null -> taskEntries.filter { it.was in waitedBefore && (it.task.status == BoardStatus.Working || it.task.status == BoardStatus.Reviewing) }
                .map { it.task.id }.distinct().size to taskEntries.filter { it.was == "new" }.map { it.task.id }.distinct().size
            base?.tasks != null -> running.count { base.tasks[it.id] == null || base.tasks[it.id] == BoardStatus.Todo.name || base.tasks[it.id] == BoardStatus.Queued.name } to
                tasks.count { base.tasks[it.id] == null }
            else -> 0 to 0
        }
        return Part(machine, failed, waiting, doneItems, finished, working, started, added)
    }
}
