package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.board.ApprovalCommands
import com.makefunstuff.herdr.core.board.ApprovalReader
import com.makefunstuff.herdr.core.board.Approvals
import com.makefunstuff.herdr.core.board.BoardStatus
import com.makefunstuff.herdr.core.board.BoardTask
import com.makefunstuff.herdr.core.board.BoardTasks
import com.makefunstuff.herdr.core.board.CheckState
import com.makefunstuff.herdr.core.board.Finding
import com.makefunstuff.herdr.core.board.PrChecks
import com.makefunstuff.herdr.core.board.Review
import com.makefunstuff.herdr.core.board.TaskDetails
import com.makefunstuff.herdr.core.board.WorkFacts
import com.makefunstuff.herdr.core.board.LocalCheck
import com.makefunstuff.herdr.core.ssh.Machine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ApprovalsTest {
    private val machine = Machine("m", "m", "h", user = "u")

    private fun task(json: String, key: String = ""): BoardTask = assertNotNull(BoardTasks.task(Json.parseToJsonElement(json).jsonObject, key))

    private val approval = """{"id":"herdr-android/9","title":"Last answered","state":"blocked","stage":"approval","kind":"permission","review":{"pass":true},"pr":"https://github.com/o/r/pull/9"}"""
    private val failedReview = """{"id":"website/4","title":"Install page","state":"blocked","stage":"approval","kind":"permission","review":{"pass":false}}"""

    /** Runs [command] as the machine would, with [home] as HOME and a plain sh as the login shell. */
    private fun sh(command: String, home: File, env: Map<String, String> = emptyMap()): String {
        val pb = ProcessBuilder("sh", "-c", command).redirectErrorStream(true)
        pb.environment()["HOME"] = home.absolutePath
        pb.environment()["SHELL"] = "/bin/sh"
        pb.environment().remove("HERDR_PROJECTS_DATA_DIR")
        pb.environment().remove("HERDR_PROJECTS_HOME")
        pb.environment().putAll(env)
        val p = pb.start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        p.waitFor(30, TimeUnit.SECONDS)
        return out
    }

    /** A home with the board's record of herdr-android/9, a closing note, and a `gh` on the login shell's PATH. */
    private fun home(gh: String?): File {
        val home = kotlin.io.path.createTempDirectory("approvals").toFile()
        val dir = File(home, ".herdr-projects/data/tasks/herdr-android/9").apply { mkdirs() }
        File(dir, "task.json").writeText(
            """
            {"id":"herdr-android/9","stage":"approval","summary":"The summary","stat":"3 files changed, 40 insertions(+), 2 deletions(-)",
             "checkPassed":{"head":"abc123","with":"./gradlew test"},"from":"origin/HEAD","pr":"https://github.com/o/r/pull/9","prState":"waiting",
             "mergeFailed":false,"syncBlocked":null}
            """.trimIndent(),
        )
        File(dir, "closing.md").writeText("AND-9 done.\n\n- **Shows** when each machine last answered\n")
        if (gh != null) {
            val bin = File(home, "bin").apply { mkdirs() }
            File(bin, "gh").apply { writeText("#!/bin/sh\n$gh\n"); setExecutable(true) }
            // A login shell may set PATH itself (Debian's /etc/profile does): the account's profile puts gh on it, as on a real machine.
            File(home, ".profile").writeText("PATH=\"${bin.absolutePath}:\$PATH\"; export PATH\n")
        }
        return home
    }

    @Test
    fun factsReadTheRecordTheNoteAndGitHubsChecks() {
        val home = home(
            """[ "$1 $2" = "pr checks" ] || exit 9; echo '[{"bucket":"pass","name":"build","state":"SUCCESS","workflow":"Android","link":"https://x/1"},""" +
                """{"bucket":"fail","name":"screens","state":"FAILURE","workflow":"Android","link":"https://x/2"},{"bucket":"pending","name":"lint","state":"IN_PROGRESS","workflow":"CI","link":""}]'""",
        )
        try {
            val launcher = File(home, ".herdr-projects/data/bin/herdr-projects").absolutePath
            val out = sh(ApprovalCommands.facts(machine, launcher, "herdr-android/9", "https://github.com/o/r/pull/9"), home)
            val f = ApprovalReader.facts(out)
            assertEquals("AND-9 done.\n\n- **Shows** when each machine last answered", f.note)
            assertEquals("3 files changed, 40 insertions(+), 2 deletions(-)", f.stat)
            assertEquals(LocalCheck(CheckState.Passed, "./gradlew test"), f.local)
            assertEquals("origin/HEAD", f.from)
            assertEquals(CheckState.Failed, f.pr.state)
            // Failed first, then what still runs, then what passed.
            assertEquals(listOf("screens", "lint", "build"), f.pr.runs.map { it.name })
            assertEquals("https://x/2", f.pr.runs.first().link)
            assertTrue(!f.mergeFailed && !f.syncBlocked)
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun withoutGhTheBoardsOwnWordOnThePullRequestStands() {
        val home = home(gh = null)
        try {
            // A launcher found elsewhere (on the PATH): the board's folder is the default one.
            val out = sh(ApprovalCommands.facts(machine, "/usr/local/bin/herdr-projects", "herdr-android/9", "https://github.com/o/r/pull/9"), home, mapOf("PATH" to "/usr/bin:/bin"))
            val f = ApprovalReader.facts(out)
            assertEquals("AND-9 done.\n\n- **Shows** when each machine last answered", f.note)
            assertEquals(PrChecks(CheckState.Running, words = "Waiting for GitHub's checks"), f.pr)
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun theDataFolderCanBeSetAndAMissingRecordLeavesEverythingEmpty() {
        val home = home(gh = null)
        try {
            val elsewhere = File(home, "elsewhere").apply { mkdirs() }
            File(home, ".herdr-projects/data").renameTo(File(elsewhere, "data"))
            val cmd = ApprovalCommands.facts(machine, "/x/herdr-projects", "herdr-android/9", "")
            val f = ApprovalReader.facts(sh(cmd, home, mapOf("HERDR_PROJECTS_DATA_DIR" to File(elsewhere, "data").absolutePath)))
            assertEquals("./gradlew test", f.local.words)
            // gh isn't asked without a pull request given; the record's own word on it stands.
            assertEquals(CheckState.Running, f.pr.state)
            val none = ApprovalReader.facts(sh(ApprovalCommands.facts(machine, "/x/herdr-projects", "herdr-android/77", ""), home))
            assertEquals(WorkFacts(pr = PrChecks(CheckState.None, words = "No pull request")), none)
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun theLocalCheckFromTheRecord() {
        fun local(json: String) = ApprovalReader.local(Json.parseToJsonElement(json).jsonObject)
        assertEquals(LocalCheck(CheckState.Failed, "Check failed after 3 tries"), local("""{"failure":{"stage":"check","title":"Check failed after 3 tries"},"checkPassed":{"with":"x"}}"""))
        assertEquals(CheckState.Running, local("""{"stage":"checking","msg":"Running the check"}""").state)
        assertEquals("the shorter check of a fix round: ./gradlew -p core test", local("""{"checkShort":"./gradlew -p core test","checkPassed":{"with":"./gradlew test"}}""").words)
        assertEquals(LocalCheck(CheckState.None), local("""{"checkPassed":null,"failure":null}"""))
    }

    @Test
    fun githubsChecksFallBackToTheBoardsBlocker() {
        val t = Json.parseToJsonElement("""{"prState":"failing","prFailure":{"title":"Pull request checks failed","checks":["build"]}}""").jsonObject
        assertEquals(PrChecks(CheckState.Failed, words = "Pull request checks failed"), ApprovalReader.prChecks("To get started with GitHub CLI, please run:  gh auth login", t, hasPr = true))
        val none = ApprovalReader.prChecks("no checks reported on the 'work/9' branch", Json.parseToJsonElement("{}").jsonObject, hasPr = true)
        assertEquals(PrChecks(CheckState.None, words = "no checks reported on the 'work/9' branch"), none)
        // Skipped checks count as passed; none failed or runs.
        val ok = ApprovalReader.prChecks("""[{"bucket":"pass","name":"a"},{"bucket":"skipping","name":"b"}]""", null, hasPr = true)
        assertEquals(CheckState.Passed, ok.state)
        // The same check from two workflow runs (the push's and the pull request's): once, at its worst.
        val twice = ApprovalReader.prChecks(
            """[{"bucket":"pass","name":"build","workflow":"Android"},{"bucket":"fail","name":"build","workflow":"Android"},{"bucket":"pass","name":"build","workflow":"Lint"}]""",
            null, hasPr = true,
        )
        assertEquals(listOf("Android" to CheckState.Failed, "Lint" to CheckState.Passed), twice.runs.map { it.workflow to it.state })
    }

    @Test
    fun theQueueTakesWhatWaitsForAYesFromEveryBoard() {
        val devbox = listOf(
            task("""{"id":"api/3","title":"Asks","state":"blocked","stage":"working","kind":"question"}"""),
            task(failedReview),
            task("""{"id":"api/7","title":"Plan","state":"blocked","stage":"plan","kind":"permission"}"""),
            task(approval),
            // Its pull request's checks failed: "went wrong", but still at its approval.
            task("""{"id":"api/8","title":"Checks red","state":"blocked","stage":"approval","kind":"permission","prState":"failing"}"""),
            task("""{"id":"api/9","title":"Broke","state":"error","stage":"working"}"""),
            task("""{"id":"api/10","title":"Old","state":"blocked","stage":"approval","kind":"permission","archivedAt":5}"""),
            // gpu's own task, as devbox's board lists it: gpu's board lists it too.
            task("""{"id":"gpu:eval/2","title":"Eval","state":"blocked","stage":"approval","kind":"permission"}"""),
            task("""{"id":"far:x/1","title":"Far","state":"blocked","stage":"pick"}"""),
        )
        val gpu = listOf(task("""{"id":"eval/2","title":"Eval","state":"blocked","stage":"approval","kind":"permission"}"""))
        val q = Approvals.queue(listOf("devbox" to devbox, "gpu" to gpu))
        assertEquals(
            listOf("devbox|herdr-android/9", "devbox|website/4", "devbox|api/8", "devbox|api/7", "devbox|far:x/1", "gpu|eval/2"),
            q.map { it.key },
        )
        assertEquals(BoardStatus.Error, q[2].task.status)
    }

    @Test
    fun whatFailedSaysWhyOnlyApproveAnywayMerges() {
        val r = Review(
            1, "claude", meets = false, pass = false, error = "", text = "",
            findings = listOf(Finding("F1", "minor", "a", "", ""), Finding("F2", "major", "b", "", ""), Finding("F3", "minor", "c", "", "")),
            checks = emptyList(), limitations = emptyList(),
        )
        val d = details(listOf(r))
        val f = WorkFacts(
            local = LocalCheck(CheckState.Passed, "./gradlew test"),
            pr = PrChecks(CheckState.Failed, listOf(com.makefunstuff.herdr.core.board.CheckRun("screens", "Android", CheckState.Failed), com.makefunstuff.herdr.core.board.CheckRun("build", "Android", CheckState.Passed))),
        )
        assertEquals(
            listOf("The review didn't pass: 3 findings (1 major, 2 minor)", "GitHub's checks failed on the pull request: screens"),
            Approvals.failed(task(failedReview), d, f),
        )
        assertEquals(listOf("major" to 1, "minor" to 2), Approvals.severities(r))
        // A passed review and green checks: a plain approve.
        val passed = r.copy(meets = true, pass = true, findings = listOf(Finding("F1", "trivial", "x", "", "")))
        assertEquals(emptyList(), Approvals.failed(task(approval), details(listOf(passed)), WorkFacts(local = LocalCheck(CheckState.Passed), pr = PrChecks(CheckState.Passed))))
        // Before the details are read, the list's own verdict.
        assertEquals(listOf("The review didn't pass"), Approvals.failed(task(failedReview), null, null))
        assertEquals("passed", Approvals.verdict(passed))
    }

    private fun details(reviews: List<Review>) = TaskDetails(
        key = "AND-9", title = "", stage = "approval", msg = "", description = "", failure = null, reviews = reviews, agent = "", handovers = emptyList(),
        research = false, reportLink = "", plan = "", pr = "", branch = "", worktree = "", time = null, tokens = null, queue = "", attempts = emptyList(),
        comparison = "", waitsFor = emptyList(),
    )
}
