package com.makefunstuff.herdr.core.board

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException

/**
 * The board's automations ("routines" on the phone): a saved task plus a trigger, a schedule or
 * something that happens on the board. Each firing adds a task and puts an agent on it. All of it
 * lives on the machine (`herdr-projects automations …`); the phone reads it with `--json` and changes
 * it with the same commands a person types.
 */
data class Automation(
    /** The board's eight-letter id: every command takes it, and it stays when the name changes. */
    val id: String,
    val name: String,
    val on: Boolean,
    val trigger: Trigger,
    /** The repo its tasks go to, by name, and its folder there. */
    val repo: String,
    val folder: String = "",
    /** Its tasks are research only: a report, nothing changed in the repo. */
    val research: Boolean = false,
    /** What each of its tasks is to do: the task's description. */
    val prompt: String = "",
    /** The work settings that are its own (`worker.agent`, `research.only`, …), as the board says them. */
    val settings: Map<String, String> = emptyMap(),
    /** Its latest firing, null before the first. */
    val last: Firing? = null,
    /** When a schedule next fires (ms), null while off or for an event. */
    val next: Long? = null,
    /** Why the board turned it off itself ("Turned itself off: more than 10 firings in an hour"). */
    val offWhy: String = "",
) {
    /** The agent its tasks get, when it has one of its own; else the board's. */
    val agent: String get() = settings["worker.agent"].orEmpty()
}

/** One firing: when, what fired it, and the task it made or why it was skipped. */
data class Firing(
    val at: Long,
    /** `schedule`, `event` or `run`. */
    val cause: String,
    /** What fired it, in the board's words: "mon 07:00", "herdr-android/3 was merged", "run now". */
    val what: String,
    /** The task it made (`repo/12`), or empty when it was skipped. */
    val task: String = "",
    /** Why it was skipped. */
    val skipped: String = "",
    /** Said of a task that was added but got no agent ("added, but no agent could be put on it: …"). */
    val note: String = "",
    /** The same skip, this many times in a row. */
    val times: Int = 1,
)

/** What starts an automation: a schedule, or something that happens to a task on the board. */
sealed interface Trigger {
    /** As the board takes it: `every 2h`, `daily 09:00`, `mon,thu 09:00`, `cron 0 9 * * 1`. */
    data class Schedule(val text: String) : Trigger

    data class Event(val event: BoardEvent, val everyRepo: Boolean = false) : Trigger

    /** `--schedule …` or `--when … [--every-repo]`. */
    fun args(): List<String> = when (this) {
        is Schedule -> listOf("--schedule", text)
        is Event -> listOf("--when", event.id) + if (everyRepo) listOf("--every-repo") else emptyList()
    }

    companion object {
        /**
         * The trigger from the board's plain words for it (`automations --json` says it so): an event
         * reads "when a task is merged[, in every repo]", a schedule is its own text.
         */
        fun of(words: String): Trigger {
            val w = words.trim()
            if (w.startsWith("when a task ")) {
                val every = w.endsWith(", in every repo")
                val what = w.removePrefix("when a task ").removeSuffix(", in every repo").trim()
                BoardEvent.entries.firstOrNull { it.words == what }?.let { return Event(it, every) }
            }
            return Schedule(w)
        }
    }
}

/** What can happen to a task on the board and fire an automation (herdr-projects `automations/events.rs`). */
enum class BoardEvent(val id: String, val words: String, val label: String) {
    Added("added", "is added", "A task is added"),
    Approval("approval", "waits for approval", "A task waits for approval"),
    ReviewFailed("review-failed", "failed its review", "A task fails its review"),
    Failed("failed", "went wrong", "A task goes wrong"),
    NeedsYou("needs-you", "needs you", "A task needs you"),
    Merged("merged", "is merged", "A task is merged"),
}

/**
 * A schedule as the phone's form builds it, and back from the board's text. The board takes
 * `every 30m`, `every 2h`, `daily 09:00`, `mon,thu 09:00` and `cron "<five fields>"`, in the
 * machine's time zone.
 */
sealed interface Schedule {
    data class Every(val count: Int, val hours: Boolean) : Schedule
    data class Daily(val time: String) : Schedule
    /** [days] by the board's names (`mon` … `sun`), in the week's order. */
    data class Days(val days: List<String>, val time: String) : Schedule
    data class Cron(val fields: String) : Schedule

    /** The board's text for it, or null while it is not a schedule yet (no days, a time like 7:5). */
    fun text(): String? = when (this) {
        is Every -> if (count > 0) "every $count${if (hours) "h" else "m"}" else null
        is Daily -> clock(time)?.let { "daily $it" }
        is Days -> clock(time)?.takeIf { days.isNotEmpty() }?.let { t -> "${DAYS.filter { it in days }.joinToString(",")} $t" }
        is Cron -> fields.trim().split(Regex("\\s+")).takeIf { it.size == 5 }?.let { "cron ${it.joinToString(" ")}" }
    }

    companion object {
        val DAYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

        /** `7:00` and `07:00` as `07:00`; null for anything that isn't a time of day. */
        fun clock(text: String): String? {
            val m = Regex("""^(\d{1,2}):(\d{2})$""").find(text.trim()) ?: return null
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            return if (h < 24 && min < 60) "%02d:%02d".format(h, min) else null
        }

        /** The form's parts of the board's text; a text the form can't show as parts is a cron or kept as it is. */
        fun of(text: String): Schedule? {
            val parts = text.trim().lowercase().split(Regex("\\s+"))
            val word = parts.firstOrNull() ?: return null
            if (word == "cron") return Cron(parts.drop(1).joinToString(" ").trim('"', '\''))
            if (parts.size != 2) return null
            if (word == "every") {
                val m = Regex("""^(\d+)([mh])$""").find(parts[1]) ?: return null
                return Every(m.groupValues[1].toInt(), m.groupValues[2] == "h")
            }
            val time = clock(parts[1]) ?: return null
            if (word == "daily") return Daily(time)
            val days = word.split(',')
            return if (days.all { it in DAYS }) Days(days, time) else null
        }
    }
}

/** How an automation is said on the phone. */
object AutomationWords {
    private val DAY_NAMES = mapOf(
        "mon" to "Monday", "tue" to "Tuesday", "wed" to "Wednesday", "thu" to "Thursday", "fri" to "Friday", "sat" to "Saturday", "sun" to "Sunday",
    )

    /** "Mondays at 07:00", "Every 2 hours", "When a task is merged, in any repo". */
    fun trigger(t: Trigger): String = when (t) {
        is Trigger.Event -> "When a task ${t.event.words}" + if (t.everyRepo) ", in any repo" else ""
        is Trigger.Schedule -> when (val s = Schedule.of(t.text)) {
            is Schedule.Every -> when {
                s.hours && s.count == 1 -> "Every hour"
                s.hours && s.count % 24 == 0 -> if (s.count == 24) "Every 24 hours" else "Every ${s.count / 24} days"
                s.hours -> "Every ${s.count} hours"
                s.count == 1 -> "Every minute"
                s.count % 60 == 0 -> if (s.count == 60) "Every hour" else "Every ${s.count / 60} hours"
                else -> "Every ${s.count} minutes"
            }
            is Schedule.Daily -> "Daily at ${s.time}"
            is Schedule.Days -> "${days(s.days)} at ${s.time}"
            is Schedule.Cron -> "Cron ${s.fields}"
            null -> t.text
        }
    }

    /** The trigger inside a sentence: "on Mondays at 07:00", "every 2 hours", "daily at 02:00", "when a task is merged". */
    fun inSentence(t: Trigger): String {
        val w = trigger(t)
        return if (DAY_NAMES.values.any { w.startsWith(it) }) "on $w"
        else if (w.startsWith("Weekdays") || w.startsWith("Weekends")) "on " + w.replaceFirstChar { it.lowercase() }
        else w.replaceFirstChar { it.lowercase() }
    }

    /** "Mondays", "Mondays and Thursdays", "Weekdays", "Weekends", "Daily". */
    fun days(days: List<String>): String {
        val set = days.toSet()
        return when {
            set.size == 7 -> "Daily"
            set == setOf("mon", "tue", "wed", "thu", "fri") -> "Weekdays"
            set == setOf("sat", "sun") -> "Weekends"
            else -> {
                val names = Schedule.DAYS.filter { it in set }.map { DAY_NAMES.getValue(it) + "s" }
                if (names.size == 1) names[0] else names.dropLast(1).joinToString(", ") + " and " + names.last()
            }
        }
    }

    /** "just now", "5 min ago", "6 h ago", "3 days ago". */
    fun ago(at: Long, now: Long): String = span(now - at)?.let { "$it ago" } ?: "just now"

    /** "in 5 min", "in 8 h", "in 2 days", "now". */
    fun until(at: Long, now: Long): String = span(at - now)?.let { "in $it" } ?: "now"

    private fun span(ms: Long): String? {
        val min = ms / 60_000
        return when {
            min < 1 -> null
            min < 60 -> "$min min"
            min < 48 * 60 -> "${min / 60} h"
            else -> "${min / (24 * 60)} days"
        }
    }
}

/** How the last firing turned out, as one line on a routine's card. */
data class Outcome(
    val text: String,
    val mood: Mood,
    /** The task it made (`repo/12`), to open; empty when there is none. */
    val task: String = "",
) {
    enum class Mood {
        /** Done, merged, report ready, waiting for a yes. */
        Good,
        /** Its task is at work. */
        Working,
        /** It went wrong, stopped or asks you: worth a look. */
        Stuck,
        /** Never fired, skipped, paused. */
        Quiet,
    }

    companion object {
        /**
         * The line for [a] at [now], with [task] the board's row of the task its last firing made
         * (null where the board no longer lists it).
         */
        fun of(a: Automation, task: BoardTask?, now: Long): Outcome {
            val last = a.last
            if (!a.on && a.offWhy.isNotEmpty()) return Outcome(a.offWhy, Mood.Stuck, last?.task.orEmpty())
            if (!a.on) return Outcome("Paused" + (last?.let { " · last run ${AutomationWords.ago(it.at, now)}" } ?: " · never run"), Mood.Quiet, last?.task.orEmpty())
            if (last == null) return Outcome("Never run yet" + (a.next?.let { " · next ${AutomationWords.until(it, now)}" }.orEmpty()), Mood.Quiet)
            val ago = AutomationWords.ago(last.at, now)
            if (last.task.isEmpty()) {
                val times = if (last.times > 1) " (${last.times} times)" else ""
                return Outcome("Last run skipped$times: ${last.skipped} · $ago", Mood.Quiet)
            }
            val name = task?.name ?: last.task
            if (last.note.isNotEmpty() && (task == null || task.status == BoardStatus.Todo)) {
                return Outcome("Last run added $name, but no agent started · $ago", Mood.Stuck, last.task)
            }
            if (task == null) return Outcome("Last run: $name · $ago", Mood.Quiet, last.task)
            return when (task.status) {
                BoardStatus.Approval -> Outcome("Last run: $name ready to approve · $ago", Mood.Good, last.task)
                BoardStatus.Done -> Outcome("Last run: $name ${if (task.research) "report ready" else "done"} · $ago", Mood.Good, last.task)
                BoardStatus.Working, BoardStatus.Reviewing, BoardStatus.Queued ->
                    Outcome("Last run: $name ${task.status.label} · $ago", Mood.Working, last.task)
                BoardStatus.Asks, BoardStatus.AsksOk, BoardStatus.NeedsLogin, BoardStatus.PlanReady, BoardStatus.PickOne ->
                    Outcome("Last run asks you ($name)", Mood.Stuck, last.task)
                BoardStatus.ReviewFailed, BoardStatus.Error, BoardStatus.Stopped -> Outcome("Last run got stuck ($name)", Mood.Stuck, last.task)
                BoardStatus.Todo -> Outcome("Last run: $name added · $ago", Mood.Quiet, last.task)
                BoardStatus.Archived -> Outcome("Last run: $name archived · $ago", Mood.Quiet, last.task)
            }
        }
    }
}

/** What the phone's form makes of a routine: everything `automations add` takes. */
data class AutomationDraft(
    val name: String,
    val prompt: String,
    val repo: String,
    val trigger: Trigger,
    val research: Boolean = false,
    /** The agent its tasks get (`claude/claude-opus-5-5`); empty: the board's own. */
    val agent: String = "",
) {
    companion object {
        fun of(a: Automation) = AutomationDraft(a.name, a.prompt, a.repo, a.trigger, a.research, a.agent)
    }
}

/** The board's own commands for its automations, as argument lists for `herdr-projects`. */
object AutomationCommands {
    fun list() = listOf("automations", "--json")
    fun show(id: String) = listOf("automations", "show", id, "--json")
    fun turn(id: String, on: Boolean) = listOf("automations", if (on) "on" else "off", id)
    fun run(id: String) = listOf("automations", "run", id)
    fun remove(id: String) = listOf("automations", "remove", id)

    fun add(d: AutomationDraft): List<String> = buildList {
        add("automations"); add("add"); add(d.name.trim())
        add("--prompt"); add(d.prompt.trim())
        addAll(d.trigger.args())
        if (d.repo.isNotBlank()) { add("--repo"); add(d.repo.trim()) }
        if (d.research) add("--research")
        if (d.agent.isNotBlank()) { add("--worker.agent"); add(d.agent) }
    }

    /**
     * What turns [old] into [d], one command each: `change` for its name, prompt, trigger and
     * research, `set`/`unset worker.agent --automation` for its agent. Empty when nothing changed.
     * The repo is not one of them: the board keeps an automation in the repo it was made in.
     */
    fun change(old: Automation, d: AutomationDraft): List<List<String>> = buildList {
        val args = buildList {
            if (d.name.trim() != old.name) { add("--name"); add(d.name.trim()) }
            if (d.prompt.trim() != old.prompt.trim()) { add("--prompt"); add(d.prompt.trim()) }
            if (d.trigger != old.trigger) addAll(d.trigger.args())
            if (d.research != old.research) add(if (d.research) "--research" else "--no-research")
        }
        if (args.isNotEmpty()) add(listOf("automations", "change", old.id) + args)
        if (d.agent != old.agent) {
            add(if (d.agent.isBlank()) listOf("unset", "worker.agent", "--automation", old.id) else listOf("set", "worker.agent", d.agent, "--automation", old.id))
        }
    }
}

/** `automations --json` and `automations show <id> --json`, wherever a login shell printed around them. */
object AutomationReader {
    fun list(stdout: String): List<Automation> {
        val arr = BoardTasks.element(stdout, '[') as? JsonArray
            ?: if (stdout.lines().any { it.trim() == "[]" }) return emptyList()
            else throw IOException(BoardTasks.plain(stdout).ifEmpty { "herdr-projects gave no automations" })
        return arr.mapNotNull { (it as? JsonObject)?.let(::automation) }
    }

    /** One automation with all its firings, newest first. */
    fun show(stdout: String): Pair<Automation, List<Firing>> {
        val o = BoardTasks.element(stdout, '{') as? JsonObject
        val a = o?.let(::automation) ?: throw IOException(BoardTasks.plain(stdout).ifEmpty { "herdr-projects gave no automation" })
        return a to (o["firings"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::firing) }
    }

    fun automation(o: JsonObject): Automation? {
        val id = o.str("id").ifEmpty { return null }
        return Automation(
            id = id,
            name = o.str("name").ifEmpty { id },
            on = o.bool("on"),
            trigger = Trigger.of(o.str("trigger")),
            repo = o.str("repo"),
            folder = o.str("folder"),
            research = o.bool("research"),
            prompt = o.str("prompt"),
            settings = (o["settings"] as? JsonObject).orEmpty().mapValues { (_, v) ->
                when (v) {
                    is JsonPrimitive -> v.contentOrNull.orEmpty()
                    is JsonArray -> v.joinToString(", ") { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() }
                    else -> v.toString()
                }
            },
            last = (o["last"] as? JsonObject)?.let(::firing),
            next = o.num("next")?.toLong(),
            offWhy = o.str("offWhy"),
        )
    }

    fun firing(o: JsonObject) = Firing(
        at = o.num("at")?.toLong() ?: 0L,
        cause = o.str("cause"),
        what = o.str("what"),
        task = o.str("task"),
        skipped = o.str("skipped"),
        note = o.str("note"),
        times = o.num("n")?.toInt() ?: 1,
    )
}
