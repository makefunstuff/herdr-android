package com.makefunstuff.herdr.data

import com.makefunstuff.herdr.core.terminal.BarKey
import com.makefunstuff.herdr.core.terminal.KeyBar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The terminal's own settings, kept in [file] through restarts: the keys of its key bar, in order
 * (as [BarKey.spec]s), and whether it keeps the screen on.
 */
class KeyBarStore(private val file: File) {
    @Serializable
    data class Saved(val keys: List<String> = KeyBar.DEFAULT, val keepScreenOn: Boolean = false) {
        /** The bar's keys this app knows, each once. */
        val bar: List<BarKey> get() = KeyBar.keys(keys)
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Saved> = _state.asStateFlow()

    /** Adds [key] at the end of the bar; a key it has already stays where it is. */
    fun add(key: BarKey) = change { s -> if (s.bar.contains(key)) s else s.copy(keys = s.bar.map { it.spec } + key.spec) }

    fun remove(key: BarKey) = change { s -> s.copy(keys = s.bar.filter { it != key }.map { it.spec }) }

    /** Moves [key] by [by] places (−1 earlier, +1 later). */
    fun move(key: BarKey, by: Int) = change { s ->
        val list = s.bar.toMutableList()
        val from = list.indexOf(key)
        val to = from + by
        if (from < 0 || to !in list.indices) return@change s
        list.add(to, list.removeAt(from))
        s.copy(keys = list.map { it.spec })
    }

    fun reset() = change { it.copy(keys = KeyBar.DEFAULT) }

    fun setKeepScreenOn(on: Boolean) = change { it.copy(keepScreenOn = on) }

    @Synchronized
    private fun change(f: (Saved) -> Saved) {
        val next = f(_state.value)
        if (next == _state.value) return
        _state.value = next
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(Saved.serializer(), next))
            tmp.renameTo(file)
        }
    }

    private fun load(): Saved =
        runCatching { if (file.exists()) json.decodeFromString(Saved.serializer(), file.readText()) else null }.getOrNull() ?: Saved()
}
