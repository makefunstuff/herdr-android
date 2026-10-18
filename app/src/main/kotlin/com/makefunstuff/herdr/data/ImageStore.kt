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
 * A picture in a chat's composer, waiting to go with the next message: made smaller on the phone
 * ([path], [width]×[height]), and once copied to the chat's machine, [remote] is its path there.
 */
@Serializable
data class TrayImage(
    val id: String,
    val name: String,
    val path: String,
    val width: Int,
    val height: Int,
    val remote: String? = null,
)

/**
 * The pictures of each chat's next message, kept like its draft (under the same [DraftStore] key)
 * through leaving the chat and restarts, in [file]; the pictures themselves in [trayDir]. And the
 * pictures sent from here, in [sentDir] by the machine and the path they have there, so the chat
 * shows them without reading them back; one sent from elsewhere is read back once and kept there too.
 */
class ImageStore(private val file: File, private val trayDir: File, private val sentDir: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val trays = MutableStateFlow(load())

    /** The pictures waiting in the composer under [key], in the order they were added. */
    fun tray(key: String): Flow<List<TrayImage>> = trays.map { it[key].orEmpty() }.distinctUntilChanged()

    fun current(key: String): List<TrayImage> = trays.value[key].orEmpty()

    /** A new file in the tray's folder for a picture being added, with [extension]. */
    @Synchronized
    fun newFile(extension: String): File {
        trayDir.mkdirs()
        var n = System.nanoTime()
        while (File(trayDir, "$n.$extension").exists()) n++
        return File(trayDir, "$n.$extension")
    }

    /** Adds [image] to [key]'s tray; false (and its file goes) when the tray is full. */
    @Synchronized
    fun add(key: String, image: TrayImage): Boolean {
        if (current(key).size >= MAX_IMAGES) {
            File(image.path).delete()
            return false
        }
        change { it + (key to it[key].orEmpty() + image) }
        return true
    }

    /** Takes [id] out of [key]'s tray, and its file off the phone. */
    @Synchronized
    fun remove(key: String, id: String) {
        val gone = current(key).firstOrNull { it.id == id } ?: return
        File(gone.path).delete()
        change { set(it, key, current(key) - gone) }
    }

    /** [id] was edited (cropped, drawn on): [edited] takes its place, to be copied again. */
    @Synchronized
    fun replace(key: String, id: String, edited: TrayImage) {
        val list = current(key)
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) {
            File(edited.path).delete()
            return
        }
        if (list[i].path != edited.path) File(list[i].path).delete()
        change { set(it, key, list.toMutableList().apply { set(i, edited.copy(id = id, remote = null)) }) }
    }

    /**
     * [id] is now at [remote] on [machineId]: noted in the tray, and a copy kept as that sent picture,
     * so the chat shows it from the phone once it is sent. False when it left the tray meanwhile.
     */
    @Synchronized
    fun uploaded(key: String, id: String, machineId: String, remote: String, from: String): Boolean {
        val list = current(key)
        val i = list.indexOfFirst { it.id == id }
        // Removed, or edited again, while it was being copied: the new one is copied in its turn.
        if (i < 0 || list[i].path != from) return false
        runCatching { File(from).copyTo(sentFile(machineId, remote), overwrite = true) }
        change { set(it, key, list.toMutableList().apply { set(i, list[i].copy(remote = remote)) }) }
        return true
    }

    /**
     * The message under [key] went with [sent] in it: those leave the tray (their copies stay as
     * sent pictures); pictures added while it was on its way stay for the next one.
     */
    @Synchronized
    fun sent(key: String, sent: List<TrayImage>) {
        val ids = sent.map { it.id }.toSet()
        val (gone, left) = current(key).partition { it.id in ids }
        gone.forEach { File(it.path).delete() }
        change { set(it, key, left) }
    }

    /** Where the picture at [remote] on [machineId] is kept on the phone, whether it is there yet or not. */
    fun sentFile(machineId: String, remote: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest("$machineId\n$remote".toByteArray())
        val ext = remote.substringAfterLast('.', "img").lowercase().take(5)
        return File(sentDir.apply { mkdirs() }, digest.joinToString("") { "%02x".format(it) } + "." + ext)
    }

    /** A sent picture read back from its machine ([bytes]), kept for the next time the chat shows it. */
    fun keepSent(machineId: String, remote: String, bytes: ByteArray): File {
        val target = sentFile(machineId, remote)
        val tmp = File(target.parentFile, target.name + ".part")
        tmp.writeBytes(bytes)
        tmp.renameTo(target)
        return target
    }

    /**
     * The message under [key] and its pictures as it goes: [typed] with a mention of each picture
     * in front (after a leading `/command`), in the order they were added.
     */
    fun message(key: String, typed: String): String =
        current(key).mapNotNull { it.remote }.fold(typed.trim()) { text, remote -> Attachments.withMention(text, remote) }.trim()

    private fun set(all: Map<String, List<TrayImage>>, key: String, list: List<TrayImage>) =
        if (list.isEmpty()) all - key else all + (key to list)

    private fun change(block: (Map<String, List<TrayImage>>) -> Map<String, List<TrayImage>>) {
        trays.value = block(trays.value)
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(Saved.serializer(), Saved(trays.value)))
            tmp.renameTo(file)
        }
    }

    @Serializable
    private data class Saved(val trays: Map<String, List<TrayImage>> = emptyMap())

    private fun load(): Map<String, List<TrayImage>> {
        val saved = runCatching { if (file.exists()) json.decodeFromString(Saved.serializer(), file.readText()) else null }.getOrNull() ?: Saved()
        // A picture whose file Android took away (or never finished) is dropped rather than shown broken.
        return saved.trays.mapValues { (_, list) -> list.filter { File(it.path).exists() } }.filterValues { it.isNotEmpty() }
    }

    companion object {
        /** At most this many pictures go with one message. */
        const val MAX_IMAGES = 10
    }
}
