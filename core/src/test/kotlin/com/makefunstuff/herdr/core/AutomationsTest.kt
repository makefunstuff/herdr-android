package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.board.Automation
import com.makefunstuff.herdr.core.board.AutomationCommands
import com.makefunstuff.herdr.core.board.AutomationDraft
import com.makefunstuff.herdr.core.board.AutomationReader
import com.makefunstuff.herdr.core.board.AutomationWords
import com.makefunstuff.herdr.core.board.BoardEvent
import com.makefunstuff.herdr.core.board.BoardTask
import com.makefunstuff.herdr.core.board.Firing
import com.makefunstuff.herdr.core.board.Outcome
import com.makefunstuff.herdr.core.board.Schedule
import com.makefunstuff.herdr.core.board.Trigger
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The board's automations as the phone reads, says and changes them; outputs from herdr-projects 1.0.1. */
class AutomationsTest {
    private fun res(name: String) = javaClass.getResource("/board/$name")!!.readText()

    private fun task(id: String, state: String, stage: String, kind: String = "", research: Boolean = false) =
        BoardTask(id, "AND-4", "t", state, stage, kind, "claude", "", null, null, research, "", false, false, 0, null, false)

    @Test
    fun readsTheList() {
        val list = AutomationReader.list("Welcome!\n" + res("automations.json"))
        assertEquals(listOf("Bump dependencies", "Hunt flaky tests", "Look at failed reviews"), list.map { it.name })
        val bump = list[0]
        assertEquals(Trigger.Schedule("mon 07:00"), bump.trigger)
        assertEquals("claude/claude-opus-5-5", bump.agent)
        assertEquals(1792382400000, bump.next)
        assertEquals("repo/4", bump.last?.task)
        assertEquals("added, but no agent could be put on it: no agent left", bump.last?.note)
        assertTrue(list[1].research)
        assertEquals("true", list[1].settings["research.only"])
        val reviews = list[2]
        assertEquals(false, reviews.on)
        assertEquals(Trigger.Event(BoardEvent.ReviewFailed, everyRepo = true), reviews.trigger)
        assertNull(reviews.next)
        assertNull(reviews.last)
    }

    @Test
    fun readsAnEmptyListAndARefusal() {
        assertEquals(emptyList(), AutomationReader.list("[]\n"))
        val e = assertFailsWith<IOException> { AutomationReader.list("Unknown: automations.\n") }
        assertEquals("Unknown: automations.", e.message)
    }

    @Test
    fun readsOneWithItsFirings() {
        val (a, firings) = AutomationReader.show(res("automation-show.json"))
        assertEquals("Bump dependencies", a.name)
        assertEquals(3, firings.size)
        assertEquals("repo/4", firings[0].task)
        assertEquals(2, firings[1].times)
        assertEquals("its last task, repo/3, is still being worked on", firings[1].skipped)
        assertEquals("run", firings[1].cause)
        assertEquals("repo/3", firings[2].task)
        assertEquals(1, firings[2].times)
    }

    @Test
    fun triggersFromTheBoardsWords() {
        assertEquals(Trigger.Event(BoardEvent.Merged), Trigger.of("when a task is merged"))
        assertEquals(Trigger.Event(BoardEvent.NeedsYou, true), Trigger.of("when a task needs you, in every repo"))
        assertEquals(Trigger.Event(BoardEvent.Approval), Trigger.of("when a task waits for approval"))
        assertEquals(Trigger.Schedule("cron 0 9 * * 1-5"), Trigger.of("cron 0 9 * * 1-5"))
        assertEquals(listOf("--when", "failed", "--every-repo"), Trigger.Event(BoardEvent.Failed, true).args())
        assertEquals(listOf("--schedule", "every 2h"), Trigger.Schedule("every 2h").args())
    }

    @Test
    fun schedulesBothWays() {
        assertEquals(Schedule.Every(2, hours = true), Schedule.of("every 2h"))
        assertEquals(Schedule.Every(30, hours = false), Schedule.of("every 30m"))
        assertEquals(Schedule.Daily("09:00"), Schedule.of("daily 09:00"))
        assertEquals(Schedule.Days(listOf("mon", "thu"), "07:30"), Schedule.of("mon,thu 07:30"))
        assertEquals(Schedule.Cron("0 9 * * 1"), Schedule.of("cron \"0 9 * * 1\""))
        assertNull(Schedule.of("every 2d"))

        assertEquals("every 45m", Schedule.Every(45, false).text())
        assertEquals("daily 07:05", Schedule.Daily("7:05").text())
        assertEquals("mon,fri 18:00", Schedule.Days(listOf("fri", "mon"), "18:00").text())
        assertEquals("cron 0 9 * * 1", Schedule.Cron(" 0 9  * * 1 ").text())
        assertNull(Schedule.Days(emptyList(), "07:00").text())
        assertNull(Schedule.Daily("25:00").text())
        assertNull(Schedule.Every(0, true).text())
        assertNull(Schedule.Cron("0 9 *").text())
    }

    @Test
    fun saysTriggersInWords() {
        fun say(t: String) = AutomationWords.trigger(Trigger.of(t))
        assertEquals("Mondays at 07:00", say("mon 07:00"))
        assertEquals("Mondays and Thursdays at 09:00", say("mon,thu 09:00"))
        assertEquals("Mondays, Wednesdays and Fridays at 09:00", say("mon,wed,fri 09:00"))
        assertEquals("Weekdays at 09:00", say("mon,tue,wed,thu,fri 09:00"))
        assertEquals("Weekends at 10:00", say("sat,sun 10:00"))
        assertEquals("Daily at 02:00", say("daily 02:00"))
        assertEquals("Every 2 hours", say("every 2h"))
        assertEquals("Every hour", say("every 60m"))
        assertEquals("Every 30 minutes", say("every 30m"))
        assertEquals("Every 2 days", say("every 48h"))
        assertEquals("Cron 0 9 * * 1", say("cron 0 9 * * 1"))
        assertEquals("When a task failed its review", say("when a task failed its review"))
        assertEquals("When a task is merged, in any repo", say("when a task is merged, in every repo"))
        assertEquals("on Mondays at 07:00", AutomationWords.inSentence(Trigger.of("mon 07:00")))
        assertEquals("on weekdays at 09:00", AutomationWords.inSentence(Trigger.of("mon,tue,wed,thu,fri 09:00")))
        assertEquals("every 2 hours", AutomationWords.inSentence(Trigger.of("every 2h")))
        assertEquals("when a task is merged", AutomationWords.inSentence(Trigger.of("when a task is merged")))
    }

    @Test
    fun saysHowTheLastRunWent() {
        val now = 1_792_400_000_000
        val base = Automation("a1", "Bump", true, Trigger.Schedule("mon 07:00"), "website", next = now + 8 * 3_600_000)
        assertEquals(Outcome("Never run yet · next in 8 h", Outcome.Mood.Quiet), Outcome.of(base, null, now))
        assertEquals("Paused · never run", Outcome.of(base.copy(on = false, next = null), null, now).text)

        val made = base.copy(last = Firing(now - 3 * 86_400_000, "schedule", "mon 07:00", task = "website/4"))
        assertEquals(Outcome("Last run: AND-4 done · 3 days ago", Outcome.Mood.Good, "website/4"), Outcome.of(made, task("website/4", "done", "done"), now))
        assertEquals("Last run: AND-4 report ready · 3 days ago", Outcome.of(made, task("website/4", "done", "done", research = true), now).text)
        assertEquals(Outcome.Mood.Stuck, Outcome.of(made, task("website/4", "error", "working"), now).mood)
        assertEquals("Last run got stuck (AND-4)", Outcome.of(made, task("website/4", "error", "working"), now).text)
        assertEquals("Last run asks you (AND-4)", Outcome.of(made, task("website/4", "blocked", "working", "question"), now).text)
        assertEquals(Outcome.Mood.Working, Outcome.of(made, task("website/4", "working", "working"), now).mood)
        assertEquals("Last run: AND-4 ready to approve · 3 days ago", Outcome.of(made, task("website/4", "blocked", "approval", "permission"), now).text)
        assertEquals(Outcome("Last run: website/4 · 3 days ago", Outcome.Mood.Quiet, "website/4"), Outcome.of(made, null, now))
        assertEquals("Paused · last run 3 days ago", Outcome.of(made.copy(on = false), null, now).text)

        val skipped = base.copy(last = Firing(now - 20 * 60_000, "run", "run now", skipped = "its last task, website/4, is still being worked on", times = 3))
        assertEquals("Last run skipped (3 times): its last task, website/4, is still being worked on · 20 min ago", Outcome.of(skipped, null, now).text)

        val noAgent = base.copy(last = Firing(now - 60_000, "schedule", "mon 07:00", task = "website/5", note = "added, but no agent could be put on it: x"))
        assertEquals(Outcome("Last run added AND-4, but no agent started · 1 min ago", Outcome.Mood.Stuck, "website/5"), Outcome.of(noAgent, task("website/5", "idle", ""), now))

        val runaway = base.copy(on = false, offWhy = "Turned itself off: more than 10 firings in an hour")
        assertEquals(Outcome.Mood.Stuck, Outcome.of(runaway, null, now).mood)
    }

    @Test
    fun addsWithTheBoardsOwnOptions() {
        val d = AutomationDraft(" Bump deps ", "Update them\nand open a PR", "website", Trigger.Schedule("mon 07:00"), research = true, agent = "codex/gpt-6.1-sol")
        assertEquals(
            listOf("automations", "add", "Bump deps", "--prompt", "Update them\nand open a PR", "--schedule", "mon 07:00", "--repo", "website", "--research", "--worker.agent", "codex/gpt-6.1-sol"),
            AutomationCommands.add(d),
        )
        assertEquals(
            listOf("automations", "add", "Fix", "--prompt", "Fix it", "--when", "review-failed", "--every-repo"),
            AutomationCommands.add(AutomationDraft("Fix", "Fix it", "", Trigger.Event(BoardEvent.ReviewFailed, true))),
        )
    }

    @Test
    fun changesOnlyWhatChanged() {
        val a = AutomationReader.list(res("automations.json"))[0]
        assertEquals(emptyList(), AutomationCommands.change(a, AutomationDraft.of(a)))
        assertEquals(
            listOf(listOf("automations", "change", a.id, "--name", "Bump deps", "--schedule", "mon,thu 07:00", "--research")),
            AutomationCommands.change(a, AutomationDraft.of(a).copy(name = "Bump deps", trigger = Trigger.Schedule("mon,thu 07:00"), research = true)),
        )
        assertEquals(
            listOf(listOf("unset", "worker.agent", "--automation", a.id)),
            AutomationCommands.change(a, AutomationDraft.of(a).copy(agent = "")),
        )
        assertEquals(
            listOf(listOf("automations", "change", a.id, "--prompt", "New words", "--no-research"), listOf("set", "worker.agent", "pi/x", "--automation", a.id)),
            AutomationCommands.change(a.copy(research = true), AutomationDraft.of(a).copy(prompt = "New words", agent = "pi/x")),
        )
        assertEquals(listOf("automations", "off", a.id), AutomationCommands.turn(a.id, false))
        assertEquals(listOf("automations", "run", a.id), AutomationCommands.run(a.id))
    }
}
