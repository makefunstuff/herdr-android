package com.makefunstuff.herdr.push

import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.board.BoardTask
import com.makefunstuff.herdr.core.push.Alert
import com.makefunstuff.herdr.core.push.AlertChange
import com.makefunstuff.herdr.core.push.PushEvent
import com.makefunstuff.herdr.core.push.Watch
import com.makefunstuff.herdr.core.push.WatchFired
import com.makefunstuff.herdr.core.push.Watches
import com.makefunstuff.herdr.data.BoardLists
import com.makefunstuff.herdr.data.HostManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * One-shot watches ([Watch]): set from a long-press sheet, kept with the notification settings, and
 * fired once, as a notification, by what a push says, what the app's own look sees, or what the open
 * app reads of its machines. A fired one is removed (and the machine's herdr-push stops following it
 * at the next connect).
 */
class WatchWork(
    private val store: PushStore,
    private val inbox: Inbox,
    private val quiet: QuietWork,
) {
    private val _fired = MutableSharedFlow<Alert>(extraBufferCapacity = 8)
    /** Watches that fired, as their notifications: the open app shows them as its in-app alert too. */
    val fired: SharedFlow<Alert> = _fired.asSharedFlow()

    fun start(scope: CoroutineScope, hosts: HostManager, boards: BoardLists) {
        // While the app runs it reads its machines itself: their agents, and the boards it has read.
        scope.launch {
            hosts.hosts.collect { states ->
                if (store.state.value.watches.isEmpty()) return@collect
                for (s in states) {
                    // Both classic herdr's agents and herdr-core's, or a watched one would seem gone.
                    val snap = s.view?.takeIf { s.agentsKnown } ?: continue
                    if (s.online) look(s.machine.id, s.machine.label, snap.agents, null)
                }
            }
        }
        scope.launch {
            boards.lists.map { lists -> lists.mapValues { it.value.tasks } }.distinctUntilChanged().collect { lists ->
                if (store.state.value.watches.isEmpty()) return@collect
                for ((id, tasks) in lists) {
                    val label = hosts.hosts.value.firstOrNull { it.machine.id == id }?.machine?.label ?: continue
                    look(id, label, null, tasks)
                }
            }
        }
    }

    fun has(id: String): Boolean = store.state.value.watches.any { it.id == id }

    /** Sets [w], replacing one on the same thing and question. */
    fun add(w: Watch) = store.change { s -> s.copy(watches = s.watches.filter { it.id != w.id } + w) }

    fun remove(id: String) = store.change { s -> s.copy(watches = s.watches.filter { it.id != id }) }

    /** A push from [machineId]. */
    fun events(machineId: String, label: String, events: List<PushEvent>) =
        fire(Watches.events(store.state.value.watches, machineId, label, events))

    /** A look at [machineId]: its agents (null: not looked at) and its board's tasks (null: not read). */
    fun look(machineId: String, label: String, agents: List<AgentInfo>?, tasks: List<BoardTask>?) =
        fire(Watches.look(store.state.value.watches, machineId, label, agents, tasks))

    private fun fire(fired: List<WatchFired>) {
        if (fired.isEmpty()) return
        val ids = fired.map { it.watch.id }.toSet()
        // Removed first: two reads at once (a push and the screen) fire it once.
        var mine = emptySet<String>()
        store.change { s ->
            mine = s.watches.map { it.id }.filter { it in ids }.toSet()
            s.copy(watches = s.watches.filter { it.id !in ids })
        }
        val shows = fired.filter { it.watch.id in mine }.mapNotNull { f -> f.alert?.let { AlertChange.Show(it) } }
        if (shows.isNotEmpty()) inbox.apply(shows, quiet = quiet.silent(), hold = quiet.check(), rules = store.state.value.rules)
        shows.forEach { _fired.tryEmit(it.alert) }
    }
}
