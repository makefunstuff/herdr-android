package com.makefunstuff.herdr.core.chat

/**
 * Matching a message the app sent to the copy the transcript, screen or board shows later.
 * Matching is per message: a sent message is confirmed by the n-th matching message beyond those
 * already there when it was sent, so identical messages sent on purpose stay separate.
 */
object SentMatch {
    private const val HEAD = 200
    private const val MIN_PREFIX = 20

    /** Whitespace collapsed and Markdown emphasis marks removed. */
    fun normalize(s: String): String =
        s.replace(Regex("[*_`>]+"), "").replace(Regex("\\s+"), " ").trim()

    /**
     * Whether [seen] is [sent] as the channel recorded it: equal once normalised, or one a prefix of
     * the other (the board appends lines, a channel truncates).
     */
    fun same(sent: String, seen: String): Boolean {
        val a = normalize(sent).take(HEAD)
        val b = normalize(seen).take(HEAD)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        val (short, long) = if (a.length <= b.length) a to b else b to a
        return short.length >= MIN_PREFIX && long.startsWith(short)
    }

    /** How many of [seen] are [sent]. */
    fun count(sent: String, seen: List<String>): Int = seen.count { same(sent, it) }

    /** Occurrences of the start of [sent] in a screen's text. */
    fun countOnScreen(sent: String, screen: String): Int {
        val head = normalize(sent).take(40)
        if (head.isEmpty()) return 0
        val text = normalize(screen)
        var n = 0
        var i = text.indexOf(head)
        while (i >= 0) { n++; i = text.indexOf(head, i + head.length) }
        return n
    }

    /** A sent message waiting to be seen; [baseline] is how many like it were already shown when it was sent. */
    data class Pending(val text: String, val baseline: Int)

    /** The pending messages not yet confirmed, where [counted] says how many matching messages are shown. */
    fun unconfirmed(pending: List<Pending>, counted: (String) -> Int): List<Pending> {
        val nth = HashMap<String, Int>()
        return pending.filter { p ->
            val key = normalize(p.text).take(HEAD)
            val k = (nth[key] ?: 0) + 1
            nth[key] = k
            counted(p.text) < p.baseline + k
        }
    }
}
