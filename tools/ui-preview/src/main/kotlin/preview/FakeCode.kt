// The code on a fake machine: its repositories and checkouts, answering the app's code commands
// (CodeCommands: `sh -c <script> herdr-code-<op> <args>`) with what those commands printed on a
// real repository (src/main/resources/demo/code, written by code-demo/make.sh).
package preview

import com.makefunstuff.herdr.core.ssh.CommandResult

class FakeCode(
    /** The repositories, each its checkouts with the main one first. */
    val repos: List<List<Checkout>> = devbox(),
    val home: String = "/home/demo",
) {
    /** A checkout; [recording] names its files in demo/code (`task` → task.files, task.log, task/…); null has a README only. */
    class Checkout(val path: String, val branch: String, val recording: String?, /** Its diff against main, for one without a [recording]. */ val diffBase: String? = null)

    private val checkouts get() = repos.flatten()

    fun run(command: String): CommandResult? {
        val w = Shell.words(command)
        val at = w.indexOfFirst { it.startsWith("herdr-code-") }
        if (at < 0) return null
        val op = w[at].removePrefix("herdr-code-")
        val args = w.drop(at + 1).map(::expand)
        val co = args.firstOrNull()?.let(::find)
        return when (op) {
            "discover" -> ok(discover(args))
            "info" -> ok(
                if (co == null) "nogit\t${args[0]}\n"
                else "git\t${co.path}\t${co.branch}\t${head(co)}\t${args.getOrNull(1)?.ifEmpty { null } ?: "main"}\t${repoOf(co).first().path}\n",
            )
            "files" -> ok(co?.recording?.let { Demo.resource("code/$it.files") } ?: "README.md\u0000")
            "read" -> ok(read(co, args[1]))
            "search" -> ok(search(co, args[1], args.getOrElse(2) { "" }, args.getOrNull(3)?.toIntOrNull() ?: 400))
            "status" -> ok(co?.recording?.let { Demo.resource("code/$it.status") } ?: "## ${co?.branch ?: "main"}\n")
            "diff" -> ok(co?.diffBase?.takeIf { args.getOrNull(2) != "head" }?.let { Demo.resource("code/$it") } ?: co?.recording?.let { Demo.resource("code/$it." + if (args.getOrNull(2) == "head") "diff-head" else "diff-base") } ?: "@@from 0000000\n")
            "log" -> ok(co?.recording?.let { Demo.resource("code/$it.log") } ?: "@@herdr-code log\n")
            "show" -> ok(resource("code/show/${args.getOrElse(1) { "" }}") ?: "")
            else -> CommandResult(127, "", "unknown")
        }
    }

    private fun ok(out: String) = CommandResult(0, out, "")

    private fun expand(p: String) = when {
        p == "~" -> home
        p.startsWith("~/") -> home + p.removePrefix("~")
        else -> p
    }

    private fun find(dir: String): Checkout? =
        checkouts.filter { dir == it.path || dir.startsWith(it.path + "/") }.maxByOrNull { it.path.length }

    private fun repoOf(co: Checkout) = repos.first { co in it }

    private fun head(co: Checkout): String = co.recording?.let { Demo.resource("code/$it.head").trim().substringAfter('\t') } ?: "1a2b3c4"

    /** `git worktree list --porcelain` of each repository the folders are in, and where each folder is. */
    private fun discover(folders: List<String>): String = buildString {
        val seen = HashSet<Int>()
        for (f in folders) {
            val co = find(f) ?: continue
            append("P\t").append(f).append('\t').append(co.path).append('\n')
            val repo = repoOf(co)
            if (!seen.add(repos.indexOf(repo))) continue
            append("W\t").append(co.path).append('\n')
            repo.forEach { c ->
                append("worktree ").append(c.path).append('\n')
                append("HEAD ").append(head(c)).append("0".repeat(33)).append('\n')
                append("branch refs/heads/").append(c.branch).append("\n\n")
            }
            append("E\n")
        }
    }

    private fun read(co: Checkout?, path: String): String {
        val text = if (co?.recording == null) (if (path == "README.md") "# ${co?.path?.substringAfterLast('/') ?: "demo"}\n" else null)
        else resource("code/${co.recording}/$path")
        return text?.let { "@@size ${it.toByteArray().size}\n$it" } ?: "@@nofile\n"
    }

    /** `git grep -n` over the checkout's files: fixed words or a pattern, with or without case. */
    private fun search(co: Checkout?, query: String, flags: String, limit: Int): String {
        val rec = co?.recording ?: return ""
        val f = flags.split(' ')
        val options = if ("-i" in f) setOf(RegexOption.IGNORE_CASE) else emptySet()
        val pattern = runCatching { Regex(if ("-E" in f) query else Regex.escape(query), options) }.getOrElse { return "" }
        val out = StringBuilder()
        var n = 0
        for (path in Demo.resource("code/$rec.files").split('\u0000').filter { it.isNotEmpty() }) {
            resource("code/$rec/$path")?.lines()?.forEachIndexed { i, line ->
                if (n < limit && pattern.containsMatchIn(line)) { out.append(path).append(':').append(i + 1).append(':').append(line.take(600)).append('\n'); n++ }
            }
        }
        return out.toString()
    }

    companion object {
        const val MAIN = "/home/demo/src/herdr-android"
        /** Task AND-9's worktree, as the board names it. */
        const val TASK = "/home/demo/src/herdr-android-9"

        fun devbox() = listOf(
            listOf(Checkout(MAIN, "main", "main"), Checkout(TASK, "work/9-last-answered", "task")),
            listOf(Checkout("/home/demo/src/api-server", "main", null)),
            listOf(Checkout("/home/demo/src/website", "main", null), Checkout("/home/demo/src/website-4", "work/4-install-page", null, diffBase = "website-4.diff-base")),
        )

        /** The [n]th commit of the task's history, newest first. */
        fun commit(n: Int): String =
            Demo.resource("code/task.log").substringAfter("@@herdr-code log\n").split('\u001e')[n].trim().substringBefore('\u001f')

        private fun resource(path: String): String? =
            FakeCode::class.java.classLoader.getResourceAsStream("demo/$path")?.readBytes()?.toString(Charsets.UTF_8)
    }
}
