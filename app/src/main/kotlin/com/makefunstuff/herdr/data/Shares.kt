package com.makefunstuff.herdr.data

import com.makefunstuff.herdr.core.share.Shared
import com.makefunstuff.herdr.core.share.SharedFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The way into a Coordinator from outside the app: what was shared to it (the share sheet, "Send to
 * Coordinator" on selected text) until a machine is chosen, the shared files each Coordinator chat
 * still has to copy to its machine, and the Coordinator used last, which the tile and the widget open.
 *
 * Kept in [file], as Android may end the app's process before a machine is chosen or comes online;
 * the shared files themselves are in folders under [dir], one per share.
 */
class Shares(private val file: File, private val dir: File, private val now: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Saved(
        val pending: Shared? = null,
        val pendingAt: Long = 0,
        val files: Map<String, List<SharedFile>> = emptyMap(),
        val last: String? = null,
    )

    private var saved: Saved = load()
    private val _pending = MutableStateFlow(saved.pending)
    /** Shared, with no machine chosen yet. */
    val pending: StateFlow<Shared?> = _pending.asStateFlow()
    private val files = MutableStateFlow(saved.files)
    private val _last = MutableStateFlow(saved.last)
    /** The machine whose Coordinator chat was opened last. */
    val last: StateFlow<String?> = _last.asStateFlow()

    @Synchronized
    fun used(machineId: String) {
        if (saved.last == machineId) return
        change { it.copy(last = machineId) }
    }

    /** Something new was shared: it waits for a machine, in place of one that was never sent on. */
    @Synchronized
    fun share(shared: Shared) {
        saved.pending?.let(::forget)
        change { it.copy(pending = shared, pendingAt = now()) }
    }

    /** Not sent anywhere after all: its copied files go. */
    @Synchronized
    fun cancel() {
        saved.pending?.let(::forget)
        change { it.copy(pending = null, pendingAt = 0) }
    }

    /**
     * Sends what was shared on to [machineId]'s Coordinator chat: its words to [addToDraft] (the end
     * of the chat's draft), its files to be copied there by the chat.
     */
    @Synchronized
    fun deliver(machineId: String, addToDraft: (String) -> Unit) {
        val s = saved.pending ?: return
        addToDraft(s.text)
        change { it.copy(pending = null, pendingAt = 0, files = if (s.files.isEmpty()) it.files else it.files + (machineId to it.files[machineId].orEmpty() + s.files)) }
    }

    /** The shared files [machineId]'s Coordinator chat still has to copy to its machine. */
    fun files(machineId: String): Flow<List<SharedFile>> = files.map { it[machineId].orEmpty() }.distinctUntilChanged()

    /** [file] was copied to [machineId] (or couldn't be): off the list, and off the phone. */
    @Synchronized
    fun done(machineId: String, file: SharedFile) {
        change {
            val rest = it.files[machineId].orEmpty() - file
            it.copy(files = if (rest.isEmpty()) it.files - machineId else it.files + (machineId to rest))
        }
        File(file.path).delete()
    }

    /** Only [machineIds] are in the app: the files waiting for any other machine go. */
    @Synchronized
    fun keep(machineIds: Set<String>) {
        val gone = saved.files.filterKeys { it !in machineIds }
        if (gone.isEmpty()) return
        gone.values.flatten().forEach { File(it.path).delete() }
        change { it.copy(files = it.files - gone.keys) }
    }

    /**
     * A new folder for a share's files. Folders days old go, unless a share waiting for a machine,
     * or for its machine to come online, still has a file in them.
     */
    @Synchronized
    fun newDir(): File {
        val root = dir.absoluteFile
        // The share's own folder in [dir] that holds each file still waiting.
        val used = (saved.pending?.files.orEmpty() + saved.files.values.flatten()).mapNotNull { f ->
            generateSequence(File(f.path).absoluteFile) { it.parentFile }.firstOrNull { it.parentFile == root }
        }.toSet()
        dir.listFiles()?.filter { now() - it.lastModified() > OLD_MS && it.absoluteFile !in used }?.forEach { it.deleteRecursively() }
        var n = now()
        while (File(dir, "$n").exists()) n++
        return File(dir, "$n").apply { mkdirs() }
    }

    private fun forget(s: Shared) = s.files.forEach { File(it.path).delete() }

    private fun change(block: (Saved) -> Saved) {
        saved = block(saved)
        _pending.value = saved.pending
        files.value = saved.files
        _last.value = saved.last
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(Saved.serializer(), saved))
            tmp.renameTo(file)
        }
    }

    private fun load(): Saved {
        val s = runCatching { if (file.exists()) json.decodeFromString(Saved.serializer(), file.readText()) else null }.getOrNull() ?: Saved()
        // A share left days ago without a machine is not brought up again.
        if (s.pending != null && now() - s.pendingAt > OLD_MS) {
            forget(s.pending)
            return s.copy(pending = null, pendingAt = 0)
        }
        return s
    }

    private companion object {
        const val OLD_MS = 2L * 24 * 3600 * 1000
    }
}
