package com.makefunstuff.herdr.core.start

import com.makefunstuff.herdr.core.api.HerdrApi
import com.makefunstuff.herdr.core.api.IntegrationInfo
import com.makefunstuff.herdr.core.api.SessionSnapshot
import com.makefunstuff.herdr.core.ssh.HerdrCommands.quote

/**
 * Starting work from a sentence: what you want first, then how (a board task, or an agent now) and
 * where (the machine, a folder). The agent is started as herdr starts it, with no arguments of the
 * app's own: no mode that skips the agent's permission prompts is ever offered from the phone.
 */
object StartWork {
    /** The longest title a board task gets; a longer sentence goes whole into its description. */
    const val TITLE = 72

    /** A board task's title and description from what was said: its first line, cut at a word when long. */
    fun task(text: String): Pair<String, String> {
        val t = text.trim()
        val first = t.lineSequence().first().trim()
        val title = if (first.length <= TITLE) first else first.take(TITLE).substringBeforeLast(' ').ifEmpty { first.take(TITLE) }.trimEnd(',', ';', ':', '.') + "…"
        // The description repeats nothing the title says in full.
        val desc = if (title == t) "" else t
        return title to desc
    }

    /**
     * A name for an agent started on [text]: its first few words as `add-last-answered`, kept apart
     * from the names already on the machine ([taken]) with -2, -3…; [kind] when nothing is said.
     */
    fun agentName(text: String, kind: String, taken: Collection<String>): String {
        val words = text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() && it !in SMALL }.take(3)
        val base = words.joinToString("-").take(32).trimEnd('-').ifEmpty { kind }
        if (base !in taken) return base
        var n = 2
        while ("$base-$n" in taken) n++
        return "$base-$n"
    }

    private val SMALL = setOf("a", "an", "the", "to", "of", "and", "for", "in", "on", "it", "is", "with", "please")

    /** The agents herdr can start on a machine, as the app offers them when it can't ask which are installed. */
    val KINDS = listOf("claude", "codex", "pi", "opencode", "amp", "droid", "copilot")

    /** The order the agents are offered in: the usual ones first, then the rest as herdr lists them. */
    private val FIRST = listOf("claude", "codex", "pi", "omp", "opencode", "gemini", "copilot", "droid", "amp", "cursor")

    /**
     * The agents installed on a machine, as `agent.start` names them, from herdr's integrations: the
     * ones whose program is there. herdr's target and the kind differ for two of them.
     */
    fun installed(integrations: List<IntegrationInfo>): List<String> =
        integrations.filter { it.available }.map { kindOf(it.target) }.distinct()
            .sortedBy { FIRST.indexOf(it).let { i -> if (i < 0) FIRST.size else i } }

    private fun kindOf(target: String): String = when (target) {
        "antigravity_cli" -> "agy"
        else -> target
    }

    /**
     * Folders to start in on a machine: the ones used from the phone first ([used], newest first),
     * then each workspace's (its checkout, else its first pane's; a task's worktree counts as the
     * repository it was made from, and one in herdr's worktrees folder whose repository herdr doesn't
     * name is left out), without repeats, at most [max].
     */
    fun recentFolders(snapshot: SessionSnapshot?, used: List<String>, max: Int = 6): List<String> {
        val workspaces = snapshot?.workspaces.orEmpty().mapNotNull { ws ->
            val tree = ws.worktree
            when {
                tree != null && tree.isLinkedWorktree && tree.repoRoot.isNotEmpty() -> tree.repoRoot
                tree != null && tree.checkoutPath.isNotEmpty() -> tree.checkoutPath
                else -> snapshot?.panes?.firstOrNull { it.workspaceId == ws.workspaceId }?.cwd?.ifEmpty { null }
            }?.takeIf { WORKTREES !in it }
        }
        return (used + workspaces).map { it.trimEnd('/').ifEmpty { "/" } }.distinct().take(max)
    }

    /** Where herdr makes its worktrees (`~/.herdr/worktrees/<repo>/<branch>`): a task's checkout, not a place to start new work. */
    private const val WORKTREES = "/.herdr/worktrees/"

    /** A folder's name, as a chip shows it: its last part. */
    fun folderName(path: String): String = path.trimEnd('/').substringAfterLast('/').ifEmpty { path }

    /** The workspace on [snapshot] that sits in [folder], so an agent started there opens as a tab of it. */
    fun workspaceIn(snapshot: SessionSnapshot?, folder: String): String? {
        val f = folder.trimEnd('/')
        return snapshot?.workspaces?.firstOrNull { ws ->
            ws.worktree?.checkoutPath?.trimEnd('/') == f || snapshot.panes.firstOrNull { it.workspaceId == ws.workspaceId }?.cwd?.trimEnd('/') == f
        }?.workspaceId
    }
}

/**
 * The folder browser: a folder on the machine and the folders in it, the ones that are Git
 * repositories marked. Hidden folders are left out.
 */
data class Folder(val path: String, val folders: List<Entry>) {
    data class Entry(val name: String, val repo: Boolean)

    /** The folder it is in; null at the top. */
    val parent: String?
        get() = if (path == "/") null else path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }

    fun child(name: String): String = if (path.endsWith('/')) path + name else "$path/$name"

    companion object {
        private const val MARK = "@@herdr-folder "

        // $1 the folder, or nothing for the home folder. Prints where it is, then R or D and a name for each folder in it.
        private val SCRIPT = """
            d="${'$'}{1:-${'$'}HOME}"
            case "${'$'}d" in "~") d="${'$'}HOME" ;; "~/"*) d="${'$'}HOME/${'$'}{d#\~/}" ;; esac
            cd -- "${'$'}d" 2>/dev/null || exit 3
            echo "$MARK${'$'}(pwd)"
            for e in */; do
              [ -d "${'$'}e" ] || continue
              n="${'$'}{e%/}"
              if [ -e "${'$'}n/.git" ]; then echo "R ${'$'}n"; else echo "D ${'$'}n"; fi
            done
        """.trimIndent()

        /** The command that lists [path] (the home folder when null). */
        fun command(path: String?): String = "sh -c ${quote(SCRIPT)} herdr-folder" + (path?.let { " " + quote(it) } ?: "")

        /** What [command] printed; null when the folder couldn't be opened. */
        fun parse(stdout: String): Folder? {
            val lines = stdout.lines()
            val at = lines.indexOfFirst { it.startsWith(MARK) }
            if (at < 0) return null
            val entries = lines.drop(at + 1).mapNotNull { l ->
                when {
                    l.startsWith("R ") -> Entry(l.substring(2), true)
                    l.startsWith("D ") -> Entry(l.substring(2), false)
                    else -> null
                }
            }.filter { it.name.isNotEmpty() && it.name != "*" }
            return Folder(lines[at].removePrefix(MARK).trim(), entries.sortedWith(compareBy({ !it.repo }, { it.name.lowercase() })))
        }
    }
}

/** Starting an agent now, with what it is asked first. */
object AgentLaunch {
    /**
     * Starts [kind] as [name] in [folder]: in a new tab of the workspace that sits there, else in a new
     * workspace named after the folder. Then gives it [text], when there is any. Returns its pane.
     */
    suspend fun inFolder(api: HerdrApi, snapshot: SessionSnapshot?, folder: String, kind: String, name: String, text: String): String {
        val ws = StartWork.workspaceIn(snapshot, folder)
        val pane = api.openShell(ws, if (ws != null) name else StartWork.folderName(folder), folder)
        return start(api, pane, kind, name, text)
    }

    /** Starts [kind] as [name] in a new split next to [anchor] (a workspace's focused pane), then gives it [text]. Returns its pane. */
    suspend fun beside(api: HerdrApi, anchor: String, kind: String, name: String, text: String): String {
        val before = api.snapshot().panes.map { it.paneId }.toSet()
        val pane = api.splitPane(anchor, "right")
            ?: api.snapshot().panes.firstOrNull { it.paneId !in before }?.paneId
            ?: throw java.io.IOException("herdr didn't say which pane it opened")
        return start(api, pane, kind, name, text)
    }

    private suspend fun start(api: HerdrApi, pane: String, kind: String, name: String, text: String): String {
        api.startAgent(name, kind, pane)
        if (text.isNotBlank()) api.prompt(pane, text.trim())
        return pane
    }
}
