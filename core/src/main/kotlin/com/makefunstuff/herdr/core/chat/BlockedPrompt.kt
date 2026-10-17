package com.makefunstuff.herdr.core.chat

/**
 * What a blocked agent is asking, read off its screen: the question, the lines that explain it
 * (the command it wants to run, the folder it wants to trust), and the choices, if it offers any.
 */
data class BlockedPrompt(
    val question: String,
    val details: List<String>,
    val options: List<PromptOption>,
    /** Which option the agent's cursor is on. */
    val selected: Int,
    /** The agent's own footer, such as "Esc to cancel · Tab to amend". */
    val hint: String?,
) {
    /**
     * Keys that pick option [index]: move the cursor there, then Enter. This works for claude,
     * codex and pi menus alike, numbered or not.
     */
    fun keysFor(index: Int): List<String> {
        val delta = index - selected
        val move = if (delta >= 0) "down" else "up"
        return List(kotlin.math.abs(delta)) { move } + "enter"
    }

    /** The option that says yes ("Yes", "Yes, proceed", "Trust and continue"), when one does. */
    val approveIndex: Int? get() = options.indexOfFirst { YES.containsMatchIn(it.label) }.takeIf { it >= 0 }

    /** The option that says no ("No", "No, exit", "No, and tell Codex what to do differently"), when one does. */
    val denyIndex: Int? get() = options.indexOfFirst { NO.containsMatchIn(it.label) }.takeIf { it >= 0 }

    /** A plain yes-or-no question: both a yes and a no option, and they differ. */
    val yesNo: Boolean get() = approveIndex != null && denyIndex != null && approveIndex != denyIndex

    companion object {
        private val YES = Regex("^(yes|allow|approve|proceed|accept|trust|continue|ok)\\b", RegexOption.IGNORE_CASE)
        private val NO = Regex("^(no|deny|reject|decline|cancel|don'?t|do not)\\b", RegexOption.IGNORE_CASE)
        private val CURSOR = Regex("^(\\s*)[❯›▶►➤→>]\\s+(\\S.*)$")
        private val NUMBERED = Regex("^(\\d{1,2})[.)]\\s+(.+)$")
        private val HARD_RULE = Regex("^[─━═▔▁]{6,}$")
        private val SOFT_RULE = Regex("^[╌┄┈╍┅┉\\-–—·.]{6,}$")
        private val BOX_EDGE = Regex("^[╭╮╰╯│┃]")

        /** Parses the bottom of a blocked agent's screen; null when no menu is visible. */
        fun parse(screen: String): BlockedPrompt? {
            val lines = screen.lines().map(::unbox)
            val width = lines.maxOfOrNull { it.length } ?: return null
            val cursorAt = lines.indices.lastOrNull { CURSOR.matches(lines[it]) } ?: return null
            val match = CURSOR.matchEntire(lines[cursorAt])!!
            val textCol = match.groups[2]!!.range.first
            val numbered = NUMBERED.matches(match.groupValues[2])

            fun indent(line: String) = line.length - line.trimStart().length
            fun isOption(i: Int): Boolean {
                val line = lines[i]
                if (i == cursorAt) return true
                if (line.isBlank() || indent(line) != textCol) return false
                return !numbered || NUMBERED.matches(line.trim())
            }
            fun isContinuation(i: Int) = lines[i].isNotBlank() && indent(lines[i]) > textCol

            var start = cursorAt
            while (start > 0 && (isOption(start - 1) || isContinuation(start - 1))) start--
            while (start < cursorAt && !isOption(start)) start++
            var end = cursorAt
            while (end + 1 < lines.size && (isOption(end + 1) || isContinuation(end + 1))) end++

            val options = mutableListOf<PromptOption>()
            var selected = 0
            var previous = ""
            for (i in start..end) {
                val raw = if (i == cursorAt) match.groupValues[2] else lines[i].trim()
                if (isOption(i)) {
                    if (i == cursorAt) selected = options.size
                    val num = NUMBERED.matchEntire(raw)
                    options += PromptOption(num?.groupValues?.get(2)?.trim() ?: raw, num?.groupValues?.get(1)?.toInt())
                } else if (options.isNotEmpty()) {
                    // A line that reached the right edge wrapped, so the next one continues it (with no
                    // space when it was cut mid-word); after a short line, the text describes the option.
                    val last = options.removeAt(options.size - 1)
                    val joiner = if (previous.length >= width) "" else " "
                    options += when {
                        last.description != null -> last.copy(description = last.description + (if (previous.length >= width - 12) joiner else " ") + raw)
                        previous.length >= width - 12 -> last.copy(label = last.label + joiner + raw)
                        else -> last.copy(description = raw)
                    }
                }
                previous = lines[i]
            }
            if (options.isEmpty()) return null

            val above = context(lines, start, width)
            val questionAt = above.indexOfLast { it.endsWith("?") }.takeIf { it >= 0 }
                ?: above.indexOfLast { it.contains("? ") }.takeIf { it >= 0 }
                ?: 0
            val question = above.getOrNull(questionAt) ?: "The agent needs your answer"
            val details = above.filterIndexed { i, _ -> i != questionAt }
            val hint = lines.drop(end + 1).take(3).firstOrNull { it.isNotBlank() }?.trim()
            return BlockedPrompt(question, details, options, selected, hint)
        }

        /** The lines above the menu that explain it, joined back into sentences where they wrapped. */
        private fun context(lines: List<String>, menuStart: Int, width: Int): List<String> {
            val picked = mutableListOf<String>()
            var i = menuStart - 1
            while (i >= 0 && picked.size < 14) {
                val line = lines[i]
                val text = line.trim()
                if (HARD_RULE.matches(text) || CURSOR.matches(line) || text.startsWith("•") || text.startsWith("●") || BOX_EDGE.containsMatchIn(text)) break
                picked.add(0, line)
                i--
            }
            val out = mutableListOf<String>()
            var prev: String? = null
            for (line in picked) {
                val text = line.trim()
                if (text.isEmpty() || SOFT_RULE.matches(text)) {
                    prev = null
                    continue
                }
                val p = prev
                when {
                    p != null && p.length >= width -> out[out.size - 1] = out.last() + text
                    p != null && text.first().isLowerCase() && !out.last().endsWith(".") && !out.last().endsWith(":") ->
                        out[out.size - 1] = out.last() + " " + text
                    else -> out += text
                }
                prev = line
            }
            return out
        }

        /** Drops the side borders of a boxed dialog so its contents parse like any other screen. */
        private fun unbox(line: String): String {
            val t = line.trimEnd()
            val lead = t.indexOfFirst { !it.isWhitespace() }
            if (lead < 0) return ""
            if ((t[lead] == '│' || t[lead] == '┃') && t.length > lead + 1 && (t.last() == '│' || t.last() == '┃')) {
                return " ".repeat(lead) + t.substring(lead + 1, t.length - 1).trimEnd()
            }
            return t
        }
    }
}

data class PromptOption(val label: String, val number: Int? = null, val description: String? = null)
