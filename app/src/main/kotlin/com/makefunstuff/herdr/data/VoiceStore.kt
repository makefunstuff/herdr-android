package com.makefunstuff.herdr.data

import com.makefunstuff.herdr.core.host.Attachments
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * A voice message in a chat's composer, waiting to go with the next message: the recording on the
 * phone ([path], [millis] long), and once copied to the chat's machine, [remote] is its path there.
 */
@Serializable
data class VoiceClip(
    val id: String,
    val path: String,
    val millis: Long,
    val remote: String? = null,
)

/**
 * The voice messages of each chat's next message, kept like its draft (under the same [DraftStore]
 * key) through leaving the chat, going offline and restarts, in [file]; the recordings themselves in
 * [clipDir]. And the ones sent from here, in [sentDir] by the machine and the path they have there
 * (with how long each is), so the chat plays them without reading them back; one sent from
 * elsewhere is read back once and kept there too.
 */
class VoiceStore(private val file: File, private val clipDir: File, private val sentDir: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val state = MutableStateFlow(load())

    /** The voice messages waiting in the composer under [key], in the order they were recorded. */
    fun clips(key: String): Flow<List<VoiceClip>> = state.map { it.clips[key].orEmpty() }.distinctUntilChanged()

    fun current(key: String): List<VoiceClip> = state.value.clips[key].orEmpty()

    /** A new file for a recording, with [extension]. */
    @Synchronized
    fun newFile(extension: String): File {
        clipDir.mkdirs()
        var n = System.nanoTime()
        while (File(clipDir, "$n.$extension").exists()) n++
        return File(clipDir, "$n.$extension")
    }

    /** Adds [clip] to [key]'s composer; false (and its file goes) when the message has as many as it takes. */
    @Synchronized
    fun add(key: String, clip: VoiceClip): Boolean {
        if (current(key).size >= MAX_CLIPS) {
            File(clip.path).delete()
            return false
        }
        change { it.copy(clips = it.clips + (key to current(key) + clip)) }
        return true
    }

    /** Takes [id] out of [key]'s composer, and its recording off the phone. */
    @Synchronized
    fun remove(key: String, id: String) {
        val gone = current(key).firstOrNull { it.id == id } ?: return
        File(gone.path).delete()
        change { set(it, key, current(key) - gone) }
    }

    /**
     * [id] is now at [remote] on [machineId]: noted in the composer, and a copy kept as that sent
     * voice message, so the chat plays it from the phone once it is sent. False when it left the
     * composer meanwhile.
     */
    @Synchronized
    fun uploaded(key: String, id: String, machineId: String, remote: String): Boolean {
        val list = current(key)
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return false
        runCatching { File(list[i].path).copyTo(sentFile(machineId, remote), overwrite = true) }
        val lengths = (state.value.lengths + (sentKey(machineId, remote) to list[i].millis)).entries.toList().takeLast(KEEP_LENGTHS)
        change { set(it, key, list.toMutableList().apply { set(i, list[i].copy(remote = remote)) }).copy(lengths = lengths.associate { e -> e.key to e.value }) }
        return true
    }

    /**
     * The message under [key] went with [sent] in it: those leave the composer (their copies stay as
     * sent voice messages); ones recorded while it was on its way stay for the next one.
     */
    @Synchronized
    fun sent(key: String, sent: List<VoiceClip>) {
        val ids = sent.map { it.id }.toSet()
        val (gone, left) = current(key).partition { it.id in ids }
        gone.forEach { File(it.path).delete() }
        change { set(it, key, left) }
    }

    /** Where the voice message at [remote] on [machineId] is kept on the phone, whether it is there yet or not. */
    fun sentFile(machineId: String, remote: String): File {
        val ext = remote.substringAfterLast('.', "ogg").lowercase().take(5)
        return File(sentDir.apply { mkdirs() }, sentKey(machineId, remote) + "." + ext)
    }

    /** How long the voice message at [remote] on [machineId] is, when it was sent from here. */
    fun millis(machineId: String, remote: String): Long? = state.value.lengths[sentKey(machineId, remote)]

    /** A sent voice message read back from its machine ([bytes]), kept for the next time it is played. */
    fun keepSent(machineId: String, remote: String, bytes: ByteArray): File {
        val target = sentFile(machineId, remote)
        val tmp = File(target.parentFile, target.name + ".part")
        tmp.writeBytes(bytes)
        tmp.renameTo(target)
        return target
    }

    /**
     * [typed] with the voice messages under [key] named in front (after a leading `/command` and
     * other mentions), in the order they were recorded, as the message goes.
     */
    fun message(key: String, typed: String): String =
        current(key).mapNotNull { it.remote }.fold(typed.trim()) { text, remote -> Attachments.withMention(text, remote) }.trim()

    private fun sentKey(machineId: String, remote: String): String =
        MessageDigest.getInstance("SHA-1").digest("$machineId\n$remote".toByteArray()).joinToString("") { "%02x".format(it) }

    private fun set(all: Saved, key: String, list: List<VoiceClip>) =
        all.copy(clips = if (list.isEmpty()) all.clips - key else all.clips + (key to list))

    private fun change(block: (Saved) -> Saved) {
        state.value = block(state.value)
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(Saved.serializer(), state.value))
            tmp.renameTo(file)
        }
    }

    @Serializable
    private data class Saved(
        val clips: Map<String, List<VoiceClip>> = emptyMap(),
        /** How long each voice message sent from here is, by its machine and path there. */
        val lengths: Map<String, Long> = emptyMap(),
    )

    private fun load(): Saved {
        val saved = runCatching { if (file.exists()) json.decodeFromString(Saved.serializer(), file.readText()) else null }.getOrNull() ?: Saved()
        // A recording whose file is gone (the app's files cleared) is dropped rather than sent empty.
        return saved.copy(clips = saved.clips.mapValues { (_, list) -> list.filter { File(it.path).exists() } }.filterValues { it.isNotEmpty() })
    }

    companion object {
        /** At most this many voice messages go with one message. */
        const val MAX_CLIPS = 5
        /** How many sent voice messages' lengths are kept. */
        const val KEEP_LENGTHS = 500
    }
}
