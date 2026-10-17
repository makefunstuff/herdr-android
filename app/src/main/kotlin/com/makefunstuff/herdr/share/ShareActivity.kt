package com.makefunstuff.herdr.share

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.makefunstuff.herdr.HerdrApp
import com.makefunstuff.herdr.MainActivity
import com.makefunstuff.herdr.core.host.Attachments
import com.makefunstuff.herdr.core.share.ShareText
import com.makefunstuff.herdr.core.share.Shared
import com.makefunstuff.herdr.core.share.SharedFile
import com.makefunstuff.herdr.ui.common.toast
import com.makefunstuff.herdr.ui.share.Ask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Where the share sheet and "Send to Coordinator" in the text-selection menu land. It shows nothing:
 * it takes what was shared, copies its files into the app (the right to read them ends with this
 * activity), and opens the app on the Coordinator for it ([Ask.SHARE]) in the app's own task.
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return finish()
        val container = (application as HerdrApp).container
        val intent = intent
        // The selected text is left as it was.
        if (intent.action == Intent.ACTION_PROCESS_TEXT) setResult(RESULT_CANCELED)
        lifecycleScope.launch {
            val (shared, problems) = withContext(Dispatchers.IO) { read(intent) }
            if (shared.isEmpty) {
                // The app doesn't open, so Android's own toast says why.
                Toast.makeText(this@ShareActivity, problems.firstOrNull() ?: "There was nothing to send", Toast.LENGTH_LONG).show()
            } else {
                // Shown in the app, which opens on the Coordinator.
                if (problems.isNotEmpty()) toast(problems.joinToString("\n"))
                container.shares.share(shared)
                startActivity(MainActivity.ask(this@ShareActivity, Ask.SHARE))
            }
            finish()
        }
    }

    /** What [intent] shares, with its files copied; and why a file was left out. */
    private fun read(intent: Intent): Pair<Shared, List<String>> {
        val problems = ArrayList<String>()
        val text = when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim().orEmpty()
            else -> ShareText.of(intent.getStringExtra(Intent.EXTRA_SUBJECT), intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
        }
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        // Only what another app hands over through a content provider: never a file path, which could name the app's own files.
        val content = uris.filter { it.scheme == ContentResolver.SCHEME_CONTENT && it.authority?.startsWith(packageName) != true }
        if (content.size < uris.size) problems += "Some of it couldn't be read, so it was left out"
        val dir = shareDir()
        val files = content.take(MAX_FILES).mapIndexedNotNull { i, uri ->
            runCatching { copy(uri, File(dir, "$i").apply { mkdirs() }, intent.type) }
                .onFailure { problems += it.message ?: "A file couldn't be read" }
                .getOrNull()
        }
        if (content.size > MAX_FILES) problems += "Only the first $MAX_FILES files go with it"
        return Shared(text, files) to problems
    }

    /** A new folder for this share's files; those of shares days old (never sent on) go. */
    private fun shareDir(): File {
        val root = File(cacheDir, "shared")
        root.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 2L * 24 * 3600 * 1000 }?.forEach { it.deleteRecursively() }
        return File(root, System.currentTimeMillis().toString()).apply { mkdirs() }
    }

    private fun copy(uri: Uri, dir: File, type: String?): SharedFile {
        var name: String? = null
        var size: Long? = null
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val s = c.getColumnIndex(OpenableColumns.SIZE)
                if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
            }
        }
        val fileName = Attachments.safeName(name ?: fallbackName(uri, type))
        if ((size ?: 0) > Attachments.MAX_BYTES) throw IllegalStateException("$fileName is over 25 MB, so it was left out")
        val out = File(dir, fileName)
        val input = contentResolver.openInputStream(uri) ?: throw IllegalStateException("$fileName couldn't be read")
        val copied = input.use { i -> out.outputStream().use { o -> copyAtMost(i, o, Attachments.MAX_BYTES + 1) } }
        if (copied > Attachments.MAX_BYTES) {
            out.delete()
            throw IllegalStateException("$fileName is over 25 MB, so it was left out")
        }
        return SharedFile(fileName, out.path, copied)
    }

    /** A name for a file whose app didn't give one: "shared" with the extension its type has. */
    private fun fallbackName(uri: Uri, type: String?): String {
        val mime = contentResolver.getType(uri) ?: type
        val ext = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        return if (ext != null) "shared.$ext" else "shared"
    }

    private fun copyAtMost(input: java.io.InputStream, output: java.io.OutputStream, limit: Long): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (total < limit) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), limit - total).toInt())
            if (n < 0) break
            output.write(buffer, 0, n)
            total += n
        }
        return total
    }

    private companion object {
        const val MAX_FILES = 20
    }
}
