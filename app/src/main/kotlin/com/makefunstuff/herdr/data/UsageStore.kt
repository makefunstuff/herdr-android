package com.makefunstuff.herdr.data

import com.makefunstuff.herdr.core.api.plainMessage
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.core.usage.HerdrUsage
import com.makefunstuff.herdr.core.usage.UsageLimit
import com.makefunstuff.herdr.core.usage.UsageReader
import com.makefunstuff.herdr.core.usage.UsageReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.time.ZoneId

/**
 * Each machine's latest usage reading (herdr-usage, read over SSH on the machine), kept in [file] so
 * the usage page, the top bar's glance and the agent choices show something the moment they open;
 * a fresh reading replaces it as it comes. [clock] (ms) and [zone] are the phone's, given by the
 * desktop preview to keep its pictures the same.
 */
class UsageStore(
    private val file: File,
    val clock: () -> Long = { System.currentTimeMillis() },
    val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    /** A reading and when the phone got it (its clock, ms). */
    data class Entry(val report: UsageReport, val readMs: Long)

    private val _latest = MutableStateFlow(load())
    val latest: StateFlow<Map<String, Entry>> = _latest.asStateFlow()

    /** Why the last reading of a machine failed, while it does. */
    private val _problems = MutableStateFlow<Map<String, String>>(emptyMap())
    val problems: StateFlow<Map<String, String>> = _problems.asStateFlow()

    /** The machines being read now. */
    private val _reading = MutableStateFlow<Set<String>>(emptySet())
    val reading: StateFlow<Set<String>> = _reading.asStateFlow()

    fun nowSeconds(): Long = clock() / 1000

    fun reports(): Map<String, UsageReport> = _latest.value.mapValues { it.value.report }

    /** Whether [machineId]'s reading is missing or older than [maxAgeMs]. */
    fun stale(machineId: String, maxAgeMs: Long): Boolean =
        _latest.value[machineId]?.let { clock() - it.readMs > maxAgeMs } ?: true

    /**
     * Reads [session]'s machine now (copying herdr-usage there first when it isn't, or is older);
     * [fresh] asks the providers again rather than taking the machine's few minutes old answer.
     * Null when it failed ([problems] says why) or another reading of it is under way.
     */
    suspend fun read(session: HostSession, fresh: Boolean = false): UsageReport? {
        val id = session.machine.id
        if (!session.machine.allowHostScripts) return null
        val mine = synchronized(_reading) {
            if (id in _reading.value) false else { _reading.value = _reading.value + id; true }
        }
        if (!mine) return null
        return try {
            val conn = session.awaitConnection()
            val client = HerdrUsage.Client(
                run = { cmd, t -> conn.run(cmd, t) },
                runWithInput = { cmd, input, t -> conn.runWithInput(cmd, input.byteInputStream(), t) },
                label = session.machine.label,
                allowInstall = session.machine.allowHostScripts,
            )
            val r = client.read(fresh)
            put(id, r)
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

    fun put(machineId: String, report: UsageReport) {
        _latest.update { it + (machineId to Entry(report, clock())) }
        save()
    }

    /**
     * Newer limits for [machineId] (`herdr-usage limits`, the app's own look while closed), into its
     * reading: the week of use stays, and so does when the whole reading was taken. A machine with no
     * reading yet keeps these as one.
     */
    fun putLimits(machineId: String, limits: UsageReport) {
        _latest.update { all ->
            val e = all[machineId]
            // No whole reading yet: these show, and the usage page still reads the whole (read at 0: stale).
            all + (machineId to if (e == null) Entry(limits.copy(raw = UsageReader.write(limits)), 0) else e.copy(report = e.report.withLimits(limits)))
        }
        save()
    }

    /** One limit as a push told it ([at]: the machine's clock, seconds), into [machineId]'s reading. */
    fun putLimit(machineId: String, provider: String, limit: UsageLimit, at: Long) {
        val e = _latest.value[machineId] ?: return
        _latest.update { it + (machineId to e.copy(report = e.report.withLimit(provider, limit, at))) }
        save()
    }

    /** Machines no longer in the app take their readings with them. */
    fun keep(ids: Set<String>) {
        if (_latest.value.keys.all { it in ids }) return
        _latest.update { it.filterKeys { k -> k in ids } }
        save()
    }

    /**
     * While the app is in front, every connected machine's reading is kept within [everyMs]: the
     * glance in the top bar and the agent choices stay current without the usage page open.
     */
    fun start(scope: CoroutineScope, hosts: HostManager, machines: MachineStore, everyMs: Long = 10 * 60_000L) {
        scope.launch { machines.machines.collect { list -> keep(list.map { it.id }.toSet()) } }
        scope.launch {
            delay(5_000)
            while (true) {
                if (hosts.isForeground) {
                    for (h in hosts.hosts.value) {
                        if (!h.online || !stale(h.machine.id, everyMs)) continue
                        hosts.session(h.machine.id)?.let { runCatching { read(it) } }
                    }
                }
                delay(60_000)
            }
        }
    }

    private fun load(): Map<String, Entry> = runCatching {
        val o = Json.parseToJsonElement(file.readText()).jsonObject
        o.mapNotNull { (id, v) ->
            val e = v as? JsonObject ?: return@mapNotNull null
            val raw = (e["raw"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val at = (e["at"] as? JsonPrimitive)?.longOrNull ?: 0
            UsageReader.cached(raw)?.let { id to Entry(it, at) }
        }.toMap()
    }.getOrDefault(emptyMap())

    @Synchronized
    private fun save() {
        val o = buildJsonObject {
            for ((id, e) in _latest.value) put(id, buildJsonObject { put("at", e.readMs); put("raw", e.report.raw) })
        }
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(file)
        }
    }
}
