package com.makefunstuff.herdr.core.chat

import com.makefunstuff.herdr.core.activity.Diffs
import com.makefunstuff.herdr.core.activity.StepKind
import com.makefunstuff.herdr.core.activity.Tools
import com.makefunstuff.herdr.core.code.DiffLine
import com.makefunstuff.herdr.core.code.FileChange
import com.makefunstuff.herdr.core.code.FileDiff
import com.makefunstuff.herdr.core.code.Hunk
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * What an edit call changes, read from its own arguments, so the chat shows it as a diff while it
 * runs and when its result has none. The shapes the agents the app reads send:
 *
 * - Claude's `Edit` (`file_path`, `old_string`, `new_string`), `MultiEdit` (`edits` of those),
 *   `Write` (`content`), `NotebookEdit` (`new_source`), and the API's text editor (`old_str`,
 *   `new_str`, `file_text`);
 * - pi's `edit` (`path`, `edits` of `oldText`/`newText`, or one `oldText`/`newText`) and `write`;
 *   omp's `edit` (`path`, `old_string`, `new_string`) and `write`;
 * - Codex's `apply_patch` envelope (`*** Begin Patch` … `*** End Patch`), sent as the call's text,
 *   in its arguments, or in a script or a command that runs it.
 *
 * Anything else (omp's line-anchored edits, a call cut short) is null: the chat shows its raw arguments.
 */
object EditCalls {
    private val PATH = listOf("file_path", "path", "notebook_path", "filePath", "file")
    private val OLD = listOf("old_string", "oldText", "old_str", "old_text")
    private val NEW = listOf("new_string", "newText", "new_str", "new_text")
    private val WHOLE = listOf("content", "file_text", "new_source")

    /**
     * The files tool [name] changes with [input], as diffs; null when it isn't an edit in a shape the
     * app knows. Another tool's patch counts only when it applies it (a command or a script that runs
     * `apply_patch`): a command that only writes one somewhere edits nothing.
     */
    fun of(name: String, input: JsonObject): List<FileDiff>? =
        fields(name, input) ?: input.values.firstNotNullOfOrNull { v ->
            // A patch as an argument (`{"input": "*** Begin Patch…"}`), or in a command (`["apply_patch", "*** Begin Patch…"]`).
            val texts = (v as? JsonArray)?.mapNotNull { it.str() } ?: listOfNotNull(v.str())
            if (isEdit(name)) texts.firstOrNull { BEGIN in it }?.let(::patch) else applied(texts.joinToString("\n"))
        }

    /**
     * Whether tool [name] is an edit and nothing else, so its diff says all it did. A command or a
     * script that applies a patch may do more: its own words and output still show with the diff.
     */
    fun isEdit(name: String): Boolean = Tools.kind(name) == StepKind.Edit

    /** The patch a command or a script [text] applies (`apply_patch <<'PATCH'`, `tools.apply_patch("…")`); null when it applies none. */
    fun applied(text: String): List<FileDiff>? = if ("apply_patch" in text) patch(text) else null

    private fun fields(name: String, input: JsonObject): List<FileDiff>? {
        if (!isEdit(name)) return null
        val path = PATH.firstNotNullOfOrNull { input.str(it)?.takeIf { p -> p.isNotBlank() } } ?: return null
        // A virtual path (omp's `xd://propose` hands in a plan) is no file.
        if ("://" in path && !path.startsWith("file://")) return null
        val file = path.removePrefix("file://")
        val edits = input.arr("edits")?.filterIsInstance<JsonObject>()
        if (!edits.isNullOrEmpty()) {
            val pairs = edits.map { e -> (OLD.firstNotNullOfOrNull { e.str(it) } ?: return null) to (NEW.firstNotNullOfOrNull { e.str(it) } ?: return null) }
            return Diffs.replaced(file, pairs)?.let(::listOf)
        }
        val old = OLD.firstNotNullOfOrNull { input.str(it) }
        val new = NEW.firstNotNullOfOrNull { input.str(it) }
        if (old != null || new != null) return Diffs.replaced(file, old.orEmpty(), new.orEmpty())?.let(::listOf)
        // A notebook cell deleted has no new source: nothing to show but the call.
        if (input.str("edit_mode") == "delete") return null
        val whole = WHOLE.firstNotNullOfOrNull { input.str(it) } ?: return null
        // A write may replace a file that is there: only a create says the file is new.
        val created = name.lowercase() == "create" || input.str("command") == "create"
        return listOf(Diffs.added(file, whole).let { if (created) it else it.copy(oldPath = file, change = FileChange.Modified) })
    }

    private const val BEGIN = "*** Begin Patch"
    private const val END = "*** End Patch"
    private const val MAX_LINES = 600

    /**
     * The files a Codex patch changes, from [text] that holds one: the patch itself, or a script or
     * a command with it in a string (`tools.apply_patch("*** Begin Patch\n…")`), escapes and all.
     * Null when it has no patch or the patch names no file (omp's patch of line anchors).
     */
    fun patch(text: String): List<FileDiff>? {
        val start = text.indexOf(BEGIN).takeIf { it >= 0 } ?: return null
        val end = text.indexOf(END, start).let { if (it < 0) text.length else it + END.length }
        var body = text.substring(start, end)
        // In a string of a script the lines are `\n` escapes; the patch's own lines are real ones.
        if ('\n' !in body && "\\n" in body) body = unescape(body)
        val files = ArrayList<FileDiff>()
        var path: String? = null
        var change = FileChange.Modified
        var moved: String? = null
        val hunks = ArrayList<Hunk>()
        var rows = ArrayList<DiffLine>()
        var header = "@@"
        var budget = MAX_LINES
        fun closeHunk() {
            while (rows.lastOrNull()?.let { it.kind == DiffLine.Kind.Context && it.text.isEmpty() } == true) rows.removeAt(rows.lastIndex)
            if (rows.isNotEmpty()) hunks += Hunk(header, 0, 0, rows)
            rows = ArrayList()
        }
        fun closeFile() {
            closeHunk()
            val p = path ?: return
            files += when (change) {
                FileChange.Added -> FileDiff(null, p, change, false, hunks.toList())
                FileChange.Deleted -> FileDiff(p, null, change, false, emptyList())
                else -> FileDiff(p, moved ?: p, if (moved != null) FileChange.Renamed else FileChange.Modified, false, hunks.toList())
            }
            path = null
            moved = null
            hunks.clear()
            header = "@@"
        }
        for (line in body.split('\n')) {
            val file = FILE.matchEntire(line.trimEnd())
            when {
                file != null -> {
                    closeFile()
                    path = file.groupValues[2].trim()
                    change = when (file.groupValues[1]) { "Add" -> FileChange.Added; "Delete" -> FileChange.Deleted; else -> FileChange.Modified }
                }
                path == null -> {}
                line.startsWith("*** Move to: ") -> moved = line.removePrefix("*** Move to: ").trim()
                // `@@ fn main() {`: the line the hunk is under, as Git puts it after its second `@@`.
                line.startsWith("@@") -> { closeHunk(); header = "@@ @@ " + line.removePrefix("@@").trim() }
                line.startsWith("***") -> {}
                budget <= 0 -> {}
                line.startsWith("+") -> { rows += DiffLine(DiffLine.Kind.Added, null, null, line.substring(1)); budget-- }
                line.startsWith("-") -> { rows += DiffLine(DiffLine.Kind.Removed, null, null, line.substring(1)); budget-- }
                line.startsWith(" ") -> { rows += DiffLine(DiffLine.Kind.Context, null, null, line.substring(1)); budget-- }
                // An empty line kept, as models write it without its space.
                line.isEmpty() && change == FileChange.Modified -> { rows += DiffLine(DiffLine.Kind.Context, null, null, ""); budget-- }
            }
        }
        closeFile()
        return files.takeIf { it.isNotEmpty() }
    }

    private val FILE = Regex("""\*\*\* (Add|Update|Delete) File: (.+)""")

    /** A string's text from its escapes as JavaScript and JSON write them. */
    private fun unescape(s: String): String = buildString(s.length) {
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch != '\\' || i == s.lastIndex) { append(ch); i++; continue }
            val next = s[i + 1]
            i += 2
            when (next) {
                'n' -> append('\n')
                't' -> append('\t')
                'r' -> {}
                'u' -> {
                    val code = s.substring(i, minOf(i + 4, s.length)).toIntOrNull(16)
                    if (code != null) { append(code.toChar()); i += 4 } else append("\\u")
                }
                else -> append(next)
            }
        }
    }
}
