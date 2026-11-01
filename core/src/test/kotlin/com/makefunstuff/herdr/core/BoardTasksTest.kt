package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.board.BoardAction
import com.makefunstuff.herdr.core.board.BoardActions
import com.makefunstuff.herdr.core.board.BoardCommands
import com.makefunstuff.herdr.core.board.BoardFilter
import com.makefunstuff.herdr.core.board.BoardStatus
import com.makefunstuff.herdr.core.board.BoardTask
import com.makefunstuff.herdr.core.board.BoardTasks
import com.makefunstuff.herdr.core.ssh.Machine
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BoardTasksTest {
    private fun res(name: String) = javaClass.getResource("/board/$name")!!.readText()

    private fun task(state: String, stage: String, kind: String = "", extra: BoardTask.() -> BoardTask = { this }) =
        BoardTask("r/1", "", "t", state, stage, kind, "pi", "", null, null, false, "", false, false, 0, null, false).extra()

    @Test
    fun readsARealList() {
        val tasks = BoardTasks.list("motd\n" + res("list.json") + "\n")
        assertEquals(14, tasks.size)
        val a = tasks.first { it.id == "herdr-android/12" }
        assertEquals(BoardStatus.Working, a.status)
        assertEquals(3, a.progress)
        assertEquals("pi", a.agent)
        val failed = tasks.first { it.id == "dev/1" }
        assertEquals(BoardStatus.Error, failed.status)
        assertTrue(failed.research)
        assertTrue(failed.needsYou)
        val merged = tasks.first { it.id == "herdr-android/11" }
        assertEquals(BoardStatus.Done, merged.status)
        assertEquals("11", merged.number.takeIf { true })
        assertEquals("10", merged.prNumber)
        assertEquals("herdr-android", merged.repo)
    }

    @Test
    fun shortKeysComeFromThePlainListByPosition() {
        val tasks = BoardTasks.list(res("list.json")).take(3)
        val plain = "AND-12    working  [pi]  Native\nHML-1     done     [pi]  Arch\nHOME-1    error    [pi]  Arch"
        val keys = BoardTasks.keys(plain, tasks)
        assertEquals("AND-12", keys["herdr-android/12"])
        assertEquals("HML-1", keys["homelab/1"])
        assertEquals("HOME-1", keys["dev/1"])
        // A list of another length can't be matched.
        assertTrue(BoardTasks.keys("AND-12 x", tasks).isEmpty())
    }

    @Test
    fun theBoardsMessageIsTheError() {
        val e = assertFailsWith<IOException> { BoardTasks.list("No tasks.") }
        assertEquals("No tasks.", e.message)
        assertEquals("No task AND-99", assertFailsWith<IOException> { BoardTasks.details("No task AND-99\n") }.message)
    }

    @Test
    fun statusesFollowTheBoardsRules() {
        assertEquals(BoardStatus.Todo, task("idle", "todo").status)
        assertEquals(BoardStatus.Queued, task("working", "queued").status)
        assertEquals(BoardStatus.Reviewing, task("working", "reviewing").status)
        assertEquals(BoardStatus.PlanReady, task("blocked", "plan", "permission").status)
        assertEquals(BoardStatus.PickOne, task("blocked", "pick").status)
        assertEquals(BoardStatus.Approval, task("blocked", "approval", "permission").status)
        assertEquals(BoardStatus.ReviewFailed, task("blocked", "approval", "permission") { copy(reviewPassed = false) }.status)
        assertEquals(BoardStatus.Error, task("blocked", "approval", "permission") { copy(approvalBlocked = true) }.status)
        assertEquals(BoardStatus.Asks, task("blocked", "asks", "question").status)
        assertEquals(BoardStatus.NeedsLogin, task("blocked", "asks", "auth").status)
        assertEquals(BoardStatus.Archived, task("done", "done") { copy(archived = true) }.status)
        assertTrue(task("blocked", "pick").needsYou)
        assertTrue(!task("working", "working").needsYou)
    }

    @Test
    fun actionsFitTheStatus() {
        assertEquals(listOf(BoardAction.Delegate), BoardActions.forTask(task("idle", "todo")).take(1))
        val failedReview = BoardActions.forTask(task("blocked", "approval", "permission") { copy(reviewPassed = false) })
        assertTrue(BoardAction.Review in failedReview && BoardAction.ApproveAnyway in failedReview && BoardAction.Approve !in failedReview)
        val plan = BoardActions.forTask(task("blocked", "plan", "permission"))
        assertTrue(BoardAction.ApprovePlan in plan && BoardAction.SendPlanBack in plan)
        assertEquals(listOf(BoardAction.Restore, BoardAction.Clear), BoardActions.forTask(task("done", "done") { copy(archived = true) }))
    }

    @Test
    fun readsDetailsOfAFailedReviewOfAResearchTask() {
        val d = BoardTasks.details(res("details-failed.json"))
        assertEquals("HML-1", d.key)
        assertTrue(d.research)
        assertEquals("Review failed", d.failure!!.title)
        assertTrue(d.failure!!.next.startsWith("Investigate the reviewer failure"))
        assertTrue(d.failure!!.agentCanFix)
        assertEquals("no valid verdict", d.reviews.single().error)
        assertEquals("https://devbox.tail1234.ts.net:8750/homelab/1/report/report.md", d.reportLink)
        assertEquals("23s", d.time!!.used)
        assertEquals("none", d.tokens!!.limit)
    }

    @Test
    fun readsReviewsWithFindingsAndSeverity() {
        val d = BoardTasks.details(res("details-reviewed.json"))
        val f = d.reviews.flatMap { it.findings }
        assertTrue(f.isNotEmpty())
        assertEquals(setOf("minor", "trivial"), f.map { it.severity }.toSet())
        assertTrue(f.first().subject.isNotEmpty() && f.first().proof.isNotEmpty())
        assertTrue(d.reviews.first().checks.isNotEmpty() || d.reviews.first().text.isNotEmpty())
        assertTrue(d.description.isNotEmpty())
    }

    @Test
    fun readsDetailsOfATaskAtWork() {
        val d = BoardTasks.details("noise\n" + res("details-working.json"))
        assertEquals("AND-12", d.key)
        assertEquals(null, d.failure)
        assertEquals("pi", d.agent)
        assertTrue(d.branch.startsWith("work/12"))
        assertEquals("", d.pr)
    }

    @Test
    fun readsHandoversPlansAndAttempts() {
        val d = BoardTasks.details(
            """{"task":{"id":"AND-1","title":"t","stage":"plan","msg":"","description":"d"},"failure":null,"review":null,"reviews":[],
            "handovers":[{"from":"claude","to":"pi","why":"out of allowance","words":"pi took over from claude: out of allowance"}],
            "plan":{"text":"1. do it"},"limits":{"time":{"limit":"2h","usedMs":5,"used":"5s"},"tokens":{"limit":"1m","used":1234,"total":1234}},
            "attempts":[{"letter":"A","agent":"pi","standing":"passed","stat":"2 files"}],"comparison":"A is better"}""",
        )
        assertEquals(listOf("pi took over from claude: out of allowance"), d.handovers)
        assertEquals("1. do it", d.plan)
        assertEquals("1234", d.tokens!!.used)
        assertEquals("2h", d.time!!.limit)
        assertEquals("A", d.attempts.single().letter)
    }

    @Test
    fun commandsQuoteEverythingAndCarryTheSession() {
        val m = Machine("m", "m", "h", user = "u", session = "work")
        val cmd = BoardCommands.cli(m, "/h/herdr-projects", listOf("tell", "AND-1", "it's \"fine\"; rm -rf /"))
        assertTrue(cmd.contains("HERDR_SESSION="), cmd)
        assertTrue(cmd.contains("tell"), cmd)
        val list = BoardCommands.listCommand(m, "/h/herdr-projects", BoardFilter(query = "a b", archived = true))
        assertTrue(list.contains("--archived") && list.contains("--query") && list.contains(BoardCommands.LIST_MARK), list)
    }
}

class BoardActionsPrimaryTest {
    private fun t(state: String, stage: String, kind: String = "") = BoardTask(
        "r/1", "R-1", "t", state, stage, kind, "", "", null, null, false, "", false, false, 0, null, false,
    )

    @Test
    fun primaryIsTheLikelyNextStep() {
        assertEquals(BoardAction.Approve, BoardActions.primary(t("blocked", "approval", "permission")))
        assertEquals(BoardAction.ApprovePlan, BoardActions.primary(t("blocked", "plan", "permission")))
        assertEquals(BoardAction.Delegate, BoardActions.primary(t("idle", "todo")))
        assertEquals(null, BoardActions.primary(t("working", "working")))
    }

    @Test
    fun onlyRiskyActionsAskFirst() {
        assertEquals(
            setOf(BoardAction.Stop, BoardAction.Finish, BoardAction.ApproveAnyway, BoardAction.Clear),
            BoardAction.entries.filter { it.destructive }.toSet(),
        )
    }
}
