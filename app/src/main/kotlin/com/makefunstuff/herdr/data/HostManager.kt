package com.makefunstuff.herdr.data

import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.core.host.HostState
import com.makefunstuff.herdr.core.ssh.Machine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** An agent together with the machine it runs on. */
data class AgentRow(val machine: Machine, val agent: AgentInfo, val location: String)

/** Owns one [HostSession] per saved machine and connects them while the app is in use. */
@OptIn(ExperimentalCoroutinesApi::class)
class HostManager(
    private val scope: CoroutineScope,
    private val machines: MachineStore,
    private val identity: IdentityStore,
) {
    private val sessions = MutableStateFlow<Map<String, HostSession>>(emptyMap())
    @Volatile private var foreground = false
    /** Machines connected for work while the app is closed (a notification's button, a look), with how many uses. */
    private val borrowed = HashMap<String, Int>()

    /** Whether the app is on screen. */
    val isForeground: Boolean get() = foreground

    /** Live state of every machine, in saved order. */
    val hosts: StateFlow<List<HostState>> = combine(sessions, machines.machines) { map, list ->
        list.mapNotNull { map[it.id] }
    }.flatMapLatest { list ->
        if (list.isEmpty()) flowOf(emptyList()) else combine(list.map { it.state }) { it.toList() }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Every agent on every connected machine. */
    val agents: StateFlow<List<AgentRow>> = hosts.map { states ->
        states.flatMap { s ->
            val snap = s.snapshot ?: return@flatMap emptyList()
            snap.agents.map { AgentRow(s.machine, it, snap.location(it.workspaceId, it.tabId)) }
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        // Now, not on the first collect: a push or an alarm that started the process asks for a session at once.
        sync(machines.machines.value)
        scope.launch {
            machines.machines.collect { list -> sync(list) }
        }
    }

    fun session(machineId: String): HostSession? = sessions.value[machineId]

    fun setForeground(value: Boolean) {
        foreground = value
        sessions.value.values.forEach { s ->
            if (value && s.machine.enabled) s.start() else if (!value && !isBorrowed(s.machine.id)) s.stop()
        }
    }

    /**
     * Connects [machineId] for [block] also while the app is closed, and disconnects it after unless
     * the app came on screen meanwhile or another use still needs it.
     */
    suspend fun <T> borrow(machineId: String, block: suspend (HostSession) -> T): T {
        val s = session(machineId) ?: throw java.io.IOException("This machine isn't in the app any more")
        synchronized(borrowed) { borrowed[machineId] = (borrowed[machineId] ?: 0) + 1 }
        s.start()
        try {
            return block(s)
        } finally {
            val last = synchronized(borrowed) {
                val n = (borrowed[machineId] ?: 1) - 1
                if (n <= 0) borrowed.remove(machineId) else borrowed[machineId] = n
                n <= 0
            }
            if (last && !foreground) s.stop()
        }
    }

    private fun isBorrowed(id: String) = synchronized(borrowed) { id in borrowed }

    fun retryAll() = sessions.value.values.forEach { it.retry() }

    @Synchronized
    private fun sync(list: List<Machine>) {
        val current = sessions.value.toMutableMap()
        val ids = list.map { it.id }.toSet()
        (current.keys - ids).forEach { id -> current.remove(id)?.stop() }
        for (m in list) {
            val existing = current[m.id]
            if (existing == null) {
                val s = HostSession(
                    machine = m,
                    identity = { identity.get() },
                    lookup = { id -> machines.get(id) },
                    parentScope = scope,
                    onHostKeyLearned = { id, fp -> machines.update(id) { it.copy(hostKey = fp) } },
                )
                current[m.id] = s
                if (foreground && m.enabled) s.start()
            } else {
                existing.updateMachine(m)
                if (!m.enabled) existing.stop() else if (foreground) existing.start()
            }
        }
        sessions.value = current
    }
}
