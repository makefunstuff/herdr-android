package com.makefunstuff.herdr.ui.common

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.makefunstuff.herdr.core.host.Attachments
import com.makefunstuff.herdr.core.host.HostSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The composer's attach button: [pick] opens the phone's file picker; [uploading] while files are copied. */
class Attacher(val uploading: Boolean, val pick: () -> Unit)

/**
 * Attaching files to a chat message, as the web UI does: each file picked on the phone is copied
 * to [session]'s machine over SSH ([HostSession.upload]) and [onAttached] gets its path there,
 * for the draft to name it (`@path`, [Attachments.withMention]). Up to 20 files at a time, each at
 * most [Attachments.MAX_BYTES]; one that can't be copied says why and the others still go.
 */
@Composable
fun rememberAttacher(session: HostSession, onAttached: (path: String) -> Unit): Attacher {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var uploading by remember { mutableStateOf(false) }
    val attached by rememberUpdatedState(onAttached)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        if (uris.size > MAX_FILES) context.toast("Attached the first $MAX_FILES files")
        uploading = true
        scope.launch {
            for (uri in uris.take(MAX_FILES)) {
                val (name, size) = withContext(Dispatchers.IO) { describe(context, uri) }
                if (size != null && size > Attachments.MAX_BYTES) {
                    context.toast("$name is over 25 MB, so it wasn't attached")
                    continue
                }
                runCatching {
                    withContext(Dispatchers.IO) {
                        val input = context.contentResolver.openInputStream(uri) ?: error("Couldn't read $name")
                        input.use { session.upload(name, it) }
                    }
                }.onSuccess { attached(it) }
                    .onFailure { context.toast(it.message ?: "Couldn't attach $name") }
            }
            uploading = false
        }
    }
    return Attacher(uploading) { launcher.launch("*/*") }
}

private const val MAX_FILES = 20

/** The picked file's name and size, as the provider gives them. */
private fun describe(context: Context, uri: Uri): Pair<String, Long?> {
    var name: String? = null
    var size: Long? = null
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val s = c.getColumnIndex(OpenableColumns.SIZE)
                if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
            }
        }
    }
    return (name ?: uri.lastPathSegment ?: "file") to size
}
