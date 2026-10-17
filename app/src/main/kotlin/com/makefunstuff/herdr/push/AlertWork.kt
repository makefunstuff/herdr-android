package com.makefunstuff.herdr.push

import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.board.BoardAction
import com.makefunstuff.herdr.core.chat.AgentInput
import com.makefunstuff.herdr.core.chat.BlockedPrompt
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.core.push.Alert
import com.makefunstuff.herdr.core.push.AlertAction
import com.makefunstuff.herdr.core.push.Alerts
import com.makefunstuff.herdr.core.push.PushLook
import com.makefunstuff.herdr.core.push.PushMessages
import com.makefunstuff.herdr.data.HostManager
import com.makefunstuff.herdr.data.MachineStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.IOException

/**
 * The work behind notifications: what a push says, what a button does (over the machine's SSH
 * connection, made for it while the app is closed), and the app's own look at its machines.
 */
class AlertWork(
    private val hosts: HostManager,
    private val machines: MachineStore,
    private val store: PushStore,
    private val inbox: Inbox,
    private val live: LiveWork,
) {
    /** A push from a push app: opened with the machine's key and shown. False when it isn't one of ours. */
    fun received(text: String, now: Long = System.currentTimeMillis()): Boolean {
        val (machineId, msg) = PushMessages.open(text, store.keys().filterKeys { machines.get(it) != null }) ?: return false
        val label = machines.get(machineId)?.label ?: return false
        // Days old, or older than one already taken (sent again, or overtaken by a newer state): ours, but not shown.
        val last = store.state.value.machine(machineId).lastAt
        if (!PushMessages.fresh(msg.at, last, now / 1000)) return true
        if (msg.at > last) store.machine(machineId) { it.copy(lastAt = msg.at) }
        val changes = Alerts.all(machineId, label, msg.events, msg.host)
        msg.events.filterIsInstance<com.makefunstuff.herdr.core.push.PushEvent.Live>().forEach { live.received(machineId, label, it, msg.at, now) }
        if (msg.events.any { it is com.makefunstuff.herdr.core.push.PushEvent.Test }) {
            store.machine(machineId) { it.copy(testedAt = now, problem = null) }
        }
        inbox.apply(changes, quiet = hosts.isForeground)
        return true
    }

    /** Carries out [action] of [alert] ([text]: what was typed in its reply box), with the outcome on the notification. */
    suspend fun perform(alert: Alert, action: AlertAction, text: String?) {
        if (action.reply && text.isNullOrBlank()) return inbox.show(alert, quiet = true, note = null)
        inbox.working(alert, if (action.reply) "Sending…" else "${action.label}…")
        try {
            val said = withTimeout(90_000) { hosts.borrow(alert.machineId) { s -> act(s, alert, action, text?.trim().orEmpty()) } }
            inbox.finished(alert, said)
        } catch (e: CancellationException) {
            inbox.show(alert, quiet = true, note = "Took too long. Try again, or open the app.")
            if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e
        } catch (t: Throwable) {
            inbox.show(alert, quiet = true, note = t.message ?: "Couldn't do that")
        }
    }

    private suspend fun act(s: HostSession, alert: Alert, action: AlertAction, text: String): String = when (action.type) {
        AlertAction.Type.Choose -> {
            val api = s.awaitApi()
            val agent = api.snapshot().agents.firstOrNull { it.paneId == alert.paneId }
            if (agent?.agentStatus != AgentStatus.Blocked) throw IOException("It isn't waiting any more.")
            // Only press the option the notification showed, on the question it showed.
            val prompt = BlockedPrompt.parse(api.readPane(alert.paneId, source = "visible", lines = 80).text)
            if (prompt == null || prompt.question != action.question || prompt.options.getOrNull(action.option)?.label != action.optionLabel) {
                throw IOException("The question changed. Open the chat to answer it.")
            }
            AgentInput.keys(api, alert.paneId, prompt.keysFor(action.option), named = true)
            s.refresh()
            "Answered: ${action.optionLabel}"
        }
        AlertAction.Type.ReplyAgent -> {
            val api = s.awaitApi()
            val agent = api.snapshot().agents.firstOrNull { it.paneId == alert.paneId } ?: throw IOException("That agent is gone.")
            AgentInput.send(api, alert.paneId, text, named = true, blocked = agent.agentStatus == AgentStatus.Blocked)
            s.refresh()
            "Sent: $text"
        }
        AlertAction.Type.Board -> {
            val board = board(s)
            val a = action.boardAction ?: throw IOException("Unknown board command")
            board.act(a, alert.taskId).ifEmpty { "${action.label}: done" }
        }
        AlertAction.Type.TellTask -> board(s).act(BoardAction.Tell, alert.taskId, text).ifEmpty { "Sent: $text" }
        AlertAction.Type.AskCoordinator -> {
            board(s).send(Alerts.aboutTask(alert.taskName.ifEmpty { alert.taskId }, alert.text, text))
            "Sent to the Coordinator"
        }
    }

    private suspend fun board(s: HostSession) = run {
        s.awaitConnection()
        if (!s.state.value.hasBoard) s.checkBoard()
        s.awaitBoard()
    }

    /** The app's own look at every machine (an alarm while the app is closed): what needs you there now. */
    suspend fun checkAll() {
        val st = store.state.value
        if (!st.on) return
        coroutineScope {
            for (m in machines.machines.value) {
                if (!m.enabled || st.machine(m.id).off) continue
                launch {
                    try {
                        withTimeout(60_000) { check(m.id) }
                    } catch (e: CancellationException) {
                        if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                    } catch (_: Throwable) {
                        // Unreachable now: the next look, or the machine's own pushes, catch up.
                    }
                }
            }
        }
    }

    private suspend fun check(machineId: String) = hosts.borrow(machineId) { s ->
        val api = s.awaitApi(30_000)
        s.checkBoard()
        val board = if (s.state.value.hasBoard) s.awaitBoard() else null
        val snapshot = api.snapshot()
        val tasks = board?.let { PushLook.tasks(it) }
        val events = PushLook.events(api, snapshot, tasks)
        inbox.apply(Alerts.all(machineId, s.machine.label, events), quiet = hosts.isForeground)
        live.looked(machineId, s.machine.label, snapshot, tasks)
    }

    /** As the app leaves the screen: what its machines show now was seen, so a later look stays quiet about it. */
    fun leaving() {
        val changes = hosts.hosts.value.flatMap { h ->
            val snap = h.snapshot ?: return@flatMap emptyList()
            if (!h.online) return@flatMap emptyList()
            snap.agents.map { a ->
                Alerts.of(h.machine.id, h.machine.label, com.makefunstuff.herdr.core.push.PushEvent.Agent(a, snap.location(a.workspaceId, a.tabId)))
            }
        }
        if (changes.isNotEmpty()) inbox.markSeen(changes)
    }
}
