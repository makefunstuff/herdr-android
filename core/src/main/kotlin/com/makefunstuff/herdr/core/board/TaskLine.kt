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
    /** A footer's counter: tokens in (`↑850`, `↑1.6M`), out, cache read and written (`R64M`, `W12k`), the cache hit (`CH99.8%`), the cost (`$0.845 (sub)`). */
    private const val STAT = """(?:[↑↓RW]\d+(?:\.\d+)?[kM]?|CH\d+(?:\.\d+)?%|\$\d+\.\d+(?:\s+\(sub\))?)"""

    /** Its context gauge, always there: `73.6%/197k`, `?/1.0M` just after a compaction. */
    private const val GAUGE = """(?:\d+(?:\.\d+)?%|\?)/\d+(?:\.\d+)?[kM]?"""

    /** A model on its right, with its thinking level: `claude-opus-5-5 • high`, `qwen3-coder • thinking off`. */
    private const val MODEL = """\S+(?:\s+•\s+(?:thinking\s+off|[\w-]+))?"""

    /**
     * The last two lines of a coding agent's terminal (pi's footer) as the board keeps them on one line: where it
     * works (`~/…/work-50 (work/50-…)`, maybe `• <session>`), then its counters, its context gauge, `(auto)`, and
     * the model (`(anthropic) claude-opus-5-5 • low`, or a routed `auto • low → claude-opus-5-5 • high`).
     */
    private val FOOTER = Regex(
        """^(?:\S+(?:\s+\([^)]*\))?(?:\s+•\s+[^↑↓%]*?)?\s+)?((?:$STAT\s+)*)$GAUGE(\s+\(auto\))?(?:\s+•\s+xp)?""" +
            """(?:\s+(\([\w.-]+\)\s+)?($MODEL(?:\s+→\s+$MODEL)*))?\s*$""",
    )

    /** "Kept: <path> (unmerged work on <branch>)" and "Report: <path>", the board's notes on where things are: only with a path after them. */
    private val WHERE = Regex("""\b(?:Kept|Report):\s+(?:~/|/)\S+(?:\s+\([^)]*\))?\.?""")

    /** A path in someone's home on the machine: `~/…`, `/home/<user>/…`, `/Users/<user>/…`; not a route (`/api/v1`) nor a link's target. */
    private val PATH = Regex("""(?<![\w:/])(?<!\]\()(?:~|/home/[\w.-]+|/Users/[\w.-]+|/root)(?:/[\w.@+-]+)+/?""")

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
     * Whether [s] reads as an agent's terminal footer, [FOOTER] from end to end. A person's line that merely ends
     * in a gauge (`Usage at 40%/200k now`) has no counter, no `(auto)` and no model name of the footer's shape, and stays.
     */
    fun isStatusBar(s: String): Boolean {
        val m = FOOTER.matchEntire(s.trim()) ?: return false
        val (stats, auto, provider, model) = m.destructured
        return stats.isNotBlank() || auto.isNotEmpty() || provider.isNotEmpty() || model.contains('•') || model.any { it.isDigit() || it == '-' }
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
