package com.makefunstuff.herdr.core.chat

/**
 * What the composer completes as you type: a `/command` at the start of the message, and an
 * `@path` anywhere in it. Pure text work, so the screen only says which list to show.
 */
object Completions {
    enum class Kind { Command, File }

    /** The word being completed: [text] (without its `/` or `@`) runs over [start, end) of the message. */
    data class Query(val kind: Kind, val text: String, val start: Int, val end: Int)

    /**
     * The word at [cursor] when it asks for a completion: `/` and no space yet at the very start of the
     * message (an agent reads a command only there), or `@` at the start of a word. Null otherwise,
     * also for a selection.
     */
    fun at(message: String, cursor: Int, selectionEnd: Int = cursor): Query? {
        if (cursor != selectionEnd || cursor < 1 || cursor > message.length) return null
        var start = cursor
        while (start > 0 && !message[start - 1].isWhitespace()) start--
        var end = cursor
        while (end < message.length && !message[end].isWhitespace()) end++
        val word = message.substring(start, cursor)
        return when {
            word.startsWith("/") && start == 0 && !word.drop(1).contains('/') -> Query(Kind.Command, word.drop(1), start, end)
            word.startsWith("@") -> Query(Kind.File, word.drop(1), start, end)
            else -> null
        }
    }

    /** [message] with the [query]'s word replaced by [replacement] and a space, and where the cursor goes. */
    fun accept(message: String, query: Query, replacement: String): Pair<String, Int> {
        val after = message.substring(query.end)
        val gap = if (after.startsWith(" ")) "" else " "
        val text = message.substring(0, query.start) + replacement + gap + after
        return text to (query.start + replacement.length + 1).coerceAtMost(text.length)
    }

    /**
     * The files that match [query] best, up to [limit], as the herdr web UI ranks them: the query
     * inside the path (earlier is better, more so in the file's own name), then its letters in
     * order in the name, then in the whole path. An empty query keeps the listing's order.
     */
    fun files(paths: List<String>, query: String, limit: Int = 20): List<String> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return paths.take(limit)
        return paths.asSequence()
            .map { it to score(it, q) }
            .filter { it.second >= 0 }
            .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
            .take(limit)
            .map { it.first }
            .toList()
    }

    private fun score(path: String, q: String): Double {
        val lower = path.lowercase()
        val name = lower.substringAfterLast('/')
        val at = lower.indexOf(q)
        if (at >= 0) return 300.0 - at + (if (name.contains(q)) 30 else 0)
        if (inOrder(name, q)) return 200.0 - name.length
        if (inOrder(lower, q)) return 100.0 - lower.length / 1000.0
        return -1.0
    }

    private fun inOrder(text: String, q: String): Boolean {
        var i = 0
        for (ch in text) if (i < q.length && ch == q[i]) i++
        return i == q.length
    }

    /** The commands whose name starts with [query], then those that have it inside; at most [limit]. */
    fun commands(all: List<AgentCommand>, query: String, limit: Int = 30): List<AgentCommand> {
        val q = query.lowercase()
        if (q.isEmpty()) return all.take(limit)
        val starts = all.filter { it.name.lowercase().startsWith(q) }
        val inside = all.filter { it !in starts && it.name.lowercase().contains(q) }
        return (starts + inside).take(limit)
    }
}
