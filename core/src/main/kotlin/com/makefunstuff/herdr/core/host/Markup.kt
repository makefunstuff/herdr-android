package com.makefunstuff.herdr.core.host

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * What you draw on a picture before it is sent, and the part of it you keep: the geometry of the
 * composer's image editor, apart from how it is drawn. Every point is a fraction of the picture's
 * own width and height (0 to 1, from its top left), so a mark stays where it was drawn whatever
 * size the picture is shown at, cropped or not.
 */
object Markup {
    enum class Tool { Pen, Arrow, Highlight }

    data class Spot(val x: Float, val y: Float)

    /** One stroke: a pen line, an arrow (from its first point to its last) or a highlighter's band; [color] is ARGB. */
    data class Mark(val tool: Tool, val color: Long, val points: List<Spot>)

    /** The part of the picture kept, as fractions of its sides. */
    data class Crop(val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val isWhole: Boolean get() = left <= 0f && top <= 0f && right >= 1f && bottom >= 1f
    }

    /** What a drag on the crop frame takes hold of: a corner, an edge, or the whole frame. */
    enum class Handle { TopLeft, Top, TopRight, Right, BottomRight, Bottom, BottomLeft, Left, Inside }

    /** The smallest part a crop keeps, per side. */
    const val MIN_CROP = 0.08f

    /** How thick a stroke is, as a fraction of the picture's long side, so it looks the same on screen and once sent. */
    fun thickness(tool: Tool): Float = when (tool) {
        Tool.Pen -> 0.008f
        Tool.Arrow -> 0.009f
        Tool.Highlight -> 0.035f
    }

    /** The highlighter's band lets what is under it show through. */
    const val HIGHLIGHT_ALPHA = 0.4f

    /**
     * [crop] after dragging [handle] by [dx], [dy] (fractions of the picture): the frame never
     * leaves the picture and never gets smaller than [MIN_CROP]; [Handle.Inside] moves it whole.
     */
    fun drag(crop: Crop, handle: Handle, dx: Float, dy: Float): Crop {
        if (handle == Handle.Inside) {
            val x = dx.coerceIn(-crop.left, 1f - crop.right)
            val y = dy.coerceIn(-crop.top, 1f - crop.bottom)
            return Crop(crop.left + x, crop.top + y, crop.right + x, crop.bottom + y)
        }
        var (l, t, r, b) = listOf(crop.left, crop.top, crop.right, crop.bottom)
        if (handle in setOf(Handle.TopLeft, Handle.Left, Handle.BottomLeft)) l = (l + dx).coerceIn(0f, r - MIN_CROP)
        if (handle in setOf(Handle.TopRight, Handle.Right, Handle.BottomRight)) r = (r + dx).coerceIn(l + MIN_CROP, 1f)
        if (handle in setOf(Handle.TopLeft, Handle.Top, Handle.TopRight)) t = (t + dy).coerceIn(0f, b - MIN_CROP)
        if (handle in setOf(Handle.BottomLeft, Handle.Bottom, Handle.BottomRight)) b = (b + dy).coerceIn(t + MIN_CROP, 1f)
        return Crop(l, t, r, b)
    }

    /**
     * Which part of the frame [crop], shown at [width]×[height] pixels, a touch at [x], [y] (pixels,
     * from the picture's top left) takes: a corner or an edge within [reach] pixels of it, else the
     * inside, else nothing.
     */
    fun handleAt(crop: Crop, width: Float, height: Float, x: Float, y: Float, reach: Float): Handle? {
        val l = crop.left * width
        val r = crop.right * width
        val t = crop.top * height
        val b = crop.bottom * height
        val nearL = kotlin.math.abs(x - l) <= reach
        val nearR = kotlin.math.abs(x - r) <= reach
        val nearT = kotlin.math.abs(y - t) <= reach
        val nearB = kotlin.math.abs(y - b) <= reach
        val withinX = x >= l - reach && x <= r + reach
        val withinY = y >= t - reach && y <= b + reach
        return when {
            nearL && nearT -> Handle.TopLeft
            nearR && nearT -> Handle.TopRight
            nearL && nearB -> Handle.BottomLeft
            nearR && nearB -> Handle.BottomRight
            nearT && withinX -> Handle.Top
            nearB && withinX -> Handle.Bottom
            nearL && withinY -> Handle.Left
            nearR && withinY -> Handle.Right
            x in l..r && y in t..b -> Handle.Inside
            else -> null
        }
    }

    /**
     * The two ends of an arrow's head at ([x2], [y2]), coming from ([x1], [y1]), in the same units:
     * wings [size] long at 28° each side of the shaft. Null for an arrow too short to point anywhere.
     */
    fun arrowHead(x1: Float, y1: Float, x2: Float, y2: Float, size: Float): Pair<Spot, Spot>? {
        if (hypot(x2 - x1, y2 - y1) < size / 4f) return null
        val angle = atan2(y2 - y1, x2 - x1)
        val spread = Math.toRadians(28.0).toFloat()
        fun wing(a: Float) = Spot(x2 - size * cos(a), y2 - size * sin(a))
        return wing(angle - spread) to wing(angle + spread)
    }

    /** [point] added to a stroke of [points] unless it is within [minStep] of the last one: a finger's jitter. */
    fun extend(points: List<Spot>, point: Spot, minStep: Float): List<Spot> {
        val last = points.lastOrNull() ?: return listOf(point)
        return if (hypot(point.x - last.x, point.y - last.y) < minStep) points else points + point
    }

    /**
     * The picture's pixels that [crop] keeps of a [width]×[height] picture: left, top, width and
     * height, whole pixels and at least one each way.
     */
    fun cropPixels(crop: Crop, width: Int, height: Int): IntArray {
        val l = (crop.left * width).toInt().coerceIn(0, width - 1)
        val t = (crop.top * height).toInt().coerceIn(0, height - 1)
        val r = (crop.right * width).toInt().coerceIn(l + 1, width)
        val b = (crop.bottom * height).toInt().coerceIn(t + 1, height)
        return intArrayOf(l, t, r - l, b - t)
    }
}
