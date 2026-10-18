package com.makefunstuff.herdr.data

import com.makefunstuff.herdr.core.home.HomeAgent
import com.makefunstuff.herdr.core.home.Triage
import com.makefunstuff.herdr.core.host.HostState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.time.ZoneId

/**
 * The Agents list's triage, on this phone ([Triage]): agents pinned, snoozed and muted, by
 * `machine/pane`. [now] and [zone] are the clock snoozes run on (fixed in the screen previews).
 */
class TriageStore(
    private val file: File,
    val now: () -> Long = System::currentTimeMillis,
    val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Triage> = _state.asStateFlow()

    /** Whether notifications about [machineId]'s pane [paneId] are muted. */
    fun mutes(machineId: String, paneId: String): Boolean = _state.value.mutes(machineId, paneId)

    @Synchronized
    fun change(f: (Triage) -> Triage) {
        val next = f(_state.value)
        if (next == _state.value) return
        _state.value = next
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(Triage.serializer(), next))
            tmp.renameTo(file)
        }
    }

    /**
     * Drops what is over: snoozes that ended, the marks of panes gone from the machines read ([hosts]),
     * and those of machines no longer in the app ([machines], every machine saved).
     */
    fun keep(hosts: List<HostState>, machines: Set<String>) {
        val read = hosts.filter { it.snapshot != null }.map { it.machine.id }.toSet()
        change { it.keep(HomeAgent.of(hosts), read, machines, now()) }
    }

    private fun load(): Triage =
        runCatching { if (file.exists()) json.decodeFromString(Triage.serializer(), file.readText()) else null }.getOrNull() ?: Triage()
}
