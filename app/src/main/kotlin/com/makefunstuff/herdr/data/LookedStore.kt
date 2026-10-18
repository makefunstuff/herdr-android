package com.makefunstuff.herdr.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * When each agent's chat was last on screen (left, or the app put away while it was open), by
 * machine and pane: what its "While you were away" card counts from.
 */
class LookedStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), Long.serializer())
    private val _looked = MutableStateFlow(load())
    val looked: StateFlow<Map<String, Long>> = _looked.asStateFlow()

    /** When [machineId]'s [paneId] was last looked at; 0 when never. */
    fun at(machineId: String, paneId: String): Long = _looked.value["$machineId/$paneId"] ?: 0

    @Synchronized
    fun mark(machineId: String, paneId: String, at: Long) {
        val next = _looked.value + ("$machineId/$paneId" to at)
        if (next == _looked.value) return
        _looked.value = next
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(serializer, next))
            tmp.renameTo(file)
        }
    }

    private fun load(): Map<String, Long> =
        runCatching { if (file.exists()) json.decodeFromString(serializer, file.readText()) else null }.getOrNull() ?: emptyMap()
}
