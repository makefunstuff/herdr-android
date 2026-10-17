package com.makefunstuff.herdr.data

import com.makefunstuff.herdr.core.code.ReviewNote
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** How code is shown: long lines wrapped or scrolled sideways, and the text's size in sp. */
@Serializable
data class CodeView(val wrap: Boolean = false, val textSize: Int = 13) {
    companion object {
        const val SMALLEST = 9
        const val LARGEST = 22
    }
}

/**
 * The code browser's own things: the comments written and not sent yet (they survive leaving the
 * screen and a restart of the app), and how code is shown. Kept in [file].
 *
 * Also what was read from the machines while the app runs ([cache]), so going back and forth
 * between a checkout's files, its changes and a diff doesn't read them again.
 */
class CodeStore(private val file: File, /** The clock, in milliseconds ("3 h ago"). */ val now: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Saved(val notes: List<ReviewNote> = emptyList(), val view: CodeView = CodeView())

    private var saved: Saved = runCatching { if (file.exists()) json.decodeFromString(Saved.serializer(), file.readText()) else null }.getOrNull() ?: Saved()

    private val _notes = MutableStateFlow(saved.notes)
    /** Comments waiting to be sent, oldest first. */
    val notes: StateFlow<List<ReviewNote>> = _notes.asStateFlow()

    private val _view = MutableStateFlow(saved.view)
    val view: StateFlow<CodeView> = _view.asStateFlow()

    val cache = CodeCache()

    /** A new note's id. */
    fun newId(): String = "${now()}-${(0..0xffff).random().toString(16)}"

    @Synchronized
    fun add(note: ReviewNote) = change { it.copy(notes = it.notes.filter { n -> n.id != note.id } + note.copy(at = if (note.at == 0L) now() else note.at)) }

    @Synchronized
    fun update(note: ReviewNote) = change { s -> s.copy(notes = s.notes.map { if (it.id == note.id) note.copy(at = it.at) else it }) }

    @Synchronized
    fun remove(ids: Collection<String>) = change { s -> s.copy(notes = s.notes.filter { it.id !in ids }) }

    @Synchronized
    fun setView(view: CodeView) = change { it.copy(view = view.copy(textSize = view.textSize.coerceIn(CodeView.SMALLEST, CodeView.LARGEST))) }

    private fun change(block: (Saved) -> Saved) {
        saved = block(saved)
        _notes.value = saved.notes
        _view.value = saved.view
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(Saved.serializer(), saved))
            tmp.renameTo(file)
        }
    }
}

/** What was read, by what it was read for (a machine, a checkout, a diff): the last few dozen. */
class CodeCache(private val size: Int = 48) {
    private val map = object : LinkedHashMap<String, Any>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>?) = this.size > this@CodeCache.size
    }

    @Suppress("UNCHECKED_CAST")
    @Synchronized
    fun <T : Any> get(key: String): T? = map[key] as? T

    @Synchronized
    fun put(key: String, value: Any) {
        map[key] = value
    }

    /** Forgets what was read for [prefix] (a checkout, when it is read again). */
    @Synchronized
    fun drop(prefix: String) {
        map.keys.removeAll { it.startsWith(prefix) }
    }
}
