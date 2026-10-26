package com.makefunstuff.herdr.core.host

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pictures you send an agent from the phone (a photo, a screenshot). herdr takes only text and
 * keys, so a picture goes the way [Attachments] sends any file: copied to the agent's machine and
 * named in the message as an `@path` mention, which Claude Code reads as the image itself and the
 * other agents open with their own image tool. This is what a message with pictures looks like to
 * the chat: the mentions that name pictures, and the words around them.
 */
object Images {
    /** What the composer treats as a picture: what the agents' image tools read, and what phones save. */
    val EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "heic", "heif", "bmp")

    /** The most a picture is fetched back from the machine, to show it in the chat. */
    const val MAX_FETCH_BYTES = 12L * 1024 * 1024

    fun isImage(name: String): Boolean = name.substringAfterLast('/').substringAfterLast('.', "").lowercase() in EXTENSIONS

    /** A message's pictures ([paths], in order) and its words without their mentions ([text]). */
    data class Split(val paths: List<String>, val text: String)

    /**
     * Takes the mentions of pictures out of [text]. Only a picture on the machine by its full path
     * (`/…` or `~/…`) counts, as only that one can be shown; any other mention stays in the words.
     */
    fun split(text: String): Split = Attachments.split(text, ::isImage).let { (paths, words) -> Split(paths, words) }

    /**
     * Prints the picture at [path] on the machine as base64, when it is a file of at most [maxBytes];
     * otherwise prints nothing and fails. A path under `~/` is the home folder's.
     */
    fun fetchCommand(path: String, maxBytes: Long = MAX_FETCH_BYTES): String = Attachments.fetchCommand(path, maxBytes)
}

/**
 * How a picture is made smaller before it is copied: phone photos are 12 to 50 megapixels, and the
 * agents look at about two thousand pixels on the long side at most (Claude scales anything larger
 * down itself). Screenshots stay PNG so their text keeps sharp edges; photos become JPEG.
 */
object ImageSizing {
    /** The long side a picture is brought down to. */
    const val MAX_EDGE = 2048

    /** JPEG's quality for a photo: no visible loss, a tenth of the size. */
    const val JPEG_QUALITY = 85

    /** A PNG larger than this (a big screenshot of a photo) is sent as JPEG after all. */
    const val MAX_PNG_BYTES = 3L * 1024 * 1024

    enum class Format(val extension: String, val mime: String) { Png("png", "image/png"), Jpeg("jpg", "image/jpeg") }

    /** [width]×[height] brought down to at most [maxEdge] on the long side, keeping its shape; never up. */
    fun fit(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Pair<Int, Int> {
        val long = max(width, height)
        if (long <= maxEdge || long <= 0) return width to height
        val scale = maxEdge.toDouble() / long
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    /**
     * The power of two a decoder may shrink [width]×[height] by while reading, so it never holds a
     * whole 50-megapixel photo, and still has at least [maxEdge] on the long side to scale from.
     */
    fun sampleSize(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Int {
        val long = max(width, height)
        var sample = 1
        while (long / (sample * 2) >= maxEdge) sample *= 2
        return sample
    }

    /**
     * What a picture of type [mime] (or, without one, with that [name]) is sent as: PNG for what is
     * drawn rather than shot (PNG, WebP, GIF, BMP: screenshots, diagrams), JPEG for photos
     * (JPEG, HEIC, which the agents can't open).
     */
    fun format(mime: String?, name: String): Format {
        val type = mime?.lowercase()?.substringAfter("image/", "")?.takeIf { it.isNotEmpty() }
            ?: name.substringAfterLast('.', "").lowercase()
        return when (type) {
            "png", "webp", "gif", "bmp", "x-ms-bmp" -> Format.Png
            else -> Format.Jpeg
        }
    }

    /** [name] with the extension of [format]: `IMG_2041.HEIC` is sent as `IMG_2041.jpg`. */
    fun rename(name: String, format: Format): String {
        val base = name.substringAfterLast('/').let { if ('.' in it.drop(1)) it.substringBeforeLast('.') else it }.ifEmpty { "image" }
        return "$base.${format.extension}"
    }
}
