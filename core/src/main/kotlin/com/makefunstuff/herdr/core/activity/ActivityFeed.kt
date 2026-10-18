package com.makefunstuff.herdr.core.activity

import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.core.push.LiveItems
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * The timeline of a board task, or of one agent, kept live while its screen is open: the board's
 * record of the task (its events, and every session its agents had) and those sessions, read over
 * the machine's SSH connection; then the session written to last followed as it grows, and the
 * record looked at again now and then for new events and sessions (a review round starting).
 *
 * An agent's timeline is its task's when it works on one (its worker, its reviewer), else its own
 * session's alone.
 */
class ActivityFeed(private val host: HostSession, private val target: Target, private val scope: CoroutineScope) {

    sealed interface Target {
        data class Task(val taskId: String) : Target
        data class Agent(val paneId: String) : Target
    }

    data class State(
        val loading: Boolean = true,
        val timeline: Timeline = Timeline.Empty,
        /** A session is followed now: new steps appear as the agent takes them. */
        val live: Boolean = false,
        /** Its agent is at work now (in the middle of a turn), as the board or herdr says. */
        val working: Boolean = false,
        val problem: String? = null,
        /** The board task it is, when it is one (for an agent, the one it works on). */
        val taskId: String? = null,
        val title: String = "",
        /** Nothing to read: no session the app can read, and no board record. */
        val empty: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }

    fun close() {
        job?.cancel()
        job = null
    }

    /** Reads it all again now (a pull to refresh). */
    fun reload() {
        close()
        _state.update { it.copy(loading = true, problem = null) }
        start()
    }

    private fun agent(): AgentInfo? = (target as? Target.Agent)?.let { t -> host.state.value.snapshot?.agents?.firstOrNull { it.paneId == t.paneId } }

    private suspend fun run() {
        var failures = 0
        var taskId = (target as? Target.Task)?.taskId
        var looked = false
        while (true) {
            try {
                val conn = host.awaitConnection(timeoutMs = 60_000)
                if (taskId == null && !looked && target is Target.Agent) {
                    taskId = findTask()
                    looked = true
                }
                val record = taskId?.let { readRecord(it) }
                val own = agent()?.let { a ->
                    a.agentSession?.takeIf { it.value.isNotEmpty() }?.let { ref ->
                        SessionSource(ref.agent.ifEmpty { a.agent.orEmpty() }, ref.kind.ifEmpty { "path" }, ref.value)
                    }
                }
                // The agent's own session, as herdr reports it, stands for the board's copy of it.
                val sources = record?.sessions.orEmpty().filterNot { s -> own != null && (s.value == own.value || s.value.contains(own.value) || own.value.contains(s.value)) } +
                    listOfNotNull(own?.copy(role = record?.sessions?.firstOrNull { s -> s.value.contains(own.value) || own.value.contains(s.value) }?.role))
                val readable = sources.filter { ActivityLog.forAgent(it.agent) != null }
                val follow = own != null || record?.open == true
                _state.update { it.copy(taskId = taskId, title = record?.title ?: it.title, working = working(record)) }
                if (readable.isEmpty()) {
                    _state.update {
                        it.copy(loading = false, live = false, timeline = Timeline.of(emptyList(), record?.events.orEmpty()), empty = record == null, problem = null)
                    }
                    if (!follow) return
                    delay(15_000)
                    continue
                }
                follow(conn, readable, follow, record, taskId)
                failures = 0
                if (!follow) return
            } catch (e: CancellationException) {
                throw e
            } catch (_: Restart) {
                continue
            } catch (t: Throwable) {
                failures++
                _state.update { it.copy(loading = false, live = false, problem = t.message ?: "Couldn't read the activity") }
            }
            delay(minOf(30_000L, 1_000L shl minOf(failures, 5)))
        }
    }

    private class Restart : Exception()

    private fun working(record: TaskRecord?): Boolean =
        agent()?.agentStatus == com.makefunstuff.herdr.core.api.AgentStatus.Working || record?.working == true

    /** Reads [sources] (and follows the newest), with the board's [record] events beside them; looks at the record again while live. */
    private suspend fun follow(
        conn: com.makefunstuff.herdr.core.ssh.MachineConnection,
        sources: List<SessionSource>,
        follow: Boolean,
        record: TaskRecord?,
        taskId: String?,
    ) = coroutineScope {
        val reader = SessionsReader(sources)
        var events = record?.events.orEmpty()
        fun publish() {
            val timeline = Timeline.of(reader.turns(), events, reader.truncated, reader.missing)
            _state.update { it.copy(loading = false, timeline = timeline, live = follow && reader.live != null, problem = null, empty = false) }
        }
        val watcher = if (follow && taskId != null && record != null) launch {
            while (true) {
                delay(20_000)
                val next = runCatching { readRecord(taskId) }.getOrNull() ?: continue
                if (next.sessions.map { it.key } != record.sessions.map { it.key }) throw Restart()
                if (next.events != events) {
                    events = next.events
                    publish()
                }
                _state.update { it.copy(working = working(next)) }
                if (!next.open && record.open) throw Restart()
            }
        } else null
        // An agent that starts a new session (after /clear, say) is read again from it.
        val moved = (target as? Target.Agent)?.let {
            launch {
                host.state.map { agent()?.agentSession?.value }.distinctUntilChanged().drop(1).first()
                throw Restart()
            }
        }
        try {
            SessionsReader.stream({ conn.start(it) }, ActivityCommands.sessions(sources, follow, bytes(sources.size)), reader) { publish() }
        } finally {
            watcher?.cancel()
            moved?.cancel()
        }
        _state.update { it.copy(live = false) }
    }

    /** Each session's share of [ActivityCommands.TOTAL_BYTES], at most [ActivityCommands.SESSION_BYTES]. */
    private fun bytes(sessions: Int): Int = minOf(ActivityCommands.SESSION_BYTES, ActivityCommands.TOTAL_BYTES / maxOf(1, sessions)).coerceAtLeast(300_000)

    private suspend fun readRecord(taskId: String): TaskRecord? {
        // Right after connecting, the look for the board may not have answered yet.
        if (!host.state.value.boardChecked) withTimeoutOrNull(15_000) { host.state.first { it.boardChecked } }
        val path = host.state.value.boardPath ?: return null
        val conn = host.awaitConnection()
        val r = conn.run(ActivityCommands.record(host.state.value.herdrMachine, path, taskId), timeoutSeconds = 30)
        if (!r.stdout.contains(ActivityCommands.MARK)) throw IOException(r.stderr.trim().lines().lastOrNull { it.isNotBlank() } ?: "The machine didn't answer")
        return RecordReader.parse(r.stdout)
    }

    /** The board task the agent works on: by the names the board gives its agents and the workspace it opens for the task. */
    private suspend fun findTask(): String? {
        val a = agent() ?: return null
        if (!host.state.value.hasBoard) return null
        val workspace = host.state.value.snapshot?.workspaces?.firstOrNull { it.workspaceId == a.workspaceId }?.label.orEmpty()
        val name = a.name.orEmpty()
        if (name.isEmpty() && workspace.isEmpty()) return null
        val tasks = runCatching { host.awaitBoard().tasks() }.getOrNull() ?: return null
        return tasks.firstOrNull { it.machine.isEmpty() && LiveItems.belongs(name, workspace, it.id, it.name) }?.id
    }
}
