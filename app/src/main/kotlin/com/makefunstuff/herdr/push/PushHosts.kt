package com.makefunstuff.herdr.push

import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.core.push.HerdrPush
import com.makefunstuff.herdr.data.HostManager
import com.makefunstuff.herdr.data.MachineStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * herdr-push on every machine the app knows: set up for this phone when notifications are on and the
 * machine isn't left out, taken away when not. It happens as each machine connects, so a machine
 * that is offline now catches up the next time.
 */
class PushHosts(
    private val scope: CoroutineScope,
    private val hosts: HostManager,
    private val machines: MachineStore,
    private val store: PushStore,
    private val phone: PushPhone,
) {
    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    /** Machines being set up or taken off right now. */
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    /** Machines looked at since they came online; cleared when one goes offline, so a reconnect looks again. */
    private val looked = HashSet<String>()
    private val locks = HashMap<String, Mutex>()

    fun start() {
        scope.launch {
            hosts.hosts.collect { states ->
                for (s in states) {
                    val id = s.machine.id
                    val fresh = synchronized(looked) { if (s.online) looked.add(id) else { looked.remove(id); false } }
                    if (fresh) scope.launch { sync(id, quiet = true) }
                }
            }
        }
        // Turning it on or off, a new address from the push app, or a machine left out: look again where online.
        scope.launch {
            store.state.map { s -> Triple(s.on, s.endpoint, s.machines.mapValues { it.value.off }) }.distinctUntilChanged().collect {
                for (s in hosts.hosts.value) if (s.online) scope.launch { sync(s.machine.id, quiet = true) }
            }
        }
    }

    /** Brings [machineId]'s herdr-push in line with the settings. Without [quiet], a failure is thrown too. */
    suspend fun sync(machineId: String, quiet: Boolean = false) {
        val lock = synchronized(locks) { locks.getOrPut(machineId) { Mutex() } }
        lock.withLock {
            val machine = machines.get(machineId)?.takeIf { it.enabled } ?: return
            val st = store.state.value
            val m = st.machine(machineId)
            val wanted = st.on && st.endpoint != null && !m.off
            if (!wanted && m.sentTo == null) return
            _busy.update { it + machine.id }
            try {
                hosts.borrow(machineId) { session -> if (wanted) setUp(session, st.endpoint!!) else takeOff(session) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                store.machine(machineId) { it.copy(problem = t.message ?: t.javaClass.simpleName) }
                if (!quiet) throw t
            } finally {
                _busy.update { it - machine.id }
            }
        }
    }

    private suspend fun setUp(session: HostSession, endpoint: String) {
        val id = session.machine.id
        val conn = session.awaitConnection()
        val client = HerdrPush.Client(conn, session.machine.label)
        val m = store.state.value.machine(id)
        // Already set up for this address with this version: only make sure it runs and still reaches the phone.
        if (m.sentTo == endpoint && m.version == HerdrPush.version) {
            val status = runCatching { HerdrPush.status(client.run("ensure"), subscriber(id)) }.getOrNull()
            if (status != null && status.subscribed && !status.gone && status.version == HerdrPush.version) {
                store.machine(id) { it.copy(problem = status.error?.let { e -> "Its last push didn't go through: $e" }) }
                return
            }
        }
        client.install()
        session.checkBoard()
        val host = session.state.value
        val sub = HerdrPush.subscription(
            id = subscriber(id),
            phoneName = phone.name,
            endpoint = endpoint,
            key = store.key(id),
            session = host.machine.session,
            herdrPath = host.herdrMachine.herdrPath,
            boardPath = host.boardPath,
        )
        client.run("subscribe", input = sub)
        val started = client.run("install")
        val note = (started["msg"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it != "running" }
        // A fresh subscription starts the order of its pushes afresh (in case the machine's clock went back).
        store.machine(id) { it.copy(sentTo = endpoint, version = HerdrPush.version, problem = null, note = note, lastAt = 0) }
    }

    private suspend fun takeOff(session: HostSession) {
        val id = session.machine.id
        val client = HerdrPush.Client(session.awaitConnection(), session.machine.label)
        try {
            val left = client.run("unsubscribe", subscriber(id))
            // The last phone gone: nothing to watch for, so it stops and leaves no start-up entry.
            if ((left["phones"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "0") client.run("uninstall")
        } catch (_: HerdrPush.MissingException) {
        }
        store.machine(id) { it.copy(sentTo = null, version = "", problem = null, note = null) }
    }

    /** This phone's subscription for [machineId] on its host: one per machine in the app, also when two share a host. */
    private fun subscriber(machineId: String) = HerdrPush.subscriberId(store.state.value.phoneId, machineId)

    /** Asks [machineId]'s herdr-push for a test notification. */
    suspend fun test(machineId: String) {
        hosts.borrow(machineId) { session ->
            val client = HerdrPush.Client(session.awaitConnection(), session.machine.label)
            client.run("test", subscriber(machineId))
        }
    }

    /** Turns notifications on: asks the push app for an address (the machines follow once it answers). */
    fun turnOn(): Boolean {
        store.change { it.copy(on = true, pushProblem = null) }
        val asked = phone.register()
        phone.planChecks()
        return asked
    }

    fun turnOff() {
        store.change { it.copy(on = false) }
        phone.unregister()
        phone.planChecks()
    }

    fun leaveOut(machineId: String, off: Boolean) {
        store.machine(machineId) { it.copy(off = off, problem = null) }
    }

    /** The machines' labels, for the notifications' words. */
    fun label(machineId: String): String = machines.get(machineId)?.label ?: "a machine"
}
