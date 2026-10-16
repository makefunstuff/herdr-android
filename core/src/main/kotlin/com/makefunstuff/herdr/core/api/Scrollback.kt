package com.makefunstuff.herdr.core.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Where a pane's viewport sits in its scrollback. herdr counts from the bottom: 0 is the live
 * screen, [maxOffsetFromBottom] the oldest line.
 *
 * Text points (search, motions, selections) use absolute rows instead: row 0 is the oldest line
 * kept, and the last row is the bottom of the live screen.
 */
@Serializable
data class PaneScroll(
    @SerialName("offset_from_bottom") val offsetFromBottom: Long = 0,
    @SerialName("max_offset_from_bottom") val maxOffsetFromBottom: Long = 0,
    @SerialName("viewport_rows") val viewportRows: Long = 0,
) {
    /** Rows in the scrollback plus the screen. */
    val totalRows: Long get() = maxOffsetFromBottom + viewportRows

    /** The absolute row at the top of the viewport. */
    val viewportTop: Long get() = (totalRows - viewportRows - offsetFromBottom).coerceAtLeast(0)

    val atBottom: Boolean get() = offsetFromBottom == 0L

    /** The absolute row of viewport row [viewportRow]. */
    fun absoluteRow(viewportRow: Int): Long = viewportTop + viewportRow

    /** The viewport row showing absolute [row], or null when it's off screen. */
    fun viewportRow(row: Long): Int? = (row - viewportTop).takeIf { it in 0 until viewportRows }?.toInt()

    /** The offset that brings absolute [row] into view, about a third of the way down. */
    fun offsetToShow(row: Long): Long {
        if (viewportRow(row) != null) return offsetFromBottom
        val top = (row - viewportRows / 3).coerceAtLeast(0)
        return (totalRows - viewportRows - top).coerceIn(0, maxOffsetFromBottom)
    }
}

@Serializable
data class TextPoint(val row: Long, val col: Int) : Comparable<TextPoint> {
    override fun compareTo(other: TextPoint): Int = compareValuesBy(this, other, { it.row }, { it.col })
}

@Serializable
data class TextRange(val start: TextPoint, val end: TextPoint)

/** Cursor motions herdr's copy mode knows, as in vi. */
enum class CopyMotion(val wire: String) {
    LineEnd("line_end"),
    FirstNonBlank("first_non_blank"),
    NextWordStart("next_word_start"),
    PreviousWordStart("previous_word_start"),
    NextWordEnd("next_word_end"),
    NextBigWordStart("next_big_word_start"),
    PreviousBigWordStart("previous_big_word_start"),
    NextBigWordEnd("next_big_word_end"),
    PreviousParagraph("previous_paragraph"),
    NextParagraph("next_paragraph"),
}

@Serializable
data class CopyMotionResult(
    val cursor: TextPoint,
    @SerialName("content_revision") val contentRevision: Long = 0,
)

@Serializable
data class CopySearchResult(
    @SerialName("content_revision") val contentRevision: Long = 0,
    /** Matches near the cursor; [current] indexes into these. */
    val matches: List<TextRange> = emptyList(),
    val total: Long = 0,
    val current: Int? = null,
    /** The current match's place among all [total] matches. */
    @SerialName("current_global") val currentGlobal: Long? = null,
) {
    val currentMatch: TextRange? get() = current?.let { matches.getOrNull(it) }
}

/** Inclusive columns of a link on a viewport row. */
@Serializable
data class LinkRegion(
    val row: Int,
    @SerialName("start_col") val startCol: Int,
    @SerialName("end_col") val endCol: Int,
)

private val URL_RE = Regex("""(?:https?://|www\.)[^\s<>"'`)\]}]+""")

/**
 * The URL under [col] on a terminal [line], or null. herdr's `pane.link.resolve` needs the pane
 * to be visible, so this reads the phone's own copy of the screen when it isn't.
 */
fun urlAt(line: String, col: Int): String? {
    val match = URL_RE.findAll(line).firstOrNull { col in it.range.first..it.range.last } ?: return null
    return match.value.let { if (it.startsWith("www.")) "https://$it" else it }
}

@Serializable
data class LinkActivation(
    /** The link's target, for the client to open, when no herdr plugin handled it. */
    val url: String? = null,
    /** True when a link handler on the host already acted on it. */
    val handled: Boolean = false,
)

/**
 * Searches one pane's scrollback with `pane.copy_search`. herdr pins each search to a content
 * revision, and new output changes it; a stale revision is refreshed and the search retried.
 */
class ScrollbackSearch(private val api: HerdrApi, val paneId: String) {
    var query: String = ""
        private set
    var result: CopySearchResult? = null
        private set
    private var revision: Long? = null

    /**
     * Finds the match nearest the bottom of the screen for a new [query], or the next one
     * ([backward] = older) after the current match when the query is unchanged.
     */
    suspend fun find(query: String, backward: Boolean = true): CopySearchResult {
        val again = query == this.query && result?.currentMatch != null
        val previous = if (again) result?.currentMatch else null
        this.query = query
        var attempt = 0
        while (true) {
            val rev = revision ?: api.contentRevision(paneId).also { revision = it }
            val from = previous?.start ?: bottom()
            try {
                val found = api.copySearch(paneId, query, backward, from, rev, previous)
                revision = found.contentRevision
                result = found
                return found
            } catch (e: HerdrApiException) {
                if (e.code != "stale_content" || attempt++ >= 2) throw e
                revision = null
            }
        }
    }

    fun reset() {
        query = ""
        result = null
        revision = null
    }

    private suspend fun bottom(): TextPoint {
        val scroll = api.pane(paneId).scroll ?: PaneScroll()
        return TextPoint((scroll.totalRows - 1).coerceAtLeast(0), 0)
    }
}
