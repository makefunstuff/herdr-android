package com.makefunstuff.herdr.core.board

import com.makefunstuff.herdr.core.chat.Markdown
import com.makefunstuff.herdr.core.chat.ModelUse

/**
 * A task's status message as a person reads it. The board's `msg` is often an agent's own words, and
 * sometimes what its terminal showed last: a status bar of paths and counters
 * (`~/.herdr/worktrees/… ↑1.6M ↓807k R64M 73.6%/197k (auto) (strata) model • low`), or a closing
 * line ending in where the work was kept. Those parts say nothing at a glance and are left out.
 */
object TaskLine {
    /**
     * A token counter as a status bar prints it, sized in thousands or millions (`↑1.6M`, `↓807k`, `R64M`, `W12k`),
     * or a context gauge (`73.6%/197k`). A person's `↓12`, `↑3%` or `↓30ms` is none of these.
     */
    private const val GAUGE = """(?:(?:[↑↓]|\b[RW])\d+(?:[.,]\d+)?[kKmM]\b|\b\d+(?:\.\d+)?%/\d+(?:[.,]\d+)?[kKmM]?\b)"""
    private val COUNTER = Regex(GAUGE)

    /** Two of them side by side, as only a status bar has them. */
    private val RUN = Regex("""$GAUGE\s+$GAUGE""")

    /** "Kept: <path> (unmerged work on <branch>)" and "Report: <path>", the board's notes on where things are: only with a path after them. */
    private val WHERE = Regex("""\b(?:Kept|Report):\s+(?:~/|/)\S+(?:\s+\([^)]*\))?\.?""")

    /** A path in someone's home on the machine: `~/…`, `/home/<user>/…`, `/Users/<user>/…`; not a route (`/api/v1`) nor a link's target. */
    private val PATH = Regex("""(?<![\w:/])(?<!\]\()(?:~|/home/[\w.-]+|/Users/[\w.-]+|/root)(?:/[\w.@+-]+)+/?""")

    /** What is left of a status bar once its paths, counters and `(tags)` are gone: a model and an effort at most. */
    private val BAR_LEFT = Regex("""\([^)]*\)|[•|·]""")

    /** A GitHub pull request link, said as "PR #12". */
    private val PULL = Regex("""<?https?://github\.com/[\w.-]+/[\w.-]+/pull/(\d+)(?:[/?#][^\s>)]*[^\s>).,;])?>?""")

    /** The whole message, cleaned: Markdown kept, the noise taken out. Empty when nothing human is left. */
    fun clean(msg: String): String {
        var s = msg.trim()
        if (s.isEmpty()) return ""
        // A terminal's status bar is all noise: nothing of it is kept.
        if (isStatusBar(s)) return ""
        s = PULL.replace(s) { "PR #" + it.groupValues[1] }
        s = WHERE.replace(s, " ")
        s = PATH.replace(s) { m -> m.value.trimEnd('/').substringAfterLast('/') }
        s = s.lines().joinToString("\n") { it.replace(Regex("""[ \t]{2,}"""), " ").trimEnd() }
            .replace(Regex("""[ \t]+([.,;:])"""), "$1")
            .replace(Regex("""\(\s*\)"""), "")
            .trim().trimEnd(':', ',', ';').trim()
        return s
    }

    /** One plain line for a card: the cleaned message without Markdown, its first line. */
    fun line(msg: String): String = Markdown.plain(clean(msg)).trim()

    /**
     * Whether [s] reads as an agent's terminal status bar: sized counters side by side (`↑1.6M ↓807k`,
     * `R64M 73.6%/197k`) and, once they, its paths and its `(tags)` are gone, two words at most (a model and an
     * effort). A person's line with numbers in it (`Errors ↓12, latency ↓40%`) keeps its words.
     */
    fun isStatusBar(s: String): Boolean {
        if (!RUN.containsMatchIn(s)) return false
        val left = BAR_LEFT.replace(COUNTER.replace(PATH.replace(s, " "), " "), " ")
        return left.split(Regex("""\s+""")).count { w -> w.count { it.isLetter() } >= 2 } <= 2
    }

    /**
     * Who works on the task, as "claude · Opus 5.5": [agent] (the task's) with the model of the first of the
     * task's [workers] (`claude/claude-opus-5-5`, `pi/deepseek/deepseek-flash`) run by that agent; the agent
     * alone when none is; empty without an agent.
     */
    fun who(agent: String, workers: List<String>): String {
        val a = agent.trim()
        val spec = workers.firstOrNull { a.isEmpty() || it.substringBefore('/') == a } ?: return a
        val name = a.ifEmpty { spec.substringBefore('/') }
        val model = spec.substringAfter('/', "").ifEmpty { return name }
        return "$name · ${ModelUse.name(model) ?: model}"
    }
}
