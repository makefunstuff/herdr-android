package com.makefunstuff.herdr.core.activity

import com.makefunstuff.herdr.core.board.Approvals
import com.makefunstuff.herdr.core.board.Review
import com.makefunstuff.herdr.core.board.Finding
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

/** Where one of a task's agent sessions is kept: a file, or a session id the harness's own layout turns into one. */
data class SessionSource(
    /** `claude`, `codex`, `pi` or `omp`. */
    val agent: String,
    /** `path` or `id`, as herdr names them. */
    val kind: String,
    val value: String,
    /** Who it is, when the board said (the role is otherwise read from its first message). */
    val role: Role? = null,
) {
    val key: String get() = "$agent:$value"
}

/** What the board's record of a task tells: its events with their times, and its agents' sessions. */
data class TaskRecord(
    val id: String,
    val title: String,
    val events: List<Step>,
    val sessions: List<SessionSource>,
    /** Still at work or waiting: its newest session may grow. */
    val open: Boolean,
    /** An agent of it is in the middle of a turn now (the worker's, or a review). */
    val working: Boolean = false,
)

/**
 * The board's own record of a task (`<data>/tasks/<repo>/<n>/task.json`, and `said-by.jsonl`, what
 * it typed to the task's agents), read as events: added, started, asked you, handed over, each
 * review's verdict, stopped, went wrong, merged. Only fields with a time make an event.
 */
object BoardHistory {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun read(taskJson: String, saidBy: String = ""): TaskRecord? {
        val t = runCatching { json.parseToJsonElement(taskJson.trim()).jsonObject }.getOrNull() ?: return null
        val id = t.s("id")
        val events = ArrayList<Step>()
        fun add(key: String, kind: StepKind, title: String, at: Long?, state: StepState = StepState.Done, detail: String? = null, note: String? = null, tool: String = "") {
            if (at == null || at <= 0) return
            events += Step("board/$key", kind, title, at, at, state, detail = detail, note = note, tool = tool)
        }
        val agent = listOf(t.s("agent").ifEmpty { t.s("app") }, t.s("model")).filter { it.isNotEmpty() }.distinct().joinToString(" · ")

        add("created", StepKind.Board, "Added to the board", t.long("created"))
        val queued = t.long("queuedAt")
        if (queued != null && queued - (t.long("created") ?: 0) > 60_000) add("queued", StepKind.Board, "Queued", queued)
        add("started", StepKind.Board, "Started the agent", t.long("startedAgentAt") ?: t.long("workAt"), note = agent.ifEmpty { null })
        (t["blocked"] as? JsonObject)?.let { b ->
            val kind = when (b.s("kind")) { "question" -> "Asked you"; "permission" -> "Asked for your OK"; "auth" -> "Needs a login"; else -> "Waited for you" }
            add("blocked", StepKind.Board, kind + b.s("msg").takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty(), b.long("at"))
        }
        (t["handovers"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().forEachIndexed { i, h ->
            val role = when (h.s("role")) { "worker" -> "the work"; "reviewer" -> "the review"; else -> h.s("role").ifEmpty { "the work" } }
            add(
                "handover/$i", StepKind.Handover, "Handed $role over to ${h.s("to")}", h.long("at"),
                detail = listOf("From ${h.s("from")}", h.s("why").takeIf { it.isNotEmpty() }?.let { "Why: $it" }).filterNotNull().joinToString("\n"),
                note = h.s("why").ifEmpty { null },
            )
        }
        (t["reviews"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().forEachIndexed { i, r ->
            val review = review(r)
            val verdict = Approvals.verdict(review)
            val n = review.findings.size
            add(
                "review/$i", StepKind.Review, "Review round ${review.round.takeIf { it > 0 } ?: (i + 1)} $verdict", r.long("at"),
                state = if (verdict == "passed") StepState.Done else StepState.Failed,
                detail = reviewText(review),
                note = if (n > 0) "$n finding${if (n == 1) "" else "s"}" else null,
                tool = review.by,
            )
        }
        (t["plan"] as? JsonObject)?.let { p ->
            add("plan", StepKind.Plan, "The plan is ready", p.long("at"), detail = p.s("words").ifEmpty { null })
            add("plan/approved", StepKind.Plan, "Plan approved", p.long("approvedAt"))
        }
        add("stopped", StepKind.Board, "Stopped", t.long("stoppedAt"))
        val failure = t["failure"] as? JsonObject
        val notice = t["notice"] as? JsonObject
        if (notice?.s("event") == "failed" || failure != null) {
            val at = notice?.long("at") ?: failure?.long("at") ?: t.long("at")
            add(
                "failed", StepKind.Board, failure?.s("title")?.ifEmpty { null } ?: "Went wrong", at, StepState.Failed,
                detail = listOf(failure?.s("detail").orEmpty(), failure?.s("next")?.takeIf { it.isNotEmpty() }?.let { "Next: $it" }.orEmpty())
                    .filter { it.isNotEmpty() }.joinToString("\n\n").ifEmpty { null },
            )
        }
        (t["report"] as? JsonObject)?.let { add("report", StepKind.Board, "Wrote the report", it.long("at"), detail = it.s("link").ifEmpty { null }) }
        val pr = t.s("pr")
        add("merged", StepKind.Board, "Merged", t.long("mergedAt"), detail = pr.ifEmpty { null }, note = pr.substringAfterLast('/').takeIf { pr.contains("/pull/") }?.let { "#$it" })
        add("archived", StepKind.Board, "Archived", t.long("archivedAt"))

        // What the board (or you, through it) typed to its agents: kept for the times no transcript shows it.
        saidBy.lineSequence().filter { it.startsWith("{") }.forEachIndexed { i, line ->
            val o = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@forEachIndexed
            val head = o.s("head").ifEmpty { return@forEachIndexed }
            val you = o.s("by") == "you"
            val reading = Prompts.read(head)
            add(
                "said/$i", StepKind.Board, if (you) "You told the agent: ${reading.title}" else reading.title, o.long("at"),
                detail = head, tool = if (you) "you" else "board",
            )
        }

        val stage = t.s("stage")
        val open = t["archivedAt"].let { it == null || it is kotlinx.serialization.json.JsonNull } && stage != "done" && t.long("mergedAt") == null
        val working = open && ((t["turnOpen"] as? JsonPrimitive)?.booleanOrNull == true || stage in setOf("starting", "working", "reviewing", "checking", "fixing", "merging"))
        return TaskRecord(id, t.s("title"), events.sortedBy { it.at }, sessions(t), open, working)
    }

    /**
     * Every session the task's agents had (`spent.owed`: the worker's and each reviewer's, also
     * across hand-overs), oldest first, then its current transcript where that list lacks it.
     */
    fun sessions(t: JsonObject): List<SessionSource> {
        val out = LinkedHashMap<String, SessionSource>()
        val reviewer = t.s("reviewSession")
        fun add(agent: String, session: String, file: String) {
            val a = agent.ifEmpty { return }
            val src = when {
                file.isNotEmpty() -> SessionSource(a, "path", file)
                session.startsWith("/") || session.startsWith("~") -> SessionSource(a, "path", session)
                session.isNotEmpty() -> SessionSource(a, "id", session)
                else -> return
            }
            val role = if (reviewer.isNotEmpty() && (session == reviewer || file.contains(reviewer))) Role(Role.REVIEWER) else null
            // One session listed by id and by its file is the same session.
            val same = out.values.firstOrNull { it.value == src.value || (session.isNotEmpty() && (it.value == session || it.value.contains(session))) }
            if (same != null) {
                if (same.kind == "id" && src.kind == "path") out[same.key]?.let { out.remove(same.key); out[src.key] = src.copy(role = role ?: same.role) }
                return
            }
            out[src.key] = src.copy(role = role)
        }
        ((t["spent"] as? JsonObject)?.get("owed") as? JsonArray).orEmpty().filterIsInstance<JsonObject>().forEach { o ->
            add(o.s("kind"), o.s("session"), o.s("file"))
        }
        val kind = t.s("agent").ifEmpty { t.s("app") }.substringBefore('/')
        add(kind, t.s("session"), t.s("transcript"))
        return out.values.toList()
    }

    private fun review(r: JsonObject) = Review(
        round = r.long("round")?.toInt() ?: 0,
        by = r.s("by"),
        meets = (r["meets"] as? JsonPrimitive)?.booleanOrNull,
        pass = (r["pass"] as? JsonPrimitive)?.booleanOrNull,
        error = r.s("error"),
        text = r.s("text"),
        findings = (r["findings"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().map { Finding(it.s("id"), it.s("severity"), it.s("subject"), it.s("where"), it.s("proof")) },
        checks = emptyList(),
        limitations = emptyList(),
    )

    private fun reviewText(r: Review): String = buildString {
        if (r.error.isNotEmpty()) append(r.error).append("\n\n")
        if (r.text.isNotEmpty()) append(r.text).append("\n\n")
        r.findings.sortedBy { Approvals.severityRank(it.severity) }.forEach { f ->
            append("- **${f.severity.ifEmpty { "finding" }}** ${f.subject}")
            if (f.where.isNotEmpty()) append(" (`${f.where.take(120)}`)")
            append('\n')
        }
    }.trim()

    private fun JsonObject.s(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
}
