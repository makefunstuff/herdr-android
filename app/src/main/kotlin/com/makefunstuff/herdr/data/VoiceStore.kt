package com.makefunstuff.herdr.data

import com.makefunstuff.herdr.core.chat.VoiceNote
import com.makefunstuff.herdr.core.chat.VoiceNotes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The voice messages recorded on the phone ([VoiceNote]), by chat (its [DraftStore] key), with their
 * audio files in [dir]: those not sent yet wait here for a retry, also across restarts; sent ones
 * are kept a while ([VoiceNotes.KEEP] per chat), so they play without fetching them back.
 */
class VoiceStore(private val file: File, val dir: File = File(file.parentFile, "voice")) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(VoiceNote.serializer())
    private val _notes = MutableStateFlow(load())
    val notes: StateFlow<List<VoiceNote>> = _notes.asStateFlow()

    /** A new file to record into. */
    fun newFile(): File {
        dir.mkdirs()
        return File(dir, "${System.currentTimeMillis()}-${(Math.random() * 1e6).toInt()}.m4a")
    }

    @Synchronized
    fun add(note: VoiceNote) {
        val (next, gone) = VoiceNotes.add(_notes.value, note)
        gone.forEach { File(it.file).delete() }
        save(next)
    }

    @Synchronized
    fun update(id: String, f: (VoiceNote) -> VoiceNote) = save(VoiceNotes.update(_notes.value, id, f))

    /** Thrown away: the note and its file. */
    @Synchronized
    fun drop(id: String) {
        val note = _notes.value.firstOrNull { it.id == id } ?: return
        File(note.file).delete()
        save(_notes.value - note)
    }

    fun note(id: String): VoiceNote? = _notes.value.firstOrNull { it.id == id }

    private fun save(next: List<VoiceNote>) {
        _notes.value = next
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(serializer, next))
            tmp.renameTo(file)
        }
    }

    // A file from before (AND-53 kept only the words of what was sent) doesn't read as notes: none.
    private fun load(): List<VoiceNote> =
        runCatching { if (file.exists()) json.decodeFromString(serializer, file.readText()) else null }.getOrNull()
            ?.filter { File(it.file).exists() }
            ?: emptyList()
}
