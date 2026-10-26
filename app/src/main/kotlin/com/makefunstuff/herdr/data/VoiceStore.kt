package com.makefunstuff.herdr.data

import com.makefunstuff.herdr.core.chat.Spoken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The messages sent as voice messages, by chat (its [DraftStore] key): the chat shows them with a
 * mic ([Spoken.marks]). The agent's transcript has only the words, so the phone keeps which they were.
 */
class VoiceStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), ListSerializer(Spoken.Sent.serializer()))
    private val _sent = MutableStateFlow(load())
    val sent: StateFlow<Map<String, List<Spoken.Sent>>> = _sent.asStateFlow()

    /** [text] went to [chat] as a voice message at [at]. */
    @Synchronized
    fun add(chat: String, text: String, at: Long) {
        save(_sent.value + (chat to Spoken.add(_sent.value[chat].orEmpty(), text, at)))
    }

    private fun save(next: Map<String, List<Spoken.Sent>>) {
        _sent.value = next
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(serializer, next))
            tmp.renameTo(file)
        }
    }

    /** [text] sent at [at] didn't go after all. */
    @Synchronized
    fun drop(chat: String, text: String, at: Long) {
        val list = _sent.value[chat] ?: return
        val i = list.indexOfLast { it.text == text && it.at == at }
        if (i < 0) return
        save(_sent.value + (chat to list.toMutableList().also { it.removeAt(i) }))
    }

    private fun load(): Map<String, List<Spoken.Sent>> =
        runCatching { if (file.exists()) json.decodeFromString(serializer, file.readText()) else null }.getOrNull() ?: emptyMap()
}
