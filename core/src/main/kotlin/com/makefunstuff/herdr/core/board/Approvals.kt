package com.makefunstuff.herdr.core.board

import com.makefunstuff.herdr.core.ssh.HerdrCommands.quote
import com.makefunstuff.herdr.core.ssh.Machine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Finished work waiting for the human, one card per task: what `details` tells, and what only the
 * board's own record of the task holds (the agent's closing note, whether the local check passed,
 * the pull request's checks on GitHub). Read with [ApprovalCommands.facts]; plain data and rules
 * here, so they are tested without a phone.
 */
object Approvals {
    /** The statuses a card is for: work that is done and waits for a yes, a no or a choice. */
    val STATUSES = setOf(BoardStatus.Approval, BoardStatus.ReviewFailed, BoardStatus.PlanReady, BoardStatus.PickOne)

    /**
     * Whether [t] gets a card: one of [STATUSES], or work at its approval whose merge is blocked (its
     * pull request's checks failed, the merge or the update with main went wrong): the board shows
     * those as "went wrong", and they still wait for an approve, or an approve anyway.
     */
    fun waits(t: BoardTask): Boolean = !t.archived && (t.status in STATUSES || (t.stage == "approval" && t.needsYou))

    /** One card: a task and the machine whose board listed it. */
    data class Item(val machineId: String, val task: BoardTask) {
        val key: String get() = "$machineId|${task.id}"
    }

    /**
     * The cards of every machine's board, in the machines' order and each board's own: ready to
     * approve first, then what failed, plans and choices. Another machine's task that a board lists
     * (`vm:repo/3`) is left out when that machine's own board, read here too, lists it.
     */
    fun queue(boards: List<Pair<String, List<BoardTask>>>): List<Item> {
        val own = boards.flatMap { (_, l) -> l.filter { it.machine.isEmpty() }.map { it.id.substringAfter(':') to it.title } }.toSet()
        val order = listOf(BoardStatus.Approval, BoardStatus.ReviewFailed, BoardStatus.Error, BoardStatus.PlanReady, BoardStatus.PickOne)
        return boards.flatMap { (m, l) ->
            l.filter { waits(it) && (it.machine.isEmpty() || (it.id.substringAfter(':') to it.title) !in own) }
                .sortedBy { order.indexOf(it.status).let { i -> if (i < 0) order.size else i } }
                .map { Item(m, it) }
        }
    }

    /** The newest review's findings by severity, most severe first: `major` to 1, `minor` to 2. */
    fun severities(r: Review?): List<Pair<String, Int>> {
        val f = r?.findings.orEmpty()
        return f.groupBy { it.severity.ifEmpty { "finding" } }.map { (s, l) -> s to l.size }.sortedBy { severityRank(it.first) }
    }

    fun severityRank(s: String): Int = SEVERITIES.indexOf(s).let { if (it < 0) SEVERITIES.size else it }

    val SEVERITIES = listOf("critical", "blocker", "major", "minor", "trivial")

    /** The review's verdict in a few words: passed, did not pass, or could not finish. */
    fun verdict(r: Review): String = when {
        r.error.isNotEmpty() -> "could not finish"
        r.pass == true -> "passed"
        r.pass == false || r.meets == false -> "did not pass"
        else -> "no verdict yet"
    }

    /**
     * What failed on the way to this card, a sentence each: the review, the local check, the pull
     * request's checks, a merge or an update that went wrong. Empty: a plain approve merges it. Not
     * empty: only Approve anyway does, and it says these first.
     */
    fun failed(t: BoardTask, d: TaskDetails?, f: WorkFacts?): List<String> = buildList {
        val r = d?.reviews?.lastOrNull()
        if (r != null && r.error.isNotEmpty()) add("The review couldn't finish: ${firstLine(r.error)}")
        else if (r != null && (r.pass == false || (r.pass == null && r.meets == false))) add("The review didn't pass" + findingsWords(r))
        else if (r == null && t.reviewPassed == false) add("The review didn't pass")
        if (f?.local?.state == CheckState.Failed) add("The local check failed" + f.local.words.takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty())
        if (f?.pr?.state == CheckState.Failed) {
            val names = f.pr.runs.filter { it.state == CheckState.Failed }.map { it.name }.distinct()
            add("GitHub's checks failed on the pull request" + if (names.isEmpty()) "" else ": " + names.joinToString(", "))
        }
        if (f?.mergeFailed == true) add("The merge failed: the pull request was closed or couldn't merge")
        if (f?.syncBlocked == true) add("It couldn't be brought up to date with its base branch")
        d?.failure?.let { x ->
            val words = x.title.ifEmpty { "Something went wrong" }
            if (none { it.startsWith(words.trimEnd('.')) } && !(f?.local?.state == CheckState.Failed && x.title.startsWith("Check failed"))) add(words)
        }
        // The board says its merge is blocked, and nothing above said why: its own line does.
        if (isEmpty() && t.status == BoardStatus.Error) add(t.msg.lines().firstOrNull { it.isNotBlank() }?.trim() ?: "Something went wrong on the way to its merge")
    }

    private fun findingsWords(r: Review): String {
        val s = severities(r)
        if (s.isEmpty()) return ""
        val n = s.sumOf { it.second }
        return ": $n finding${if (n == 1) "" else "s"} (" + s.joinToString(", ") { "${it.second} ${it.first}" } + ")"
    }

    private fun firstLine(s: String) = s.lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
}

/** Where a check stands. */
enum class CheckState { Passed, Failed, Running, None }

/** The board's own check of the work on its machine (`check.command`), before the review. */
data class LocalCheck(val state: CheckState, val words: String = "")

/** One of the pull request's checks on GitHub (`gh pr checks`). */
data class CheckRun(val name: String, val workflow: String, val state: CheckState, val link: String = "")

/** The pull request's checks on GitHub: each one when `gh` could tell, else the board's last word on them. */
data class PrChecks(val state: CheckState, val runs: List<CheckRun> = emptyList(), val words: String = "")

/** What the board's record of a task holds that `details` doesn't send. */
data class WorkFacts(
    /** The agent's closing note (its summary of what it did), Markdown. */
    val note: String = "",
    /** The board's line for the change: "46 files changed, 3787 insertions(+), 19 deletions(-)". */
    val stat: String = "",
    val local: LocalCheck = LocalCheck(CheckState.None),
    val pr: PrChecks = PrChecks(CheckState.None),
    val mergeFailed: Boolean = false,
    val syncBlocked: Boolean = false,
    /** The base the work started from (`origin/HEAD`), for its diff. */
    val from: String = "",
)

object ApprovalCommands {
    const val MARK = "@@herdr-facts"

    /** What the board's record says, at most: a big description is cut, the JSON then doesn't parse and only the note is used. */
    private const val TASK_LIMIT = 2 * 1024 * 1024
    private const val NOTE_LIMIT = 64 * 1024

    /**
     * The task's record (`<data>/tasks/<repo>/<n>/task.json`), its closing note, and, with a pull
     * request, `gh pr checks` on it, each after a `@@herdr-facts <part>` line. Only reads. The data
     * folder is the board's: `HERDR_PROJECTS_DATA_DIR`, else the one the launcher [path] is in
     * (`~/.herdr-projects/data/bin/herdr-projects`), else `~/.herdr-projects/data`. Through a login
     * shell, as the board's commands run, so `gh` and its login are found.
     */
    fun facts(machine: Machine, path: String, taskId: String, pr: String): String {
        val script = "d=\${HERDR_PROJECTS_DATA_DIR:-}; " +
            "if [ -z \"\$d\" ]; then case \"\$1\" in */bin/herdr-projects) l=\${1%/bin/herdr-projects}; [ -d \"\$l/tasks\" ] && d=\$l;; esac; fi; " +
            "[ -n \"\$d\" ] || d=\${HERDR_PROJECTS_HOME:-\$HOME/.herdr-projects}/data; t=\"\$d/tasks/\$2\"; " +
            "echo '$MARK task'; head -c $TASK_LIMIT \"\$t/task.json\" 2>/dev/null; echo; " +
            "echo '$MARK note'; for f in closing.md closing-note.md; do if [ -s \"\$t/\$f\" ]; then head -c $NOTE_LIMIT \"\$t/\$f\"; break; fi; done; echo; " +
            "if [ -n \"\$3\" ]; then echo '$MARK checks'; if command -v gh >/dev/null 2>&1; then " +
            "if command -v timeout >/dev/null 2>&1; then w='timeout 20'; else w=''; fi; " +
            "\$w gh pr checks \"\$3\" --json name,state,bucket,workflow,link 2>&1 </dev/null; else echo 'gh is not installed'; fi; echo; fi; " +
            "echo '$MARK end'"
        val inner = "sh -c ${quote(script)} herdr-board-facts ${quote(path)} ${quote(taskId)} ${quote(pr)}"
        return BoardCommands.login(machine, inner)
    }
}

object ApprovalReader {
    private val json = Json { ignoreUnknownKeys = true }

    /** What [ApprovalCommands.facts] printed. A record that is missing or doesn't parse leaves its parts empty. */
    fun facts(out: String): WorkFacts {
        val parts = HashMap<String, String>()
        var name: String? = null
        val cur = StringBuilder()
        for (line in out.split('\n')) {
            if (line.startsWith(ApprovalCommands.MARK + " ")) {
                name?.let { parts[it] = cur.toString().trim() }
                name = line.removePrefix(ApprovalCommands.MARK + " ").trim()
                cur.clear()
            } else if (name != null) cur.append(line).append('\n')
        }
        name?.let { parts[it] = cur.toString().trim() }
        val t = parts["task"]?.takeIf { it.startsWith("{") }?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
        val prUrl = t?.str("pr").orEmpty()
        return WorkFacts(
            note = parts["note"].orEmpty().ifEmpty { t?.str("summary").orEmpty() },
            stat = t?.str("stat").orEmpty(),
            local = t?.let(::local) ?: LocalCheck(CheckState.None),
            pr = prChecks(parts["checks"], t, hasPr = prUrl.isNotEmpty() || parts.containsKey("checks")),
            mergeFailed = t?.bool("mergeFailed") == true,
            syncBlocked = t?.get("syncBlocked")?.let { it is JsonObject || (it as? JsonPrimitive)?.content == "true" } == true,
            from = t?.str("from").orEmpty(),
        )
    }

    /** The board's check on its machine: failed (its failure says so), running, passed (and with what), or none on record. */
    fun local(t: JsonObject): LocalCheck {
        val f = t["failure"] as? JsonObject
        if (f != null && f.str("stage") == "check") return LocalCheck(CheckState.Failed, f.str("title").ifEmpty { "Check failed" })
        if (t.str("stage") == "checking") return LocalCheck(CheckState.Running, t.str("msg").ifEmpty { "Running the check" })
        t.str("checkShort").takeIf { it.isNotEmpty() }?.let { return LocalCheck(CheckState.Passed, "the shorter check of a fix round: $it") }
        (t["checkPassed"] as? JsonObject)?.let { p -> return LocalCheck(CheckState.Passed, p.str("with")) }
        return LocalCheck(CheckState.None)
    }

    /**
     * `gh pr checks --json` as runs; where gh couldn't tell (not installed, not logged in, no checks
     * yet), the board's own word: its `prState` and the blocker it noted.
     */
    fun prChecks(gh: String?, t: JsonObject?, hasPr: Boolean): PrChecks {
        if (!hasPr) return PrChecks(CheckState.None, words = "No pull request")
        val runs = gh?.let(::runs)
        if (runs != null && runs.isNotEmpty()) {
            val state = when {
                runs.any { it.state == CheckState.Failed } -> CheckState.Failed
                runs.any { it.state == CheckState.Running } -> CheckState.Running
                else -> CheckState.Passed
            }
            return PrChecks(state, runs.sortedBy { listOf(CheckState.Failed, CheckState.Running, CheckState.Passed, CheckState.None).indexOf(it.state) })
        }
        val said = gh?.lines()?.map { it.trim() }?.lastOrNull { it.isNotEmpty() && !it.startsWith("[") }.orEmpty()
        val blocker = (t?.get("prFailure") as? JsonObject)
        val words = blocker?.str("title")?.takeIf { it.isNotEmpty() } ?: said
        return when (t?.str("prState")) {
            "failing" -> PrChecks(CheckState.Failed, words = words.ifEmpty { "Failing on GitHub" })
            "passing" -> PrChecks(CheckState.Passed, words = "Passed on GitHub")
            "waiting" -> PrChecks(CheckState.Running, words = "Waiting for GitHub's checks")
            "merged" -> PrChecks(CheckState.Passed, words = "Merged")
            else -> PrChecks(CheckState.None, words = said.ifEmpty { "No checks reported" })
        }
    }

    private fun runs(gh: String): List<CheckRun>? {
        val start = gh.indexOf('[').takeIf { it >= 0 } ?: return null
        val arr = runCatching { json.parseToJsonElement(gh.substring(start, gh.lastIndexOf(']') + 1)) as? JsonArray }.getOrNull() ?: return null
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val state = when (o.str("bucket").ifEmpty { o.str("state").lowercase() }) {
                "pass", "success", "skipping", "skipped", "neutral" -> CheckState.Passed
                "fail", "failure", "cancel", "cancelled", "error", "timed_out", "action_required" -> CheckState.Failed
                "pending", "in_progress", "queued", "waiting", "requested", "expected" -> CheckState.Running
                else -> CheckState.None
            }
            CheckRun(o.str("name").ifEmpty { "check" }, o.str("workflow"), state, o.str("link"))
        }
            // A workflow run for the push and one for the pull request list the same check twice: once, at its worst.
            .groupBy { it.workflow to it.name }.values.map { same -> same.minBy { WORST.indexOf(it.state) } }
    }

    private val WORST = listOf(CheckState.Failed, CheckState.Running, CheckState.None, CheckState.Passed)
}
