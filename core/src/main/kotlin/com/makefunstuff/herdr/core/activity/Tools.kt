package com.makefunstuff.herdr.core.activity

import com.makefunstuff.herdr.core.code.DiffLine
import com.makefunstuff.herdr.core.code.FileChange
import com.makefunstuff.herdr.core.code.FileDiff
import com.makefunstuff.herdr.core.code.Hunk
import com.makefunstuff.herdr.core.code.UnifiedDiff

/** Which kind of step a harness's tool is, by its name (and, for a shell, by what it runs). */
object Tools {
    private val READ = setOf("read", "notebookread", "view", "cat", "view_image", "viewimage", "read_file", "readfile", "open")
    private val SEARCH = setOf("grep", "glob", "ls", "find", "search", "list", "list_files", "listfiles", "rg", "file_search", "codebase_search")
    private val EDIT = setOf(
        "edit", "multiedit", "write", "notebookedit", "apply_patch", "applypatch", "str_replace", "str_replace_editor", "create", "write_file", "edit_file",
        "patch", "replace",
    )
    private val WEB = setOf("webfetch", "websearch", "web_search", "web_fetch", "fetch", "browser", "web", "search_web", "web search")
    private val PLAN = setOf("todowrite", "todo", "update_plan", "exitplanmode", "enterplanmode", "plan", "todo_write")
    private val SHELL = setOf("bash", "shell", "exec_command", "exec", "run", "terminal", "monitor", "bashoutput", "killshell", "local_shell", "unified_exec", "write_stdin", "container.exec")

    fun kind(tool: String, command: String? = null): StepKind {
        val n = tool.lowercase().substringAfterLast("__")
        return when {
            n in SHELL -> if (command != null && isTest(command)) StepKind.Test else StepKind.Command
            n in READ || n in SEARCH -> StepKind.Read
            n in EDIT -> StepKind.Edit
            n in WEB -> StepKind.Web
            n in PLAN -> StepKind.Plan
            else -> StepKind.Tool
        }
    }

    // A test runner, a build's check, a type checker or a linter, as the first word of any part of the command line.
    private val TEST = Regex(
        """(?:^|[\s;&|(])""" +
            """(?:\S*/)?(?:pytest|py\.test|jest|vitest|mocha|rspec|phpunit|ctest|tox|nox|""" +
            """go\s+(?:test|vet)|cargo\s+(?:test|nextest|check|clippy)|(?:npm|pnpm|yarn|bun)\s+(?:run\s+)?(?:test|check|lint|typecheck)\S*|""" +
            """deno\s+(?:test|check|lint)|swift\s+test|dotnet\s+test|mvn\s+\S*\s*(?:test|verify)|make\s+(?:test|check|lint)\S*|""" +
            """(?:\S*/)?gradlew?\b[^;&|]*?\s(?:\S*:)?(?:test\w*|check|lint\w*|connected\w*Test)\b|""" +
            """python3?\s+-m\s+(?:pytest|unittest|mypy)|tsc\b|mypy|ruff|eslint|flake8|ktlint|detekt|shellcheck)\b""",
    )

    /** Whether [command] runs tests or checks: looked for in what the shell runs, not in a heredoc's text or a quoted script. */
    fun isTest(command: String): Boolean {
        val lines = command.lines()
        val heredoc = lines.indexOfFirst { "<<" in it }
        val shell = (if (heredoc >= 0) lines.take(heredoc + 1) else lines).take(8).joinToString("\n") { it.substringBefore("<<") }
        return TEST.containsMatchIn(shell.replace(QUOTED, "''"))
    }

    private val QUOTED = Regex("""'[^']*'|"(?:[^"\\]|\\.)*"""")

    /** The line a test run ended with that says how it went: pytest's summary, Gradle's verdict, a count. */
    fun testSummary(output: String?): String? {
        val lines = output?.lines()?.map { it.trim().trim('=', ' ') }?.filter { it.isNotEmpty() } ?: return null
        val good = Regex("""(?i)(\b\d+ (?:passed|failed|tests?|examples?|failures?)\b|BUILD (?:SUCCESSFUL|FAILED)|^(?:ok|FAIL)\b|Tests run:|test result:|\d+ problems?)""")
        return lines.lastOrNull { good.containsMatchIn(it) }?.take(140)
    }

    /** `Exit code 2`, `exit status 1`, `Process exited with code 1`, `Command exited with code 127` in a tool's output. */
    fun exitIn(output: String?): Int? {
        val text = output ?: return null
        return EXIT.find(text.take(400))?.groupValues?.get(1)?.toIntOrNull() ?: EXIT.find(text.takeLast(400))?.groupValues?.get(1)?.toIntOrNull()
    }

    private val EXIT = Regex("""(?i)\b(?:exit code|exit status|exited with code|exit_code)[:= ]+(-?\d+)""")

    private const val MAX_OUTPUT = 4_000

    /** A command as its title: without the `cd <folder> &&` many agents start every command with. */
    fun commandTitle(command: String): String {
        val first = command.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        return first.replace(LEADING_CD, "").ifEmpty { first }.take(200)
    }

    private val LEADING_CD = Regex("""^cd\s+(?:"[^"]*"|'[^']*'|\S+)\s*(?:&&|;)\s*""")

    fun clip(text: String?): String? {
        val t = text?.trimEnd()?.ifEmpty { null } ?: return null
        return if (t.length <= MAX_OUTPUT) t else t.take(MAX_OUTPUT) + "\n… (${t.length - MAX_OUTPUT} more characters)"
    }
}

/** A step's diff, made from the shapes the harnesses keep an edit in. */
object Diffs {
    private const val MAX_LINES = 600

    /** Claude Code's `structuredPatch`: hunks of `+`, `-` and ` ` lines with their starts. */
    fun hunks(path: String, hunks: List<Pair<Pair<Int, Int>, List<String>>>, change: FileChange = FileChange.Modified): FileDiff? {
        if (hunks.isEmpty()) return null
        var budget = MAX_LINES
        val out = hunks.mapNotNull { (starts, lines) ->
            if (budget <= 0) return@mapNotNull null
            var old = starts.first
            var new = starts.second
            val rows = lines.take(budget).map { l ->
                when {
                    l.startsWith("+") -> DiffLine(DiffLine.Kind.Added, null, new++, l.substring(1))
                    l.startsWith("-") -> DiffLine(DiffLine.Kind.Removed, old++, null, l.substring(1))
                    l.startsWith("\\") -> DiffLine(DiffLine.Kind.Note, null, null, l.drop(1).trim())
                    else -> DiffLine(DiffLine.Kind.Context, old++, new++, l.drop(1))
                }
            }
            budget -= rows.size
            Hunk("@@ -${starts.first} +${starts.second} @@", starts.first, starts.second, rows)
        }
        return FileDiff(if (change == FileChange.Added) null else path, path, change, false, out)
    }

    /** A new file: every line added. */
    fun added(path: String, content: String): FileDiff {
        val lines = content.removeSuffix("\n").split('\n')
        val shown = lines.take(MAX_LINES).mapIndexed { i, l -> DiffLine(DiffLine.Kind.Added, null, i + 1, l) }
        val note = if (lines.size > MAX_LINES) listOf(DiffLine(DiffLine.Kind.Note, null, null, "${lines.size - MAX_LINES} more lines")) else emptyList()
        return FileDiff(null, path, FileChange.Added, false, listOf(Hunk("@@ -0,0 +1,${lines.size} @@", 0, 1, shown + note)))
    }

    /** A replacement without line numbers (an edit's old and new text). */
    fun replaced(path: String, old: String, new: String): FileDiff? {
        if (old.isEmpty() && new.isEmpty()) return null
        val removed = if (old.isEmpty()) emptyList() else old.removeSuffix("\n").split('\n')
        val addedLines = if (new.isEmpty()) emptyList() else new.removeSuffix("\n").split('\n')
        val rows = removed.take(MAX_LINES / 2).map { DiffLine(DiffLine.Kind.Removed, null, null, it) } +
            addedLines.take(MAX_LINES / 2).map { DiffLine(DiffLine.Kind.Added, null, null, it) }
        return FileDiff(path, path, FileChange.Modified, false, listOf(Hunk("@@ @@", 0, 0, rows)))
    }

    /** Hunks in the unified format without a file header (codex's `unified_diff`), or a whole `git diff`. */
    fun unified(path: String, text: String, change: FileChange = FileChange.Modified): FileDiff? {
        val body = if (text.startsWith("diff --git")) text else "diff --git a/$path b/$path\n--- a/$path\n+++ b/$path\n$text"
        val f = UnifiedDiff.parse(body).firstOrNull() ?: return null
        val hunks = f.hunks.let { hs ->
            var budget = MAX_LINES
            hs.mapNotNull { h -> if (budget <= 0) null else h.copy(lines = h.lines.take(budget)).also { budget -= it.lines.size } }
        }
        return f.copy(oldPath = if (change == FileChange.Added) null else path, newPath = if (change == FileChange.Deleted) null else path, change = change, hunks = hunks)
    }

    /**
     * pi's edit diff: one line each, `+` added, `-` removed or ` ` kept, then the line's number and its
     * text; a line of `...` between the parts.
     */
    fun pi(path: String, text: String): FileDiff? {
        val hunks = ArrayList<Hunk>()
        var rows = ArrayList<DiffLine>()
        var count = 0
        fun close() {
            if (rows.isNotEmpty()) {
                val first = rows.firstNotNullOfOrNull { it.new ?: it.old } ?: 0
                hunks += Hunk("@@ $first @@", first, first, rows)
            }
            rows = ArrayList()
        }
        for (line in text.split('\n')) {
            if (line.isBlank()) continue
            val sign = line[0]
            val rest = line.substring(1)
            val m = PI_LINE.find(rest)
            if (m == null || sign !in "+- ") {
                if (rest.trim() == "..." || line.trim() == "...") close()
                continue
            }
            if (count++ >= MAX_LINES) break
            val n = m.groupValues[1].toIntOrNull()
            val body = m.groupValues[2]
            rows += when (sign) {
                '+' -> DiffLine(DiffLine.Kind.Added, null, n, body)
                '-' -> DiffLine(DiffLine.Kind.Removed, n, null, body)
                else -> DiffLine(DiffLine.Kind.Context, n, n, body)
            }
        }
        close()
        return if (hunks.isEmpty()) null else FileDiff(path, path, FileChange.Modified, false, hunks)
    }

    private val PI_LINE = Regex("""^\s*(\d+)(?: (.*))?$""")
}
