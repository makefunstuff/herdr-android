package com.makefunstuff.herdr.data

import com.makefunstuff.herdr.core.health.HealthHistory
import com.makefunstuff.herdr.core.health.HealthPoint
import com.makefunstuff.herdr.core.health.HealthSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Each machine's latest health reading and its last hour, while the app runs: the health screen and
 * the machine's card read the same, so leaving the screen and coming back keeps the chart. The hour
 * starts from what herdr-push noted on the machine, where it runs.
 */
class HealthStore {
    private val _latest = MutableStateFlow<Map<String, HealthSample>>(emptyMap())
    val latest: StateFlow<Map<String, HealthSample>> = _latest.asStateFlow()

    private val _history = MutableStateFlow<Map<String, List<HealthPoint>>>(emptyMap())
    val history: StateFlow<Map<String, List<HealthPoint>>> = _history.asStateFlow()

    /** Machines whose herdr-push hour was read in this run (once is enough: the screen adds its own looks). */
    private val readHost = HashSet<String>()

    fun add(machineId: String, sample: HealthSample) {
        _latest.update { it + (machineId to sample) }
        val point = HealthPoint.of(sample) ?: return
        _history.update { all -> all + (machineId to HealthHistory.trim(all[machineId].orEmpty() + point, sample.at)) }
    }

    @Synchronized
    fun needsHostHistory(machineId: String): Boolean = readHost.add(machineId)

    fun addHistory(machineId: String, points: List<HealthPoint>, now: Long) {
        if (points.isEmpty()) return
        _history.update { all -> all + (machineId to HealthHistory.trim(points + all[machineId].orEmpty(), now)) }
    }
}
