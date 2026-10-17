package com.makefunstuff.herdr.core.code

import java.io.IOException

/** One checkout of a repository: its main folder or a linked worktree (an agent's, a task's). */
data class Checkout(
    val path: String,
    /** The branch checked out, without `refs/heads/`; null when detached or bare. */
    val branch: String?,
    val head: String = "",
    val main: Boolean = false,
    val detached: Boolean = false,
    val prunable: Boolean = false,
) {
    val name: String get() = path.trimEnd('/').substringAfterLast('/')
}

/** A repository on a machine and its checkouts, the main one first. */
data class Repo(val checkouts: List<Checkout>) {
    val main: Checkout get() = checkouts.first()
    val name: String get() = main.name
}

/** What [CodeCommands.discover] found: the repositories, and the checkout each folder asked about is in. */
data class Discovery(val repos: List<Repo>, val topOf: Map<String, String>)

/** A checkout as it was opened: Git's view of it, or a plain folder. */
data class CheckoutInfo(
    val top: String,
    val git: Boolean,
    val branch: String = "",
    val head: String = "",
    /** What "changes" are measured against: `main`, `origin/main`…; empty when none was found. */
    val base: String = "",
    /** The repository's main checkout; empty outside Git. */
    val mainPath: String = "",
) {
    val name: String get() = top.trimEnd('/').substringAfterLast('/')
    val linked: Boolean get() = git && mainPath.isNotEmpty() && mainPath != top
}

/** A file as read: [text] is its first [CodeCommands.READ_LIMIT] bytes when [truncated]. */
data class FileText(val text: String, val size: Long, val truncated: Boolean, val binary: Boolean) {
    /** The lines, without their ends; a file ending in a newline has no empty last line. */
    val lines: List<String> by lazy {
        val l = text.split('\n').map { it.removeSuffix("\r") }
        if (l.size > 1 && l.last().isEmpty()) l.dropLast(1) else l
    }
}

/** A line that matched a search. */
data class SearchHit(val path: String, val line: Int, val text: String)

/** One entry of `git status`: [index] and [work] are its two letters (`M`, `A`, `D`, `R`, `?`…). */
data class StatusEntry(val path: String, val from: String?, val index: Char, val work: Char) {
    val untracked: Boolean get() = index == '?'
    val conflicted: Boolean get() = index == 'U' || work == 'U' || (index == 'A' && work == 'A') || (index == 'D' && work == 'D')
    val staged: Boolean get() = !untracked && index != ' '

    /** A word for what changed. */
    val label: String get() = when {
        untracked -> "new, untracked"
        conflicted -> "conflict"
        index == 'R' || work == 'R' -> "renamed"
        index == 'A' || work == 'A' -> "added"
        index == 'D' || work == 'D' -> "deleted"
        else -> "modified"
    } + if (staged && work != ' ' && !conflicted) ", partly staged" else if (staged && !untracked) ", staged" else ""
}

data class GitStatus(val branch: String, val upstream: String?, val ahead: Int, val behind: Int, val entries: List<StatusEntry>)

/** A commit in the history. [ahead]: its branch has it and the base doesn't yet. */
data class Commit(
    val sha: String,
    val short: String,
    val author: String,
    /** Seconds since the epoch. */
    val at: Long,
    val subject: String,
    val refs: String = "",
    val body: String = "",
    val parents: List<String> = emptyList(),
    val ahead: Boolean = false,
)

/** A commit and what it changed. */
data class CommitDiff(val commit: Commit, val files: List<FileDiff>, val truncated: Boolean)

/** A checkout's changes: from [from] (the commit it left its base at, or its last commit) to what is on disk now. */
data class ChangeSet(val from: String, val files: List<FileDiff>, val truncated: Boolean, val noBase: Boolean = false) {
    val added: Int get() = files.sumOf { it.added }
    val removed: Int get() = files.sumOf { it.removed }
}

/** Reads what [CodeCommands] print. */
object CodeReader {
    private const val UNIT = '\u001f'
    private const val RECORD = '\u001e'

    private fun missing(out: String) {
        if (out.lineSequence().firstOrNull()?.trim() == "${CodeCommands.MARK} missing") throw IOException("That folder isn't there any more")
    }

    fun discovery(out: String): Discovery {
        val topOf = LinkedHashMap<String, String>()
        val repos = ArrayList<Repo>()
        var block: StringBuilder? = null
        for (line in out.lines()) {
            when {
                block != null && line == "E" -> { worktrees(block.toString()).takeIf { it.isNotEmpty() }?.let { repos += Repo(it) }; block = null }
                block != null -> block.append(line).append('\n')
                line.startsWith("P\t") -> line.split('\t').takeIf { it.size >= 3 }?.let { topOf[it[1]] = it[2] }
                line.startsWith("W\t") -> block = StringBuilder()
            }
        }
        return Discovery(repos.distinctBy { it.main.path }, topOf)
    }

    /** `git worktree list --porcelain`: blocks of `worktree`, `HEAD`, `branch`/`detached`/`bare`, `prunable`. */
    fun worktrees(out: String): List<Checkout> {
        val list = ArrayList<Checkout>()
        var cur: Checkout? = null
        var bare = false
        fun flush() {
            cur?.takeIf { !bare }?.let { list += it }
            cur = null
            bare = false
        }
        for (line in out.lines()) {
            when {
                line.startsWith("worktree ") -> { flush(); cur = Checkout(line.removePrefix("worktree "), null) }
                line.startsWith("HEAD ") -> cur = cur?.copy(head = line.removePrefix("HEAD "))
                line.startsWith("branch ") -> cur = cur?.copy(branch = line.removePrefix("branch ").removePrefix("refs/heads/"))
                line == "detached" -> cur = cur?.copy(detached = true)
                line == "bare" -> bare = true
                line.startsWith("prunable") -> cur = cur?.copy(prunable = true)
                line.isEmpty() -> flush()
            }
        }
        flush()
        // The first block is the main working tree (or the bare repository, which isn't one).
        return list.mapIndexed { i, c -> c.copy(main = i == 0) }
    }

    fun info(out: String): CheckoutInfo {
        missing(out)
        val line = out.lines().lastOrNull { it.startsWith("git\t") || it.startsWith("nogit\t") }
            ?: throw IOException(out.trim().ifEmpty { "The machine gave no answer" })
        val f = line.split('\t')
        if (f[0] == "nogit") return CheckoutInfo(f.getOrElse(1) { "" }, git = false)
        return CheckoutInfo(
            top = f.getOrElse(1) { "" }, git = true, branch = f.getOrElse(2) { "" }.let { if (it == "HEAD") "" else it },
            head = f.getOrElse(3) { "" }, base = f.getOrElse(4) { "" }, mainPath = f.getOrElse(5) { "" },
        )
    }

    /** The NUL-separated names, sorted and without repeats (an index can list a conflicted file thrice). */
    fun files(out: String): List<String> {
        missing(out)
        return out.split('\u0000').map { it.trim('\n') }.filter { it.isNotEmpty() && !it.startsWith(CodeCommands.MARK) }.distinct().sorted()
    }

    fun file(out: String): FileText {
        missing(out)
        if (out.startsWith("@@nofile")) throw IOException("That file isn't there any more")
        val nl = out.indexOf('\n')
        if (!out.startsWith("@@size ") || nl < 0) throw IOException("The file couldn't be read")
        val size = out.substring(7, nl).trim().toLongOrNull() ?: 0L
        val text = out.substring(nl + 1)
        val head = text.take(8000)
        val binary = '\u0000' in head || head.count { it == '�' } > head.length / 10 + 2
        return FileText(text, size, truncated = size > text.toByteArray().size, binary = binary)
    }

    private val HIT = Regex("""^(.+?):(\d+):(.*)$""")

    fun search(out: String): List<SearchHit> {
        missing(out)
        return out.lines().mapNotNull { l ->
            HIT.find(l)?.let { m -> SearchHit(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].trimEnd('\r')) }
        }
    }

    private val AHEAD = Regex("""ahead (\d+)""")
    private val BEHIND = Regex("""behind (\d+)""")

    fun status(out: String): GitStatus {
        missing(out)
        var branch = ""
        var upstream: String? = null
        var ahead = 0
        var behind = 0
        val entries = ArrayList<StatusEntry>()
        for (line in out.lines()) {
            if (line.startsWith("## ")) {
                val rest = line.removePrefix("## ")
                val names = rest.substringBefore(" [")
                branch = names.substringBefore("...").removePrefix("No commits yet on ")
                upstream = names.substringAfter("...", "").ifEmpty { null }
                val counts = rest.substringAfter(" [", "")
                ahead = AHEAD.find(counts)?.groupValues?.get(1)?.toInt() ?: 0
                behind = BEHIND.find(counts)?.groupValues?.get(1)?.toInt() ?: 0
                continue
            }
            if (line.length < 4) continue
            val x = line[0]
            val y = line[1]
            val rest = unquote(line.substring(3))
            val (from, path) = if ((x == 'R' || x == 'C' || y == 'R') && " -> " in rest) rest.substringBefore(" -> ") to rest.substringAfter(" -> ") else null to rest
            entries += StatusEntry(path, from, x, y)
        }
        if (branch == "HEAD (no branch)") branch = ""
        return GitStatus(branch, upstream, ahead, behind, entries)
    }

    fun log(out: String): List<Commit> {
        missing(out)
        val ahead = out.lineSequence().takeWhile { !it.startsWith(CodeCommands.MARK) }
            .filter { it.startsWith("@@ahead ") }.map { it.removePrefix("@@ahead ").trim() }.toSet()
        val records = out.substringAfter("${CodeCommands.MARK} log\n", "")
        return records.split(RECORD).mapNotNull { r ->
            val f = r.trim('\n').split(UNIT)
            if (f.size < 6) return@mapNotNull null
            Commit(f[0], f[1], f[2], f[3].toLongOrNull() ?: 0, f[5], refs = f[4], ahead = f[0] in ahead)
        }
    }

    fun show(out: String): CommitDiff {
        missing(out)
        val cut = out.indexOf(RECORD)
        if (cut < 0) throw IOException(out.trim().lines().lastOrNull { it.isNotBlank() } ?: "That commit isn't there")
        val f = out.substring(0, cut).split(UNIT)
        if (f.size < 6) throw IOException("The commit couldn't be read")
        val message = f[5].trim()
        val commit = Commit(
            f[0], f[1], f[2], f[3].toLongOrNull() ?: 0, message.lineSequence().firstOrNull().orEmpty(),
            body = message.substringAfter('\n', "").trim(), parents = f[4].split(' ').filter { it.isNotEmpty() },
        )
        val diff = out.substring(cut + 1)
        return CommitDiff(commit, UnifiedDiff.parse(diff), truncated = diff.length >= CodeCommands.DIFF_LIMIT - 4096)
    }

    fun changes(out: String): ChangeSet {
        missing(out)
        if (out.startsWith("@@nobase")) return ChangeSet("", emptyList(), truncated = false, noBase = true)
        val nl = out.indexOf('\n')
        val from = if (out.startsWith("@@from ") && nl > 0) out.substring(7, nl).trim() else ""
        val diff = if (nl >= 0) out.substring(nl + 1) else ""
        return ChangeSet(from, UnifiedDiff.parse(diff), truncated = diff.toByteArray().size >= CodeCommands.DIFF_LIMIT)
    }

    /** Undoes Git's C-style quoting of a path (`"a\"b\303\251"`); other paths are as they are. */
    fun unquote(s: String): String {
        if (s.length < 2 || !s.startsWith('"') || !s.endsWith('"')) return s
        val bytes = java.io.ByteArrayOutputStream()
        var i = 1
        val end = s.length - 1
        while (i < end) {
            val c = s[i]
            if (c != '\\' || i + 1 >= end) {
                bytes.write(c.toString().toByteArray()); i++; continue
            }
            val n = s[i + 1]
            when {
                i + 3 < end && (1..3).all { s[i + it] in '0'..'7' } -> {
                    bytes.write(s.substring(i + 1, i + 4).toInt(8)); i += 4
                }
                else -> {
                    bytes.write(
                        when (n) { 'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; 'a' -> '\u0007'; 'b' -> '\b'; 'f' -> '\u000c'; 'v' -> '\u000b'; else -> n }.code,
                    )
                    i += 2
                }
            }
        }
        return bytes.toString(Charsets.UTF_8)
    }
}
