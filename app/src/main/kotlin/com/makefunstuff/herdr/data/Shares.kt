package com.makefunstuff.herdr.data

import android.content.Context
import com.makefunstuff.herdr.core.share.Shared
import com.makefunstuff.herdr.core.share.SharedFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * The way into a Coordinator from outside the app: what was shared to it (the share sheet, "Send to
 * Coordinator" on selected text) until a machine is chosen, the shared files each Coordinator chat
 * still has to copy to its machine, and the Coordinator used last, which the tile and the widget open.
 */
class Shares(context: Context) {
    private val prefs = context.getSharedPreferences("ask", Context.MODE_PRIVATE)
    private val _pending = MutableStateFlow<Shared?>(null)
    /** Shared, with no machine chosen yet. */
    val pending: StateFlow<Shared?> = _pending.asStateFlow()
    private val files = MutableStateFlow<Map<String, List<SharedFile>>>(emptyMap())
    private val _last = MutableStateFlow(prefs.getString(LAST, null))
    /** The machine whose Coordinator chat was opened last. */
    val last: StateFlow<String?> = _last.asStateFlow()

    fun used(machineId: String) {
        if (_last.value == machineId) return
        _last.value = machineId
        prefs.edit().putString(LAST, machineId).apply()
    }

    /** Something new was shared: it waits for a machine, in place of one that was never sent on. */
    fun share(shared: Shared) {
        _pending.value?.let(::forget)
        _pending.value = shared
    }

    /** Not sent anywhere after all: its copied files go. */
    fun cancel() {
        _pending.value?.let(::forget)
        _pending.value = null
    }

    /** Sends what was shared on to [machineId]'s Coordinator chat: its words to the end of the chat's draft, its files to be copied there. */
    @Synchronized
    fun deliver(machineId: String, drafts: DraftStore) {
        val s = _pending.value ?: return
        _pending.value = null
        drafts.add(DraftStore.coordinator(machineId), s.text)
        if (s.files.isNotEmpty()) files.value = files.value + (machineId to files.value[machineId].orEmpty() + s.files)
    }

    /** The shared files [machineId]'s Coordinator chat still has to copy to its machine. */
    fun files(machineId: String): Flow<List<SharedFile>> = files.map { it[machineId].orEmpty() }.distinctUntilChanged()

    /** [file] was copied to [machineId] (or couldn't be): off the list, and off the phone. */
    @Synchronized
    fun done(machineId: String, file: SharedFile) {
        val rest = files.value[machineId].orEmpty() - file
        files.value = if (rest.isEmpty()) files.value - machineId else files.value + (machineId to rest)
        File(file.path).delete()
    }

    private fun forget(s: Shared) = s.files.forEach { File(it.path).delete() }

    private companion object {
        const val LAST = "last_coordinator"
    }
}
