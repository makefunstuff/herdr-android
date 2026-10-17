package demo.herdr.data

import demo.herdr.core.host.HostSession
import demo.herdr.core.ssh.Machine

/** One session per saved machine. */
class HostManager(machines: List<Machine>) {
    private val sessions = machines.associate { it.id to HostSession(it) }

    fun session(id: String): HostSession? = sessions[id]

    val all: Collection<HostSession> get() = sessions.values
}
