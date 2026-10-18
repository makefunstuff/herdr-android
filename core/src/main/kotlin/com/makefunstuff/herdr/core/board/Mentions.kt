package com.makefunstuff.herdr.core.board

/**
 * The tasks the Coordinator names in an answer (`AND-6`, `API-3`), so the chat can show each as a
 * row with the task's live state. Only keys on the board count: "GPT-4" or "UTF-8" stay words.
 */
object TaskMentions {
    /** A task key as the board prints it: letters (and digits) from the repo's name, a dash, the task's number. */
    private val KEY = Regex("""(?<![\w-])([A-Z][A-Z0-9]{0,11}-[0-9]+)(?![\w-])""")

    /** A list item that starts with a key, bold or not: "- **AND-6** Add app screenshots", "1. API-3: Speed up". */
    private val ITEM = Regex("""^\s*(?:[-*+]|\d+[.)])\s+(?:\*\*|__|`)?([A-Z][A-Z0-9]{0,11}-[0-9]+)(?:\*\*|__|`)?(?:[\s:,.–—-]|$)""")

    /** Any list item, for telling where a list starts and ends. */
    private val LIST_ITEM = Regex("""^\s*(?:[-*+]|\d+[.)])\s+""")

    /** A piece of an answer: its own words, or a task it names with what the answer says of it there. */
    sealed interface Part {
        data class Words(val text: String) : Part
        /** [note]: the item's words after the key ("approve the push to main"), empty for a task named in passing. */
        data class Task(val key: String, val note: String = "") : Part
    }

    /** Every key in [text] in the order it first appears, once each. */
    fun keys(text: String): List<String> = KEY.findAll(text).map { it.groupValues[1] }.distinct().toList()

    /**
     * [text] with each list of tasks as rows in its place: a list whose every item starts with a key
     * in [known], each item's own words kept as its [Part.Task.note]. A list that mixes tasks with
     * other items stays words, numbering and all. The other keys in [known] it names come once each
     * after the words; not those in code. Nothing changes with no key known.
     */
    fun split(text: String, known: Set<String>): List<Part> {
        if (known.isEmpty()) return listOf(Part.Words(text))
        val lines = text.lines()
        // Which lines are code (a fence and what is between), where nothing is a list or a key.
        val code = BooleanArray(lines.size)
        var fence = false
        for ((i, line) in lines.withIndex()) {
            val marker = line.trimStart().startsWith("```")
            code[i] = fence || marker
            if (marker) fence = !fence
        }
        fun item(i: Int) = !code[i] && LIST_ITEM.containsMatchIn(lines[i])
        val out = mutableListOf<Part>()
        val words = StringBuilder()
        fun flush() {
            val w = words.toString().trim('\n')
            if (w.isNotBlank()) out += Part.Words(w)
            words.clear()
        }
        var i = 0
        while (i < lines.size) {
            if (!item(i)) {
                words.append(lines[i]).append('\n')
                i++
                continue
            }
            // A list: its items, each with the lines indented under it, and the blank lines between them.
            val items = mutableListOf<MutableList<String>>()
            var end = i
            var j = i
            while (j < lines.size) {
                val line = lines[j]
                when {
                    item(j) -> { items += mutableListOf(line); end = j + 1 }
                    line.isBlank() -> {}
                    !code[j] && line.first().isWhitespace() && items.isNotEmpty() && end == j -> { items.last() += line.trim(); end = j + 1 }
                    else -> break
                }
                j++
            }
            val tasks = items.map { lines -> ITEM.find(lines.first())?.takeIf { it.groupValues[1] in known }?.let { m -> m to lines } }
            if (tasks.all { it != null }) {
                flush()
                tasks.forEach { t ->
                    val (m, itemLines) = t!!
                    val first = itemLines.first().substring(m.range.last + 1).trimStart { it.isWhitespace() || it in ":–—-" }
                    out += Part.Task(m.groupValues[1], (listOf(first) + itemLines.drop(1)).filter { it.isNotBlank() }.joinToString("\n").trim())
                }
            } else {
                for (k in i until end) words.append(lines[k]).append('\n')
            }
            i = end
        }
        flush()
        // Named in passing ("waits for AND-2"): the task's row comes after the words, once; not for a key in code.
        val shown = out.filterIsInstance<Part.Task>().map { it.key }.toSet()
        val prose = out.flatMap { p ->
            when (p) {
                is Part.Words -> listOf(withoutCode(p.text))
                is Part.Task -> listOf(p.note)
            }
        }
        prose.flatMap { keys(it) }.filter { it in known && it !in shown }.distinct().forEach { out += Part.Task(it) }
        return if (out.isEmpty()) listOf(Part.Words(text)) else out
    }

    /** [text] without its fenced code blocks and `inline code`. */
    private fun withoutCode(text: String): String =
        text.replace(Regex("""```.*?(```|$)""", RegexOption.DOT_MATCHES_ALL), " ").replace(Regex("""`[^`]*`"""), " ")
}

/** A board at a glance, for the strip under the Coordinator's bar. */
data class BoardGlance(val needYou: Int, val working: Int, val queued: Int) {
    val quiet: Boolean get() = needYou == 0 && working == 0 && queued == 0

    companion object {
        /** Counted as the Board tab counts them: waiting for you (approvals too), at work (reviews too), waiting their turn. */
        fun of(tasks: List<BoardTask>): BoardGlance {
            val live = tasks.filter { !it.archived }
            return BoardGlance(
                needYou = live.count { it.needsYou },
                working = live.count { !it.needsYou && (it.status == BoardStatus.Working || it.status == BoardStatus.Reviewing) },
                queued = live.count { it.status == BoardStatus.Queued },
            )
        }
    }
}

/**
 * The Coordinator's suggested asks, above its composer: only the ones that are a next step on
 * this board now. [label] is the chip, [text] what it puts in the message box.
 */
data class CoordinatorAsk(val label: String, val text: String) {
    companion object {
        /**
         * "What needs me?" when something waits for you, "Start the queued ones" when tasks wait
         * their turn, and "Status of <task>" for the open task the conversation named last (else
         * the first one at work). [said] is the conversation, oldest first.
         */
        fun of(tasks: List<BoardTask>, said: List<String>): List<CoordinatorAsk> {
            val byKey = tasks.filter { it.key.isNotEmpty() && !it.archived }.associateBy { it.key }
            val glance = BoardGlance.of(tasks)
            val open = { t: BoardTask -> t.status != BoardStatus.Done && t.status != BoardStatus.Archived }
            val named = said.asReversed().asSequence().flatMap { TaskMentions.keys(it).asReversed().asSequence() }
                .mapNotNull { byKey[it] }.firstOrNull(open)
            val status = named ?: byKey.values.firstOrNull { it.needsYou } ?: byKey.values.firstOrNull { it.status == BoardStatus.Working }
            return buildList {
                if (glance.needYou > 0) add(CoordinatorAsk("What needs me?", "What needs me?"))
                if (glance.queued > 0) add(CoordinatorAsk("Start the queued ones", "Start the queued ones."))
                status?.let { add(CoordinatorAsk("Status of ${it.key}", "What's the status of ${it.key}?")) }
            }
        }
    }
}
