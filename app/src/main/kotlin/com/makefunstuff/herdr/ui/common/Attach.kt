package com.makefunstuff.herdr.ui.common

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.makefunstuff.herdr.core.host.Attachments
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.core.host.Images
import com.makefunstuff.herdr.data.ImageStore
import com.makefunstuff.herdr.data.TrayImage
import com.makefunstuff.herdr.ui.images.ImageCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The composer's ways to attach: [pick] any file, [photos] from the gallery, [camera] a new photo,
 * [screenshot] the newest one. [uploading] while a file is copied or a picture is made ready.
 */
class Attacher(
    val uploading: Boolean,
    val pick: () -> Unit,
    val photos: () -> Unit = pick,
    val camera: () -> Unit = pick,
    val screenshot: () -> Unit = pick,
)

/**
 * Attaching to a chat message. A file picked on the phone is copied to [session]'s machine over
 * SSH ([HostSession.upload]) and [onAttached] gets its path there, for the draft to name it
 * (`@path`, [Attachments.withMention]); up to 20 at a time, each at most [Attachments.MAX_BYTES],
 * and one that can't be copied says why while the others still go.
 *
 * A picture (from the camera, the gallery, the newest screenshot, or a picked file that is one) is
 * made smaller first ([ImageCodec.prepare]) and goes to [onImage], the composer's tray, which shows
 * it before it is sent and copies it itself; it answers false when the message is full.
 */
@Composable
fun rememberAttacher(session: HostSession, onAttached: (path: String) -> Unit, onImage: ((TrayImage) -> Boolean)? = null): Attacher {
    val context = LocalContext.current
    val store = LocalContainer.current.images
    val scope = rememberCoroutineScope()
    var busy by remember { mutableIntStateOf(0) }
    val attached by rememberUpdatedState(onAttached)
    val image by rememberUpdatedState(onImage)

    /** Pictures read from [uris] into the tray, made smaller; each one that can't be says why. */
    fun addPictures(uris: List<Uri>, deleteAfter: File? = null) {
        val take = image ?: return
        if (uris.isEmpty()) return
        busy++
        scope.launch {
            for (uri in uris) {
                val (name, _) = withContext(Dispatchers.IO) { describe(context, uri) }
                val ready = withContext(Dispatchers.IO) {
                    runCatching { ImageCodec.prepare(ImageDecoder.createSource(context.contentResolver, uri), context.contentResolver.getType(uri), name, store) }.getOrNull()
                }
                when {
                    ready == null -> context.toast("$name isn't a picture the phone can read")
                    !take(ready) -> { context.toast("A message takes at most ${ImageStore.MAX_IMAGES} pictures"); break }
                }
            }
            deleteAfter?.delete()
            busy--
        }
    }

    val files = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        if (uris.size > MAX_FILES) context.toast("Attached the first $MAX_FILES files")
        val (pictures, others) = uris.take(MAX_FILES).partition { uri ->
            image != null && (context.contentResolver.getType(uri)?.startsWith("image/") == true || Images.isImage(describe(context, uri).first))
        }
        addPictures(pictures)
        if (others.isEmpty()) return@rememberLauncherForActivityResult
        busy++
        scope.launch {
            for (uri in others) {
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
            busy--
        }
    }

    // The gallery: Android's photo picker, which needs no permission.
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(ImageStore.MAX_IMAGES)) { uris -> addPictures(uris) }
    fun openGallery() = gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    // The camera app takes the photo into a file of ours, which then goes like a picked one.
    var shot by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { _ ->
        val file = shot?.let(::File) ?: return@rememberLauncherForActivityResult
        shot = null
        // Some camera apps answer "cancelled" after writing the photo: the file says what happened.
        if (file.length() > 0) addPictures(listOf(Uri.fromFile(file)), deleteAfter = file) else file.delete()
    }
    fun takePhoto() {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        // A photo the camera never gave back (the app was closed meanwhile) isn't kept.
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "Photo_${Attachments.stamp()}.jpg")
        shot = file.path
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        try {
            camera.launch(uri)
        } catch (e: ActivityNotFoundException) {
            shot = null
            context.toast("This phone has no camera app to take a photo with")
        }
    }

    // The newest screenshot, found in the phone's pictures: that needs leave to see them.
    fun attachLatest() {
        scope.launch {
            val found = withContext(Dispatchers.IO) { latestScreenshot(context) }
            if (found == null) {
                context.toast("There's no screenshot on this phone yet. Pick one from Photos.")
                openGallery()
            } else addPictures(listOf(found))
        }
    }
    // Android 14 and newer can give leave for only the photos chosen then ("Select photos"): the newest
    // screenshot among those is taken, and the next tap asks again, to choose others.
    val permissions = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) attachLatest() else {
            context.toast("Without access to your pictures the app can't find the newest screenshot. Pick it from Photos.")
            openGallery()
        }
    }

    return Attacher(
        uploading = busy > 0,
        pick = { files.launch("*/*") },
        photos = ::openGallery,
        camera = ::takePhoto,
        screenshot = {
            if (ContextCompat.checkSelfPermission(context, permissions.first()) == PackageManager.PERMISSION_GRANTED) attachLatest() else ask.launch(permissions)
        },
    )
}

private const val MAX_FILES = 20

/** The newest picture in the phone's Screenshots folder, or null. */
private fun latestScreenshot(context: Context): Uri? {
    val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val (where, args) = if (Build.VERSION.SDK_INT >= 29) {
        "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?" to arrayOf("%Screenshots%")
    } else {
        "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?" to arrayOf("Screenshots")
    }
    return runCatching {
        context.contentResolver.query(
            collection, arrayOf(MediaStore.Images.Media._ID), where, args, "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null }
    }.getOrNull()
}

/** The picked file's name and size, as the provider gives them. */
private fun describe(context: Context, uri: Uri): Pair<String, Long?> {
    if (uri.scheme == "file") return (uri.lastPathSegment ?: "file") to uri.path?.let { File(it).length() }
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
