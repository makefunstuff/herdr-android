package com.makefunstuff.herdr.data

import android.content.Context
import com.makefunstuff.herdr.core.ssh.Machine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** Saved machines, kept as a small JSON file in app storage. */
class MachineStore(context: Context) {
    private val file = File(context.filesDir, "machines.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val serializer = ListSerializer(Machine.serializer())

    private val _machines = MutableStateFlow(load())
    val machines: StateFlow<List<Machine>> = _machines.asStateFlow()

    fun get(id: String): Machine? = _machines.value.firstOrNull { it.id == id }

    @Synchronized
    fun upsert(machine: Machine) {
        val list = _machines.value.toMutableList()
        val index = list.indexOfFirst { it.id == machine.id }
        if (index >= 0) list[index] = machine else list += machine
        save(list)
    }

    @Synchronized
    fun update(id: String, change: (Machine) -> Machine) {
        val current = get(id) ?: return
        upsert(change(current))
    }

    @Synchronized
    fun remove(id: String) {
        save(
            _machines.value
                .filterNot { it.id == id }
                .map { if (it.viaMachineId == id) it.copy(viaMachineId = null) else it },
        )
    }

    private fun save(list: List<Machine>) {
        val tmp = File(file.parentFile, "machines.json.tmp")
        tmp.writeText(json.encodeToString(serializer, list))
        tmp.renameTo(file)
        _machines.value = list
    }

    private fun load(): List<Machine> = runCatching {
        if (file.exists()) json.decodeFromString(serializer, file.readText()) else emptyList()
    }.getOrDefault(emptyList())

    companion object {
        fun newId(): String = UUID.randomUUID().toString().take(8)
    }
}
