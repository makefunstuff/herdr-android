package demo.herdr.core.host

import demo.herdr.core.ssh.Machine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** What the app knows about a machine. */
data class HostState(
    val machine: Machine,
    val online: Boolean = false,
    val agents: Int = 0,
)

/** Keeps one machine connected and its state fresh. */
class HostSession(machine: Machine) {
    private val _state = MutableStateFlow(HostState(machine))
    val state: StateFlow<HostState> = _state

    /** herdr answered: the machine is online, with [agents] agents. */
    fun answered(agents: Int) {
        _state.update { it.copy(online = true, agents = agents) }
    }

    /** The connection broke; it is tried again. */
    fun lost() {
        _state.update { it.copy(online = false) }
    }
}
