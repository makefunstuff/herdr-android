package com.makefunstuff.herdr.core.chat

/**
 * Matching a message the app sent to the copy the transcript, screen or board shows later.
 * Matching is per message: a sent message is confirmed by the n-th matching message beyond those
 * already there when it was sent, so identical messages sent on purpose stay separate.
 */
object SentMatch {
    private const val HEAD = 200
    private const val TRUNCATED = 100

    /** Whitespace collapsed and Markdown emphasis marks removed. */
    fun normalize(s: String): String =
        s.replace(Regex("[*_`>]+"), "").replace(Regex("\\s+"), " ").trim()

    /**
     * Whether [seen] is [sent] as the channel recorded it: equal once normalised, or one a prefix of
     * the other (the board appends lines, a channel truncates).
     */
    fun same(sent: String, seen: String): Boolean {
        val a = normalize(sent)
        val b = normalize(seen)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a.take(HEAD) == b.take(HEAD) && (a.length >= HEAD || b.length >= HEAD || a == b)) return true
        val (short, long) = if (a.length <= b.length) a to b else b to a
        if (!long.startsWith(short)) return false
        val rest = long.substring(short.length).trim()
        // Lines the board appends, in brackets; or a long message cut short.
        return (rest.startsWith("[") && rest.endsWith("]")) || short.length >= TRUNCATED
    }

    /** How many of [seen] are [sent]. */
    fun count(sent: String, seen: List<String>): Int = seen.count { same(sent, it) }

    /** Occurrences of the start of [sent] in a screen's text. */
    fun countOnScreen(sent: String, screen: String): Int {
        // A terminal can wrap in the middle of a word: compare without any whitespace.
        val head = normalize(sent).filterNot { it.isWhitespace() }.take(40)
        if (head.isEmpty()) return 0
        val text = screen.filterNot { it.isWhitespace() || it in "*_`>" }
        var n = 0
        var i = text.indexOf(head)
        while (i >= 0) { n++; i = text.indexOf(head, i + head.length) }
        return n
    }

    /** A sent message waiting to be seen; [baseline] is how many like it were already shown when it was sent. */
    data class Pending(
        val text: String,
        val baseline: Int,
        /** Ids of the user messages in the transcript when it was sent: one not among them is new. */
        val known: Set<String> = emptySet(),
        val sentAt: Long = 0,
    )

    /**
     * What to report as the shown count for [p] against a window that can slide: it is taken as
     * confirmed ([Int.MAX_VALUE]) when the matches fell in number (older copies scrolled out as
     * new text came in), or when a copy has stood there a while after the send.
     */
    fun slid(p: Pending, count: Int, now: Long, graceMs: Long = 30_000): Int = when {
        count > p.baseline -> count
        count in 1 until p.baseline -> Int.MAX_VALUE
        count >= 1 && p.sentAt > 0 && now - p.sentAt > graceMs -> Int.MAX_VALUE
        else -> count
    }

    /** The pending messages not yet confirmed, where [counted] says how many matching messages are shown. */
    fun unconfirmed(pending: List<Pending>, counted: (Pending) -> Int): List<Pending> {
        val nth = HashMap<String, Int>()
        return pending.filter { p ->
            val key = normalize(p.text).take(HEAD)
            val k = (nth[key] ?: 0) + 1
            nth[key] = k
            counted(p) < p.baseline + k
        }
    }
}
