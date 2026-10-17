package com.makefunstuff.herdr.brief

import com.makefunstuff.herdr.core.brief.Baseline
import com.makefunstuff.herdr.core.brief.Brief
import com.makefunstuff.herdr.core.brief.BriefSchedule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** The brief's part of the app: when it comes, when the person last looked, and the last one made. */
@Serializable
data class BriefState(
    val schedule: BriefSchedule = BriefSchedule(),
    /** When the person last looked: the app left the screen, or they read a brief (the phone's clock, ms; 0 never). */
    val seenAt: Long = 0,
    /** What the app showed of each machine then, for a machine without herdr-push's journal. */
    val baselines: Map<String, Baseline> = emptyMap(),
    /** The last brief made, shown at once while a new one is put together. */
    val last: Brief? = null,
    /** What each machine looked like as [last] was made: what the person knows once they read it. */
    val lastBaselines: Map<String, Baseline> = emptyMap(),
    /** The scheduled brief last posted (its minute, ms): an alarm that rings twice posts it once. */
    val postedFor: Long = 0,
) {
    /** The app left the screen at [now], having shown [seen] of the machines: the person looked until then. */
    fun left(now: Long, seen: Map<String, Baseline>): BriefState = copy(seenAt = maxOf(seenAt, now), baselines = baselines + seen)

    /** The brief made at [briefAt] was read to its end: the person knows what it said, unless they looked later. */
    fun read(briefAt: Long): BriefState =
        if (last?.at != briefAt || seenAt >= briefAt) this else copy(seenAt = briefAt, baselines = baselines + lastBaselines)

    /** [brief] was made, seeing the machines as [told]. */
    fun made(brief: Brief, told: Map<String, Baseline>): BriefState = copy(last = brief, lastBaselines = told)
}

/** [BriefState], kept as a small JSON file in app storage. */
class BriefStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _state = MutableStateFlow(load())
    val state: StateFlow<BriefState> = _state.asStateFlow()

    @Synchronized
    fun change(f: (BriefState) -> BriefState) {
        val next = f(_state.value)
        if (next == _state.value) return
        _state.value = next
        runCatching {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(BriefState.serializer(), next))
            tmp.renameTo(file)
        }
    }

    fun schedule(f: (BriefSchedule) -> BriefSchedule) = change { it.copy(schedule = f(it.schedule)) }

    private fun load(): BriefState =
        runCatching { if (file.exists()) json.decodeFromString(BriefState.serializer(), file.readText()) else null }.getOrNull() ?: BriefState()
}
