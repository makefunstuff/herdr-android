package com.makefunstuff.herdr.core.chat

/**
 * Where a chat stays, the same in every chat of the app (an agent's, both Coordinators'): it opens at
 * the newest message, and every new message brings the reader down, also from further up. At the end
 * it follows whatever grows there (an answer being written, a picture loading, the working line);
 * scrolled up, only a new message moves it. Sending brings the reader down too ([sent]).
 *
 * The rules alone, on message keys: the app wires them to its list (`rememberStickToBottom`).
 */
data class StickToBottom(
    /** The messages last seen, by key; null before the first. */
    val known: Set<String>? = null,
    /** The messages there when the reader left the end; null while at it. */
    val since: Set<String>? = null,
    /** The first message that came in while the reader was up: a New line goes above it, until they write. */
    val unread: String? = null,
) {
    /** What the list does after [messages] (their keys, oldest first) came in: whether it goes to the end. */
    class Seen(val next: StickToBottom, val toEnd: Boolean)

    /** [messages] as they are now, the reader [atEnd] or not. */
    fun seen(messages: List<String>, atEnd: Boolean): Seen {
        if (messages.isEmpty()) return Seen(copy(known = emptySet()), toEnd = false)
        val before = known
        val now = copy(known = messages.toSet())
        // Opened, or the first messages loaded: at the newest.
        if (before.isNullOrEmpty()) return Seen(now, toEnd = true)
        val new = messages.firstOrNull { it !in before }
        return when {
            new == null -> Seen(now, toEnd = atEnd)
            atEnd -> Seen(now, toEnd = true)
            // Up the chat: down to the newest, a New line above the first message the reader didn't see come.
            else -> Seen(now.copy(unread = since?.let { s -> messages.firstOrNull { it !in s } } ?: new), toEnd = true)
        }
    }

    /** The reader got to the end, or left it, with [messages] there. */
    fun moved(atEnd: Boolean, messages: List<String>): StickToBottom =
        if (atEnd) copy(since = null) else if (since == null) copy(since = messages.toSet()) else this

    /** How many of [messages] came in since the reader left the end: the count on the ↓. */
    fun fresh(messages: List<String>): Int = since?.let { s -> messages.count { it !in s } } ?: 0

    /** You wrote: the chat goes to its end, and what was new is behind you. */
    fun sent(): StickToBottom = copy(since = null, unread = null)

    companion object {
        /** About 80 px from the end still counts as at it, as in Shepherdr. */
        const val SLOP_DP = 32

        /**
         * At the end of a reversed list (the newest is item 0): its first item shows, scrolled up no
         * more than [slop] px.
         */
        fun atEnd(firstIndex: Int, firstOffset: Int, slop: Int): Boolean = firstIndex == 0 && firstOffset <= slop
    }
}
