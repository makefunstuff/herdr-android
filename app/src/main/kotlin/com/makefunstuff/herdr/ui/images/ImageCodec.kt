package com.makefunstuff.herdr.ui.images

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import com.makefunstuff.herdr.core.host.Attachments
import com.makefunstuff.herdr.core.host.ImageSizing
import com.makefunstuff.herdr.data.ImageStore
import com.makefunstuff.herdr.data.TrayImage
import java.io.File
import java.util.UUID

/**
 * Reading and writing pictures on the phone, with Android's [ImageDecoder]: it reads JPEG, PNG,
 * WebP, GIF and HEIC, turns a photo upright by its EXIF orientation, and shrinks it while reading,
 * so a 50-megapixel photo is never held whole. (The screen previews have a stand-in on Skia.)
 */
object ImageCodec {
    /** The picture in [file], at most [maxEdge] pixels on its long side; null when it can't be read. */
    fun load(file: File, maxEdge: Int): ImageBitmap? = decode(ImageDecoder.createSource(file), maxEdge)?.asImageBitmap()

    /** The picture in [bytes] (a PNG an artifact's check took), as [load] reads a file. */
    fun load(bytes: ByteArray, maxEdge: Int): ImageBitmap? = decode(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes)), maxEdge)?.asImageBitmap()

    /** Writes [image] to [file] as [format]. */
    fun save(image: ImageBitmap, file: File, format: ImageSizing.Format) = write(image.asAndroidBitmap(), file, format)

    /**
     * The picture in [source] made ready to send: brought down to [ImageSizing.MAX_EDGE], upright,
     * as PNG (a screenshot) or JPEG (a photo), in a new file of [store]'s tray. Null when it isn't
     * a picture Android can read.
     */
    fun prepare(source: File, mime: String?, name: String, store: ImageStore): TrayImage? =
        prepare(ImageDecoder.createSource(source), mime, name, store)

    internal fun prepare(source: ImageDecoder.Source, mime: String?, name: String, store: ImageStore): TrayImage? {
        val bitmap = decode(source, ImageSizing.MAX_EDGE) ?: return null
        var format = ImageSizing.format(mime, name)
        var file = store.newFile(format.extension)
        write(bitmap, file, format)
        // A screenshot of a photo can make a huge PNG: then it goes as a photo after all.
        if (format == ImageSizing.Format.Png && file.length() > ImageSizing.MAX_PNG_BYTES) {
            file.delete()
            format = ImageSizing.Format.Jpeg
            file = store.newFile(format.extension)
            write(bitmap, file, format)
        }
        val safe = Attachments.safeName(name).let { if (it == "file") "image" else it }
        return TrayImage(UUID.randomUUID().toString(), ImageSizing.rename(safe, format), file.path, bitmap.width, bitmap.height)
    }

    private fun decode(source: ImageDecoder.Source, maxEdge: Int): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val (w, h) = ImageSizing.fit(info.size.width, info.size.height, maxEdge)
            if (w != info.size.width || h != info.size.height) decoder.setTargetSize(w, h)
            // In memory the app can draw on and compress, not only show.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
        }
    }.getOrNull()

    private fun write(bitmap: Bitmap, file: File, format: ImageSizing.Format) {
        // JPEG has no transparency: what was see-through becomes white, not black.
        val opaque = if (format == ImageSizing.Format.Jpeg && bitmap.hasAlpha()) {
            createBitmap(bitmap.width, bitmap.height).also {
                Canvas(it).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(bitmap, 0f, 0f, null) }
            }
        } else bitmap
        file.outputStream().use { out ->
            val ok = when (format) {
                ImageSizing.Format.Png -> opaque.compress(Bitmap.CompressFormat.PNG, 100, out)
                ImageSizing.Format.Jpeg -> opaque.compress(Bitmap.CompressFormat.JPEG, ImageSizing.JPEG_QUALITY, out)
            }
            if (!ok) throw java.io.IOException("Couldn't write the picture")
        }
    }
}
