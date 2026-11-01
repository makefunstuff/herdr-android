// Stand-in for app/…/ui/images/ImageCodec.kt (Android's ImageDecoder and Bitmap.compress): the same
// pictures read and written with Skia, which Compose on the desktop draws with.
package com.makefunstuff.herdr.ui.images

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.makefunstuff.herdr.core.host.Attachments
import com.makefunstuff.herdr.core.host.ImageSizing
import com.makefunstuff.herdr.data.ImageStore
import com.makefunstuff.herdr.data.TrayImage
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File

object ImageCodec {
    fun load(file: File, maxEdge: Int): ImageBitmap? = runCatching { file.readBytes() }.getOrNull()?.let { decode(it, maxEdge) }?.toComposeImageBitmap()

    fun load(bytes: ByteArray, maxEdge: Int): ImageBitmap? = decode(bytes, maxEdge)?.toComposeImageBitmap()

    fun save(image: ImageBitmap, file: File, format: ImageSizing.Format) =
        write(Image.makeFromBitmap(image.asSkiaBitmap()), file, format)

    fun prepare(source: File, mime: String?, name: String, store: ImageStore): TrayImage? {
        val image = runCatching { source.readBytes() }.getOrNull()?.let { decode(it, ImageSizing.MAX_EDGE) } ?: return null
        val format = ImageSizing.format(mime, name)
        val file = store.newFile(format.extension)
        write(image, file, format)
        val safe = Attachments.safeName(name).let { if (it == "file") "image" else it }
        // A fixed id per file name, so a scene renders the same every time.
        return TrayImage("shared-$safe", ImageSizing.rename(safe, format), file.path, image.width, image.height)
    }

    private fun decode(bytes: ByteArray, maxEdge: Int): Image? = runCatching {
        val full = Image.makeFromEncoded(bytes)
        val (w, h) = ImageSizing.fit(full.width, full.height, maxEdge)
        if (w == full.width && h == full.height) return@runCatching full
        val surface = Surface.makeRasterN32Premul(w, h)
        surface.canvas.drawImageRect(full, Rect.makeWH(w.toFloat(), h.toFloat()))
        surface.makeImageSnapshot()
    }.getOrNull()

    private fun write(image: Image, file: File, format: ImageSizing.Format) {
        val data = when (format) {
            ImageSizing.Format.Png -> image.encodeToData(EncodedImageFormat.PNG)
            ImageSizing.Format.Jpeg -> image.encodeToData(EncodedImageFormat.JPEG, ImageSizing.JPEG_QUALITY)
        } ?: throw java.io.IOException("Couldn't write the picture")
        file.writeBytes(data.bytes)
    }
}
