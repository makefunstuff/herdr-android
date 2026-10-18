package com.makefunstuff.herdr.core.chat

import com.makefunstuff.herdr.core.ssh.CommandResult
import com.makefunstuff.herdr.core.ssh.HerdrCommands.quote
import java.io.IOException

/** One of an agent's own `/` commands: [name] without the slash, what it does, and where it comes from. */
data class AgentCommand(val name: String, val description: String, val source: Source) {
    enum class Source { Builtin, User, Project, Skill }
}

/**
 * The `/` commands an agent understands and the files in its folder, for the composer's completions.
 * The built-in commands are known per agent kind (as the herdr web UI lists them); the person's and
 * the project's own commands and skills are read on the agent's machine over SSH ([Client]).
 */
object AgentCommands {
    private val BUILTINS: Map<String, List<String>> = mapOf(
        "claude" to listOf(
            "clear", "compact", "config", "context", "cost", "doctor", "effort", "help", "init", "login", "logout", "memory",
            "model", "permissions", "pr-comments", "release-notes", "review", "status", "terminal-setup", "vim",
        ),
        "codex" to listOf("clear", "compact", "diff", "help", "model", "new", "quit", "review", "status"),
        "omp" to listOf("help", "clear", "compact", "model", "new", "sessions", "exit"),
        "pi" to listOf(
            "settings", "model", "thinking", "scoped-models", "login", "logout", "new", "resume", "name", "session", "fork",
            "clone", "compact", "import", "copy", "export", "share", "reload", "hotkeys", "changelog", "quit",
        ),
    )

    private val DESCRIPTIONS = mapOf(
        "clear" to "Start a fresh conversation", "compact" to "Summarise the conversation to free context",
        "config" to "Open the settings", "context" to "Show what fills the context", "cost" to "Show token use and cost",
        "doctor" to "Check the installation", "effort" to "Set how hard it thinks", "help" to "List the commands",
        "init" to "Write the project's instructions file", "login" to "Sign in", "logout" to "Sign out",
        "memory" to "Edit its memory files", "model" to "Pick another model", "permissions" to "Manage what it may do",
        "pr-comments" to "Fetch a pull request's comments", "release-notes" to "Show the release notes",
        "review" to "Review the changes on this branch", "status" to "Show the session's status",
        "terminal-setup" to "Set up the terminal's keys", "vim" to "Turn Vim mode on or off", "diff" to "Show the current diff",
        "new" to "Start a new session", "quit" to "Exit the agent", "exit" to "Exit the agent", "sessions" to "List the sessions",
        "settings" to "Open the settings", "thinking" to "Set the thinking level", "scoped-models" to "Choose the models to cycle",
        "resume" to "Resume another session", "name" to "Name this session", "session" to "Show the session's details",
        "fork" to "Fork from an earlier message", "clone" to "Copy the session from here", "import" to "Resume a session from a file",
        "copy" to "Copy its last answer", "export" to "Export the session", "share" to "Share the session as a gist",
        "reload" to "Reload keys, extensions and skills", "hotkeys" to "Show the keyboard shortcuts", "changelog" to "Show what changed",
    )

    /** The agent kinds whose commands are known. */
    fun knows(agent: String?): Boolean = agent in BUILTINS

    /** [agent]'s own commands, by name. */
    fun builtins(agent: String?): List<AgentCommand> =
        BUILTINS[agent].orEmpty().map { AgentCommand(it, DESCRIPTIONS[it] ?: "Run /$it", AgentCommand.Source.Builtin) }

    /** The built-in commands with the machine's own ones, sorted by name; a name given twice keeps its first. */
    fun merge(agent: String?, own: List<AgentCommand>): List<AgentCommand> =
        (builtins(agent) + own).distinctBy { it.name }.sortedBy { it.name }

    const val MARK = "@@herdr-command"

    /** The most files the `@` list reads from a folder. */
    const val FILES_LIMIT = 5_000

    /**
     * Lists [agent]'s own commands and skills on its machine, each as a `MARK source kind name` line and
     * the first lines of its file: Claude Code's `~/.claude/commands` and the project's `.claude/commands`
     * (a folder in them is a `:` in the name) and their skills; Codex's saved prompts (`/prompts:<name>`);
     * pi's prompts and skills (`/skill:<name>`). Runs as `sh -c <script> herdr-agent-commands <agent> <cwd>`.
     */
    fun listCommand(agent: String, cwd: String): String = "sh -c " + quote(
        "a=\$1; c=\$2; " +
            "list() { s=\$1; k=\$2; r=\$3; d=\$4; [ -d \"\$r\" ] || return 0; " +
            "if [ \"\$k\" = skill ]; then for f in \"\$r\"/*/SKILL.md; do [ -f \"\$f\" ] || continue; n=\${f%/SKILL.md}; n=\${n##*/}; " +
            "printf '$MARK\\t%s\\tskill\\t%s\\n' \"\$s\" \"\$n\"; head -n 30 \"\$f\"; echo; done; " +
            "else find -L \"\$r\" -maxdepth \"\$d\" -type f -name '*.md' 2>/dev/null | sort | head -n 300 | while IFS= read -r f; do n=\${f#\"\$r\"/}; " +
            "printf '$MARK\\t%s\\t%s\\t%s\\n' \"\$s\" \"\$k\" \"\${n%.md}\"; head -n 30 \"\$f\"; echo; done; fi; }; " +
            "case \"\$a\" in " +
            "claude) list user command \"\$HOME/.claude/commands\" 4; [ -n \"\$c\" ] && list project command \"\$c/.claude/commands\" 4; " +
            "list skill skill \"\$HOME/.claude/skills\"; [ -n \"\$c\" ] && list skill skill \"\$c/.claude/skills\";; " +
            "codex) list user prompt \"\$HOME/.codex/prompts\" 1;; " +
            "pi|omp) list user command \"\${PI_CODING_AGENT_DIR:-\$HOME/.pi/agent}/prompts\" 1; list skill skill \"\${PI_CODING_AGENT_DIR:-\$HOME/.pi/agent}/skills\"; " +
            "list skill skill \"\$HOME/.agents/skills\";; " +
            "esac; exit 0",
    ) + " herdr-agent-commands " + quote(agent) + " " + quote(cwd)

    /**
     * The files in [cwd] as Git knows them (tracked and new, not ignored), or found by walking it
     * when it isn't a checkout, one a line, at most [FILES_LIMIT]. Runs as `herdr-agent-files <cwd>`.
     */
    fun filesCommand(cwd: String): String = "sh -c " + quote(
        "cd \"\$1\" 2>/dev/null || exit 0; " +
            "if git rev-parse --git-dir >/dev/null 2>&1; then GIT_OPTIONAL_LOCKS=0 git -c core.quotepath=off ls-files -co --exclude-standard | head -n $FILES_LIMIT; " +
            "else find . -maxdepth 6 \\( -name .git -o -name node_modules -o -name .gradle -o -name build -o -name target -o -name dist -o -name .venv \\) -prune -o -type f -print " +
            "| sed 's|^\\./||' | head -n $FILES_LIMIT; fi; exit 0",
    ) + " herdr-agent-files " + quote(cwd)

    /** The commands [listCommand] printed, named as [agent] runs them. */
    fun parse(agent: String, out: String): List<AgentCommand> {
        val found = mutableListOf<AgentCommand>()
        var head: List<String>? = null
        val body = StringBuilder()
        fun flush() {
            val h = head ?: return
            val (source, kind, raw) = Triple(h[0], h[1], h[2])
            val text = body.toString()
            val name = when (kind) {
                "skill" -> skillName(text) ?: raw
                else -> raw.replace('/', ':')
            }
            val shown = when {
                agent == "codex" && kind == "prompt" -> "prompts:$name"
                (agent == "pi" || agent == "omp") && kind == "skill" -> "skill:$name"
                else -> name
            }
            val src = when {
                kind == "skill" -> AgentCommand.Source.Skill
                source == "project" -> AgentCommand.Source.Project
                else -> AgentCommand.Source.User
            }
            if (shown.isNotBlank() && shown.none { it.isWhitespace() }) found += AgentCommand(shown, description(text).ifEmpty { "Run /$shown" }, src)
        }
        for (line in out.lineSequence()) {
            if (line.startsWith(MARK + "\t")) {
                flush()
                body.setLength(0)
                head = line.removePrefix(MARK + "\t").split('\t').takeIf { it.size >= 3 }
            } else if (head != null) body.append(line).append('\n')
        }
        flush()
        return found
    }

    /** The files [filesCommand] printed. */
    fun files(out: String): List<String> = out.lineSequence().map { it.trimEnd('\r') }.filter { it.isNotEmpty() }.toList()

    private val FRONT = Regex("^---\\s*\\n([\\s\\S]*?)\\n---(?:\\s*\\n|$)")

    /** A command file's `description:` (frontmatter), else its first line of text, as one short line. */
    internal fun description(markdown: String): String {
        val front = FRONT.find(markdown)
        front?.groupValues?.get(1)?.let { Regex("^description:\\s*(.+?)\\s*$", RegexOption.MULTILINE).find(it) }?.let {
            return it.groupValues[1].removeSurrounding("\"").removeSurrounding("'").take(120)
        }
        val rest = if (front != null) markdown.substring(front.range.last + 1) else markdown
        return rest.lineSequence().map { it.trim().trimStart('#').trim() }.firstOrNull { it.isNotEmpty() }.orEmpty().take(120)
    }

    private fun skillName(markdown: String): String? =
        FRONT.find(markdown)?.groupValues?.get(1)
            ?.let { Regex("^name:\\s*(.+?)\\s*$", RegexOption.MULTILINE).find(it)?.groupValues?.get(1) }
            ?.removeSurrounding("\"")?.removeSurrounding("'")
            ?.takeIf { Regex("^[\\p{L}\\p{N}_:-]+$").matches(it) }

    /** Reads an agent's commands and its folder's files on its machine: [run] runs a shell command with a timeout in seconds. */
    class Client(private val run: suspend (command: String, timeoutSeconds: Long) -> CommandResult) {
        /** [agent]'s built-in commands and the machine's own ones for it in [cwd]. */
        suspend fun commands(agent: String, cwd: String): List<AgentCommand> {
            val r = run(listCommand(agent, cwd), 20)
            if (r.exitStatus == null && r.stdout.isEmpty()) throw IOException("The machine took too long to answer")
            return merge(agent, parse(agent, r.stdout))
        }

        suspend fun files(cwd: String): List<String> {
            val r = run(filesCommand(cwd), 30)
            if (r.exitStatus == null && r.stdout.isEmpty()) throw IOException("The machine took too long to answer")
            return AgentCommands.files(r.stdout)
        }
    }
}
