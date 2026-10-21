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
    /** A token counter (`↑1.6M`, `↓807k`, `R64M`, `W12k`) or a context gauge (`73.6%/197k`). */
    private val COUNTER = Regex("""(?:[↑↓]\s?\d[\d.,]*[kKmMbB]?|\b[RW]\d[\d.,]*[kKmMbB]\b|\b\d+(?:\.\d+)?%/\d[\d.,]*[kKmM]?\b)""")

    /** "Kept: <path> (unmerged work on <branch>)" and "Report: <path>", the board's notes on where things are. */
    private val WHERE = Regex("""\b(?:Kept|Report|Worktree|Branch|Logs?):\s+\S+(?:\s+\([^)]*\))?\.?""", RegexOption.IGNORE_CASE)

    /** A path on the machine: `~/…`, `/home/…`, or any absolute one at least two folders deep. */
    private val PATH = Regex("""(?<![\w:/])(?:~|/[\w.-]+)(?:/[\w.@+-]+)+/?""")

    /** A GitHub pull request link, said as "PR #12". */
    private val PULL = Regex("""<?https?://github\.com/[\w.-]+/[\w.-]+/pull/(\d+)(?:[/?#][^\s>)]*[^\s>).,;])?>?""")

    /** The tail of an agent's status bar: `(auto)`, `(strata)`, `model • low`. */
    private val BAR_TAIL = Regex("""\s*(?:\([\w .-]+\)\s*)+(?:\S+\s*•\s*\S+)?\s*$""")

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

    /** Whether [s] reads as an agent's terminal status bar: token counters, or a path with a context gauge. */
    fun isStatusBar(s: String): Boolean {
        val counters = COUNTER.findAll(s).count()
        if (counters >= 2) return true
        if (counters == 1 && (PATH.containsMatchIn(s) || '•' in s)) return true
        // A path and the bar's own tail, without counters.
        return PATH.containsMatchIn(s) && BAR_TAIL.containsMatchIn(s) && '•' in s
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
