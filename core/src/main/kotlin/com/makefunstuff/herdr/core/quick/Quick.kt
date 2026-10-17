package com.makefunstuff.herdr.core.quick

/** A script, run as it is, or a prompt for an AI worker (`<name>.prompt.md`). */
enum class QuickKind { Script, Prompt }

/** Where a command lives: a repository's `.herdr/commands/` or the machine's `~/.config/herdr/commands/`. */
enum class QuickScope { Project, Global }

/**
 * One quick command found on a machine: an executable file, or a `.prompt.md` file, in one of the
 * commands folders. What it is called and how it runs come from the comment header at its top
 * ([QuickHeader]); without one, its file name names it.
 */
data class QuickCommand(
    val name: String,
    val kind: QuickKind,
    val scope: QuickScope,
    /** The file, as an absolute path on the machine. */
    val path: String,
    val description: String = "",
    /** A Lucide icon's name (`git-branch`, `rocket`); empty for the kind's own. */
    val icon: String = "",
    /** Asks before it runs. */
    val confirm: Boolean = false,
    /** What to ask for before it runs (the question of a small form); null when it takes nothing. */
    val args: String? = null,
    /** Runs in a new herdr pane rather than in the output sheet (long or interactive ones). */
    val pane: Boolean = false,
    /** For a prompt: the agent and model (`claude/claude-opus-5-5`, `pi/deepseek/deepseek-flash`); empty is the machine's default. */
    val model: String = "",
    /** For a prompt: what it may use (`read-only`, `shell`, `web`); empty is read-only. */
    val tools: List<String> = emptyList(),
    /** For a prompt: its limits; null is the worker's (10 minutes, 200 000 tokens). */
    val timeoutMinutes: Int? = null,
    val maxTokens: Int? = null,
    /** For a prompt: the prompt itself, with its `{{placeholders}}`. */
    val body: String = "",
) {
    val fileName: String get() = path.substringAfterLast('/')

    /** The commands folder it is in. */
    val folder: String get() = path.substringBeforeLast('/')

    /** What a pin remembers it by: its scope and name (a project's commands are pinned for every repository that has one so named). */
    val key: String get() = "${scope.name}:$name"
}

/**
 * Reads a command's header: comment lines at the top of the file, `# key: value`, as
 *
 * ```
 * #!/bin/sh
 * # name: Deploy staging
 * # desc: Builds and pushes to the staging server
 * # icon: rocket
 * # confirm: true
 * # args: Which branch?
 * ```
 *
 * A script's header is its first comment lines (after the `#!` line); it ends at the first line
 * that isn't a comment. A prompt's header is its first lines that are known keys (so a Markdown
 * heading such as `# Summary` starts the prompt), and the rest of the file is the prompt.
 */
object QuickHeader {
    /** Keys a prompt's header can have; any other line starts its body. */
    private val PROMPT_KEYS = setOf("name", "desc", "description", "icon", "model", "args", "tools", "confirm", "timeout", "tokens", "pane")
    private val LINE = Regex("""^#\s*([A-Za-z][A-Za-z_-]*)\s*:\s*(.*?)\s*$""")
    const val PROMPT_SUFFIX = ".prompt.md"

    fun kindOf(fileName: String): QuickKind = if (fileName.endsWith(PROMPT_SUFFIX)) QuickKind.Prompt else QuickKind.Script

    /** The name a file without a `# name:` goes by: its name without the extension. */
    fun defaultName(fileName: String): String = when {
        fileName.endsWith(PROMPT_SUFFIX) -> fileName.removeSuffix(PROMPT_SUFFIX)
        '.' in fileName.drop(1) -> fileName.substringBeforeLast('.')
        else -> fileName
    }.ifEmpty { fileName }

    fun parse(path: String, scope: QuickScope, text: String): QuickCommand {
        val fileName = path.substringAfterLast('/')
        val kind = kindOf(fileName)
        val lines = text.replace("\r\n", "\n").split('\n')
        val keys = LinkedHashMap<String, String>()
        var bodyAt = lines.size
        var i = 0
        if (kind == QuickKind.Script && lines.firstOrNull()?.startsWith("#!") == true) i = 1
        while (i < lines.size) {
            val line = lines[i]
            val m = LINE.matchEntire(line.trim())
            if (kind == QuickKind.Prompt) {
                if (m == null || m.groupValues[1].lowercase() !in PROMPT_KEYS) {
                    // One blank line between the header and the prompt belongs to neither.
                    bodyAt = if (keys.isNotEmpty() && line.isBlank()) i + 1 else i
                    break
                }
            } else if (!line.trimStart().startsWith("#")) break
            if (m != null) keys.putIfAbsent(m.groupValues[1].lowercase(), m.groupValues[2])
            i++
        }
        fun key(vararg names: String) = names.firstNotNullOfOrNull { keys[it]?.takeIf { v -> v.isNotBlank() } }
        return QuickCommand(
            name = key("name") ?: defaultName(fileName),
            kind = kind,
            scope = scope,
            path = path,
            description = key("desc", "description").orEmpty(),
            icon = key("icon").orEmpty().lowercase(),
            confirm = yes(key("confirm")),
            // `# args:` with no question still takes something: "Arguments".
            args = keys["args"]?.let { it.ifBlank { "Arguments" } }?.takeUnless { no(it) },
            pane = yes(key("pane")),
            model = key("model").orEmpty(),
            tools = key("tools").orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() },
            timeoutMinutes = key("timeout")?.let(::minutes),
            maxTokens = key("tokens")?.replace(Regex("[ _,]"), "")?.let { t ->
                if (t.endsWith("k", true)) t.dropLast(1).toIntOrNull()?.times(1000) else t.toIntOrNull()
            }?.takeIf { it > 0 },
            body = if (kind == QuickKind.Prompt) lines.drop(bodyAt).joinToString("\n").trim() else "",
        )
    }

    private fun yes(v: String?) = v?.trim()?.lowercase() in setOf("true", "yes", "1", "on", "y")
    private fun no(v: String) = v.trim().lowercase() in setOf("false", "no", "0", "off", "none")

    /** `10`, `10m`, `90s`, `1h` as minutes (at least one). */
    private fun minutes(v: String): Int? {
        val t = v.trim().lowercase()
        val n = t.takeWhile { it.isDigit() }.toIntOrNull() ?: return null
        return when (t.drop(t.takeWhile { it.isDigit() }.length).trim()) {
            "", "m", "min", "mins", "minutes" -> n
            "s", "sec", "seconds" -> maxOf(1, (n + 59) / 60)
            "h", "hour", "hours" -> n * 60
            else -> null
        }?.takeIf { it > 0 }
    }
}

/** The commands a place has, in the order they show: its project's first, then the machine's. */
object QuickList {
    /**
     * [found] in the order the folders were looked in (the worktree's own, its repository's main
     * checkout, then the machine's): one command per name (case aside), the first found winning,
     * so a project's command hides the machine's of the same name. Each scope is sorted by name.
     */
    fun of(found: List<QuickCommand>): List<QuickCommand> {
        val seen = HashSet<String>()
        val kept = found.filter { seen.add(it.name.lowercase()) }
        return kept.filter { it.scope == QuickScope.Project }.sortedBy { it.name.lowercase() } +
            kept.filter { it.scope == QuickScope.Global }.sortedBy { it.name.lowercase() }
    }
}

/** What a command runs in and for: the folder, its repository, what is selected. Fills a prompt's placeholders. */
data class QuickContext(
    /** The folder it runs in: the worktree or checkout, or the home folder. */
    val cwd: String = "",
    /** The repository's top folder (the checkout's own); empty outside Git. */
    val repo: String = "",
    val branch: String = "",
    /** Text chosen where it was opened (lines of a file), or the clipboard's. */
    val selection: String = "",
    /** The file open where it was started, relative to [cwd]. */
    val file: String = "",
    val clipboard: String = "",
    val machine: String = "",
    /** Today, as `2026-10-18`. */
    val date: String = "",
)

object QuickPrompt {
    private val PLACEHOLDER = Regex("""\{\{\s*([a-zA-Z_]+)\s*\}\}""")

    /** The placeholders a prompt has, by name (`args`, `repo`, `selection`…). */
    fun placeholders(body: String): Set<String> = PLACEHOLDER.findAll(body).map { it.groupValues[1].lowercase() }.toSet()

    /**
     * [body] with its placeholders filled: `{{args}}`, `{{repo}}`, `{{worktree}}` (or `{{cwd}}`),
     * `{{branch}}`, `{{selection}}`, `{{file}}`, `{{clipboard}}`, `{{machine}}`, `{{date}}`. One it
     * doesn't know stays as it is. Arguments given to a prompt without `{{args}}` go at its end.
     */
    fun fill(body: String, args: String, ctx: QuickContext): String {
        val values = mapOf(
            "args" to args, "arguments" to args, "input" to args,
            "repo" to ctx.repo.ifEmpty { ctx.cwd }, "worktree" to ctx.cwd, "cwd" to ctx.cwd, "folder" to ctx.cwd,
            "branch" to ctx.branch, "selection" to ctx.selection.ifEmpty { ctx.clipboard }, "file" to ctx.file,
            "clipboard" to ctx.clipboard, "machine" to ctx.machine, "date" to ctx.date,
        )
        val filled = PLACEHOLDER.replace(body) { m -> values[m.groupValues[1].lowercase()] ?: m.value }
        val hasArgs = placeholders(body).any { it in setOf("args", "arguments", "input") }
        return if (args.isNotBlank() && !hasArgs) "$filled\n\n$args" else filled
    }
}

/** Arguments typed in the form, as a script gets them: words, with quotes keeping spaces in one. */
object QuickArgs {
    fun split(text: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var inWord = false
        var quote: Char? = null
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quote != null && c == quote -> quote = null
                quote == null && (c == '"' || c == '\'') -> { quote = c; inWord = true }
                quote != '\'' && c == '\\' && i + 1 < text.length -> { cur.append(text[i + 1]); inWord = true; i++ }
                quote == null && c.isWhitespace() -> { if (inWord) out += cur.toString(); cur.clear(); inWord = false }
                else -> { cur.append(c); inWord = true }
            }
            i++
        }
        if (inWord) out += cur.toString()
        return out
    }
}

/** What "New command" asks an agent (the Coordinator, or the one at work there) to write. */
object QuickNew {
    /** A file name for [name]: lower case words joined by dashes, `.prompt.md` for a prompt. */
    fun fileName(name: String, kind: QuickKind): String {
        val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).trimEnd('-').ifEmpty { "command" }
        return if (kind == QuickKind.Prompt) slug + QuickHeader.PROMPT_SUFFIX else "$slug.sh"
    }

    /**
     * The request: write a [kind] command that does [what] into [folder] (as [name], or one the agent
     * picks), with the header the app reads.
     */
    fun message(kind: QuickKind, what: String, folder: String, name: String = ""): String {
        val file = if (name.isNotBlank()) "$folder/${fileName(name, kind)}" else "$folder/<short-name>${if (kind == QuickKind.Prompt) QuickHeader.PROMPT_SUFFIX else ".sh"}"
        val title = name.trim().ifEmpty { "<a short name>" }
        return if (kind == QuickKind.Script) listOf(
            "Please write a quick command for Herdr for Android (I run it from my phone): an executable shell script at `$file`.",
            "",
            "It should: ${what.trim()}",
            "",
            "Start it with the comment header the app reads, one line each:",
            "```",
            "#!/usr/bin/env bash",
            "# name: $title",
            "# desc: <what it does, in one line>",
            "# icon: <a Lucide icon name, such as rocket or git-branch>",
            "# confirm: true        (only when it changes or deletes something)",
            "# args: <a question>   (only when it needs input: the answer comes as its arguments, and whole in \$HERDR_ARGS)",
            "```",
            "It runs in the repository's folder (the home folder for one in ~/.config/herdr/commands) with no terminal and nobody to answer: " +
                "print what it does, and exit with an error when it fails. Make it executable (`chmod +x`). Create the folder if it isn't there.",
        ).joinToString("\n") else listOf(
            "Please write an AI quick command for Herdr for Android (I run it from my phone): a prompt file at `$file`.",
            "",
            "It should: ${what.trim()}",
            "",
            "The file is a header, one line each, then a blank line and the prompt itself:",
            "```",
            "# name: $title",
            "# desc: <what it does, in one line>",
            "# icon: <a Lucide icon name, such as sparkles or file-search>",
            "# model: <claude/<model> or pi/<provider>/<model>; leave it out for this machine's default>",
            "# tools: read-only   (or shell, web: only what it needs)",
            "# args: <a question> (only when it needs input)",
            "```",
            "The prompt can use {{args}}, {{repo}}, {{worktree}}, {{branch}}, {{selection}}, {{file}}, {{clipboard}} and {{date}}. " +
                "It runs as a durable worker in the repository's folder, with nobody to answer its questions, and its answer shows on a phone: " +
                "ask for a short Markdown answer. Create the folder if it isn't there.",
        ).joinToString("\n")
    }
}
