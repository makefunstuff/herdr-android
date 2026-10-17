package com.makefunstuff.herdr.core.code

/** A checkout's files as folders: built once from the flat list, then read a folder at a time. */
class FileTree(val paths: List<String>) {
    data class Entry(val name: String, val path: String, val folder: Boolean, /** Files under a folder, at any depth. */ val count: Int = 0)

    private val children: Map<String, List<Entry>>

    init {
        val folders = HashMap<String, HashMap<String, Int>>()
        val files = HashMap<String, ArrayList<String>>()
        for (p in paths) {
            val parts = p.split('/')
            var dir = ""
            for (k in 0 until parts.size - 1) {
                val sub = if (dir.isEmpty()) parts[k] else "$dir/${parts[k]}"
                folders.getOrPut(dir) { HashMap() }.merge(sub, 1, Int::plus)
                dir = sub
            }
            files.getOrPut(dir) { ArrayList() } += p
        }
        val keys = folders.keys + files.keys
        children = keys.associateWith { dir ->
            val subs = folders[dir].orEmpty().map { (path, count) -> Entry(path.substringAfterLast('/'), path, true, count) }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            val own = files[dir].orEmpty().map { Entry(it.substringAfterLast('/'), it, false) }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            subs + own
        }
    }

    /** What [folder] holds ("" is the top): its folders first, then its files, each by name. */
    fun list(folder: String): List<Entry> = children[folder].orEmpty()

    fun isFolder(path: String): Boolean = path.isEmpty() || children.containsKey(path)

    val size: Int get() = paths.size
}

/**
 * "Go to file": the files whose path has the typed letters in order, best first. A match in the
 * file's own name beats one spread over its folders; letters next to each other and at the start of
 * a word count more. What is typed with a `/` matches the whole path.
 */
object QuickOpen {
    data class Match(val path: String, val score: Int, /** Indexes in [path] of the letters that matched, for bold. */ val at: List<Int>)

    fun find(paths: List<String>, query: String, limit: Int = 60): List<Match> {
        val q = query.trim().replace('\\', '/')
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<Match>()
        for (p in paths) score(p, q)?.let { out += it }
        return out.sortedWith(compareByDescending<Match> { it.score }.thenBy { it.path.length }.thenBy { it.path }).take(limit)
    }

    fun score(path: String, query: String): Match? {
        val nameStart = path.lastIndexOf('/') + 1
        val inName = if ('/' !in query) match(path, query, nameStart) else null
        val whole = inName ?: match(path, query, 0) ?: return null
        val bonus = if (inName != null) 60 else 0
        val exact = if (path.substring(nameStart).equals(query, ignoreCase = true)) 100 else 0
        val prefix = if (inName != null && path.startsWith(query, nameStart, ignoreCase = true)) 40 else 0
        return Match(path, whole.first + bonus + exact + prefix - path.count { it == '/' }, whole.second)
    }

    /** The letters of [q] in [s] from [from] on, in order: a score and where they matched. */
    private fun match(s: String, q: String, from: Int): Pair<Int, List<Int>>? = match(s, q, from, prefer = true) ?: match(s, q, from, prefer = false)

    private fun match(s: String, q: String, from: Int, prefer: Boolean): Pair<Int, List<Int>>? {
        val at = ArrayList<Int>(q.length)
        var score = 0
        var j = from
        var last = -2
        for (c in q) {
            if (c == ' ') continue
            var found = -1
            // Prefer the letter at a word's start ahead, else the next one.
            var k = j
            while (k < s.length) {
                if (s[k].equals(c, ignoreCase = true)) {
                    if (found < 0) found = k
                    if (!prefer) break
                    if (startsWord(s, k)) { found = k; break }
                    if (k == last + 1) break
                }
                k++
            }
            if (found < 0) return null
            score += when {
                found == last + 1 -> 8
                startsWord(s, found) -> 6
                else -> 1
            }
            at += found
            last = found
            j = found + 1
        }
        return score to at
    }

    private fun startsWord(s: String, i: Int): Boolean =
        i == 0 || s[i - 1] in "/._-" || (s[i].isUpperCase() && s[i - 1].isLowerCase())
}
