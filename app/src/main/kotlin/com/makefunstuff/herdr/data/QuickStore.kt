package com.makefunstuff.herdr.data

import com.makefunstuff.herdr.core.quick.QuickRun
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The quick commands' own things on the phone: the ones pinned as favourites, per machine (by
 * [com.makefunstuff.herdr.core.quick.QuickCommand.key]), kept in [file]; and the runs last read
 * from each machine while the app runs, so the notifications know what was already seen.
 */
class QuickStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Saved(val pins: Map<String, List<String>> = emptyMap())

    private var saved: Saved = runCatching { if (file.exists()) json.decodeFromString(Saved.serializer(), file.readText()) else null }.getOrNull() ?: Saved()

    private val _pins = MutableStateFlow(saved.pins)
    /** Machine id → the keys of its pinned commands, in the order they were pinned. */
    val pins: StateFlow<Map<String, List<String>>> = _pins.asStateFlow()

    private val runs = HashMap<String, List<QuickRun>>()

    fun pinned(machineId: String, key: String): Boolean = key in _pins.value[machineId].orEmpty()

    @Synchronized
    fun setPinned(machineId: String, key: String, pinned: Boolean) {
        val now = saved.pins[machineId].orEmpty().filter { it != key } + listOfNotNull(key.takeIf { pinned })
        saved = saved.copy(pins = (saved.pins + (machineId to now)).filterValues { it.isNotEmpty() })
        _pins.value = saved.pins
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(Saved.serializer(), saved))
            tmp.renameTo(file)
        }
    }

    private val listings = HashMap<String, com.makefunstuff.herdr.core.quick.QuickListing>()

    /** The commands last read for [dir] on [machineId], shown while they are read again. */
    @Synchronized
    fun listed(machineId: String, dir: String): com.makefunstuff.herdr.core.quick.QuickListing? = listings["$machineId\n$dir"]

    @Synchronized
    fun list(machineId: String, dir: String, listing: com.makefunstuff.herdr.core.quick.QuickListing) {
        listings["$machineId\n$dir"] = listing
    }

    /** What a screen last read of [machineId]'s runs. */
    @Synchronized
    fun saw(machineId: String, list: List<QuickRun>) {
        runs[machineId] = list
    }

    /** Every machine's runs as last read. */
    @Synchronized
    fun seen(): Map<String, List<QuickRun>> = HashMap(runs)
}

/** The machine's quick commands, over its SSH connection. */
fun com.makefunstuff.herdr.core.host.HostSession.quick(): com.makefunstuff.herdr.core.quick.QuickClient =
    com.makefunstuff.herdr.core.quick.QuickClient(
        run = { command, timeout -> awaitConnection().run(command, timeout) },
        runWithInput = { command, input, timeout -> awaitConnection().runWithInput(command, input.byteInputStream(), timeout) },
    )
