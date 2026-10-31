package com.makefunstuff.herdr.data

import com.makefunstuff.herdr.core.api.plainMessage
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.core.logins.CliLogin
import com.makefunstuff.herdr.core.logins.HerdrLogins
import com.makefunstuff.herdr.core.logins.LoginsReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.ZoneId

/**
 * Each machine's agent logins as herdr-logins last read them there: signed in or not, how, until
 * when. Only in memory, and never a secret (the machine sends none). [clock] (ms) and [zone] are the
 * phone's, given by the desktop preview to keep its pictures the same.
 */
class LoginsStore(
    val clock: () -> Long = { System.currentTimeMillis() },
    val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    data class Entry(val report: LoginsReport, val readMs: Long)

    private val _latest = MutableStateFlow<Map<String, Entry>>(emptyMap())
    val latest: StateFlow<Map<String, Entry>> = _latest.asStateFlow()

    private val _problems = MutableStateFlow<Map<String, String>>(emptyMap())
    val problems: StateFlow<Map<String, String>> = _problems.asStateFlow()

    private val _reading = MutableStateFlow<Set<String>>(emptySet())
    val reading: StateFlow<Set<String>> = _reading.asStateFlow()

    fun nowSeconds(): Long = clock() / 1000

    fun stale(machineId: String, maxAgeMs: Long): Boolean =
        _latest.value[machineId]?.let { clock() - it.readMs > maxAgeMs } ?: true

    /** herdr-logins on [session]'s machine, copied there first when it isn't, or is older. */
    fun client(session: HostSession): HerdrLogins.Client = HerdrLogins.Client(
        run = { cmd, t -> session.awaitConnection().run(cmd, t) },
        runWithInput = { cmd, input, t -> session.awaitConnection().runWithInput(cmd, input.byteInputStream(), t) },
        start = { cmd -> session.awaitConnection().start(cmd) },
        label = session.machine.label,
        allowInstall = session.machine.allowHostScripts,
    )

    /** Reads [session]'s machine now; null when it failed ([problems] says why) or a reading is under way. */
    suspend fun read(session: HostSession): LoginsReport? {
        val id = session.machine.id
        if (!session.machine.allowHostScripts) return null
        val mine = synchronized(_reading) {
            if (id in _reading.value) false else { _reading.value = _reading.value + id; true }
        }
        if (!mine) return null
        return try {
            val r = client(session).status()
            _latest.update { it + (id to Entry(r, clock())) }
            _problems.update { it - id }
            r
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            _problems.update { it + (id to t.plainMessage()) }
            null
        } finally {
            synchronized(_reading) { _reading.value = _reading.value - id }
        }
    }

    /** One CLI's status after a sign-in or sign-out, into [machineId]'s reading. */
    fun put(machineId: String, cli: CliLogin) {
        _latest.update { all ->
            val e = all[machineId] ?: return@update all
            all + (machineId to e.copy(report = e.report.copy(clis = e.report.clis.map { if (it.id == cli.id) cli else it })))
        }
    }
}
