package com.makefunstuff.herdr.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * What Start work remembers: the folders work was started in from the phone, per machine (newest
 * first), and how it was started last (the machine, a board task or an agent now, the agent), so the
 * next start begins there. Kept through restarts.
 */
class StartStore(private val file: File) {
    @Serializable
    data class State(
        val folders: Map<String, List<String>> = emptyMap(),
        val machine: String? = null,
        /** A board task (true) or an agent now. */
        val board: Boolean = true,
        /** A board task gets its agent at once. */
        val startNow: Boolean = true,
        /** The agent started last on each machine. */
        val kinds: Map<String, String> = emptyMap(),
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _state = MutableStateFlow(load())
    val state: StateFlow<State> = _state.asStateFlow()

    fun folders(machineId: String): List<String> = _state.value.folders[machineId].orEmpty()

    /** Work was started on [machineId] in [folder] (none: the board's own repo): it comes first next time, and so does how. */
    fun started(machineId: String, folder: String?, board: Boolean, startNow: Boolean, kind: String? = null) = change { s ->
        val f = folder?.trim()?.trimEnd('/')?.ifEmpty { null }
        s.copy(
            folders = if (f == null) s.folders else s.folders + (machineId to (listOf(f) + s.folders[machineId].orEmpty().filter { it != f }).take(MAX)),
            machine = machineId,
            board = board,
            startNow = if (board) startNow else s.startNow,
            kinds = if (kind == null) s.kinds else s.kinds + (machineId to kind),
        )
    }

    /** Forgets the machines not in [machines] (taken out of the app). */
    fun keep(machines: Set<String>) = change { s ->
        s.copy(folders = s.folders.filterKeys { it in machines }, kinds = s.kinds.filterKeys { it in machines }, machine = s.machine?.takeIf { it in machines })
    }

    @Synchronized
    private fun change(f: (State) -> State) {
        val before = _state.value
        _state.update(f)
        val next = _state.value
        if (next == before) return
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(State.serializer(), next))
            tmp.renameTo(file)
        }
    }

    private fun load(): State =
        runCatching { if (file.exists()) json.decodeFromString(State.serializer(), file.readText()) else null }.getOrNull() ?: State()

    private companion object {
        const val MAX = 6
    }
}
