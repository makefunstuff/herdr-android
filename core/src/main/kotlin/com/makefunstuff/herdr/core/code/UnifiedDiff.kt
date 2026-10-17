package com.makefunstuff.herdr.core.code

/** One line of a hunk, with its numbers in the old and the new file (null where it isn't in that side). */
data class DiffLine(val kind: Kind, val old: Int?, val new: Int?, val text: String) {
    enum class Kind { Context, Added, Removed, /** "\ No newline at end of file". */ Note }
}

data class Hunk(val header: String, val oldStart: Int, val newStart: Int, val lines: List<DiffLine>) {
    /** What Git printed after the second `@@`: the function or heading the hunk is in. */
    val section: String get() = header.substringAfter("@@", "").substringAfter("@@", "").trim()
}

/** What happened to one file. */
enum class FileChange { Added, Deleted, Renamed, Copied, Modified }

data class FileDiff(
    val oldPath: String?,
    val newPath: String?,
    val change: FileChange,
    val binary: Boolean,
    val hunks: List<Hunk>,
    /** A mode change only, such as a script made executable. */
    val modeOnly: Boolean = false,
) {
    /** The file's name now (its old one when it was deleted). */
    val path: String get() = newPath ?: oldPath.orEmpty()
    val added: Int get() = hunks.sumOf { h -> h.lines.count { it.kind == DiffLine.Kind.Added } }
    val removed: Int get() = hunks.sumOf { h -> h.lines.count { it.kind == DiffLine.Kind.Removed } }
}

/** Reads `git diff` / `git show` output (the unified format with Git's extended headers) into files and hunks. */
object UnifiedDiff {
    private val HUNK = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@""")

    fun parse(text: String): List<FileDiff> {
        val files = ArrayList<FileDiff>()
        var cur: Builder? = null
        var hunk: HunkBuilder? = null
        var oldLeft = 0
        var newLeft = 0

        fun endHunk() {
            val c = cur
            val h = hunk
            if (c != null && h != null) c.hunks += Hunk(h.header, h.oldStart, h.newStart, h.lines)
            hunk = null
        }

        fun endFile() {
            endHunk()
            cur?.let { files += it.build() }
            cur = null
        }

        for (raw in text.split('\n')) {
            val line = raw.removeSuffix("\r")
            val h = hunk
            // Inside a hunk, its counts say how many lines are still its own (a "-- " line is code, not a header).
            if (h != null && (oldLeft > 0 || newLeft > 0)) {
                when {
                    line.startsWith("+") -> { h.lines += DiffLine(DiffLine.Kind.Added, null, h.new++, line.substring(1)); newLeft--; continue }
                    line.startsWith("-") -> { h.lines += DiffLine(DiffLine.Kind.Removed, h.old++, null, line.substring(1)); oldLeft--; continue }
                    line.startsWith(" ") || line.isEmpty() -> {
                        h.lines += DiffLine(DiffLine.Kind.Context, h.old++, h.new++, line.drop(1)); oldLeft--; newLeft--; continue
                    }
                    line.startsWith("\\") -> { h.lines += DiffLine(DiffLine.Kind.Note, null, null, line.drop(1).trim()); continue }
                }
            }
            if (h != null && line.startsWith("\\")) { h.lines += DiffLine(DiffLine.Kind.Note, null, null, line.drop(1).trim()); continue }
            when {
                line.startsWith("diff --git ") || line.startsWith("diff --cc ") || line.startsWith("diff --combined ") -> {
                    endFile()
                    cur = Builder().also { b -> gitHeaderPaths(line.removePrefix("diff --git "))?.let { (a, z) -> b.oldPath = a; b.newPath = z } }
                }
                cur == null -> continue
                line.startsWith("@@") -> {
                    endHunk()
                    val m = HUNK.find(line) ?: continue
                    val oldStart = m.groupValues[1].toInt()
                    val newStart = m.groupValues[3].toInt()
                    oldLeft = m.groupValues[2].ifEmpty { "1" }.toInt()
                    newLeft = m.groupValues[4].ifEmpty { "1" }.toInt()
                    hunk = HunkBuilder(line, oldStart, newStart)
                }
                line.startsWith("new file mode") -> cur!!.change = FileChange.Added
                line.startsWith("deleted file mode") -> cur!!.change = FileChange.Deleted
                line.startsWith("rename from ") -> { cur!!.change = FileChange.Renamed; cur!!.oldPath = CodeReader.unquote(line.removePrefix("rename from ")) }
                line.startsWith("rename to ") -> cur!!.newPath = CodeReader.unquote(line.removePrefix("rename to "))
                line.startsWith("copy from ") -> { cur!!.change = FileChange.Copied; cur!!.oldPath = CodeReader.unquote(line.removePrefix("copy from ")) }
                line.startsWith("copy to ") -> cur!!.newPath = CodeReader.unquote(line.removePrefix("copy to "))
                line.startsWith("old mode") || line.startsWith("new mode") -> cur!!.modeChanged = true
                line.startsWith("Binary files ") || line.startsWith("GIT binary patch") -> cur!!.binary = true
                line.startsWith("--- ") -> {
                    val p = side(line.removePrefix("--- "), 'a')
                    if (p == null) cur!!.change = FileChange.Added else cur!!.oldPath = p
                }
                line.startsWith("+++ ") -> {
                    val p = side(line.removePrefix("+++ "), 'b')
                    if (p == null) { cur!!.change = FileChange.Deleted; cur!!.newPath = null } else cur!!.newPath = p
                }
            }
        }
        endFile()
        return files
    }

    /** `a/x` or `b/x` as `x`, unquoted, without the tab Git adds after a name with spaces; null for `/dev/null`. */
    private fun side(raw: String, prefix: Char): String? {
        val p = CodeReader.unquote(raw.trimEnd('\t').let { if (it.startsWith('"')) it else it.substringBefore('\t') })
        if (p == "/dev/null") return null
        return if (p.startsWith("$prefix/")) p.substring(2) else p
    }

    /** The two names of `diff --git a/x b/y` when they can be told apart: quoted, or the same name twice. */
    private fun gitHeaderPaths(rest: String): Pair<String, String>? {
        if (rest.startsWith('"')) {
            val end = rest.indexOf("\" ", 1).takeIf { it > 0 } ?: return null
            val a = CodeReader.unquote(rest.substring(0, end + 1))
            val b = CodeReader.unquote(rest.substring(end + 2))
            return a.removePrefix("a/") to b.removePrefix("b/")
        }
        // "a/x b/x": the halves are the same name, so the middle is where " b/" splits it evenly.
        if (!rest.startsWith("a/")) return null
        val half = (rest.length - 1) / 2
        if (rest.length % 2 == 1 && rest.substring(half, half + 3) == " b/") {
            val a = rest.substring(2, half)
            if (a == rest.substring(half + 3)) return a to a
        }
        val i = rest.indexOf(" b/")
        return if (i > 0) rest.substring(2, i) to rest.substring(i + 3) else null
    }

    private class HunkBuilder(val header: String, val oldStart: Int, val newStart: Int) {
        var old = oldStart
        var new = newStart
        val lines = ArrayList<DiffLine>()
    }

    private class Builder {
        var oldPath: String? = null
        var newPath: String? = null
        var change = FileChange.Modified
        var binary = false
        var modeChanged = false
        val hunks = ArrayList<Hunk>()

        fun build(): FileDiff {
            val o = if (change == FileChange.Added) null else oldPath
            val n = if (change == FileChange.Deleted) null else newPath
            return FileDiff(o, n ?: if (change == FileChange.Deleted) null else o, change, binary, hunks, modeOnly = modeChanged && hunks.isEmpty() && !binary)
        }
    }
}
