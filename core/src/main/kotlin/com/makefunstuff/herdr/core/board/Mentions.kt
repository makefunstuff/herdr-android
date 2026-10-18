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

    /** A piece of an answer: its own words, or a task it names. */
    sealed interface Part {
        data class Words(val text: String) : Part
        data class Task(val key: String) : Part
    }

    /** Every key in [text] in the order it first appears, once each. */
    fun keys(text: String): List<String> = KEY.findAll(text).map { it.groupValues[1] }.distinct().toList()

    /**
     * [text] with each list item that starts with a key in [known] as that task, in its place; the
     * other keys in [known] it names come once each after the words. Nothing changes with no key known.
     */
    fun split(text: String, known: Set<String>): List<Part> {
        if (known.isEmpty()) return listOf(Part.Words(text))
        val out = mutableListOf<Part>()
        val shown = LinkedHashSet<String>()
        val words = StringBuilder()
        fun flush() {
            val w = words.toString().trim('\n')
            if (w.isNotBlank()) out += Part.Words(w)
            words.clear()
        }
        var fence = false
        for (line in text.lines()) {
            if (line.trimStart().startsWith("```")) fence = !fence
            val key = if (fence) null else ITEM.find(line)?.groupValues?.get(1)?.takeIf { it in known && it !in shown }
            if (key != null) {
                flush()
                out += Part.Task(key)
                shown += key
            } else {
                // A blank line between two task rows is the list's, not the answer's.
                if (line.isBlank() && out.lastOrNull() is Part.Task && words.isEmpty()) continue
                words.append(line).append('\n')
            }
        }
        flush()
        // Named in passing ("waits for AND-2"): the task's row comes after the words.
        val rest = out.filterIsInstance<Part.Words>().flatMap { keys(it.text) }.filter { it in known && it !in shown }.distinct()
        rest.forEach { out += Part.Task(it) }
        return if (out.isEmpty()) listOf(Part.Words(text)) else out
    }
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
