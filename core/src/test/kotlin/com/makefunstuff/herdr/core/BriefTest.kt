package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.api.SessionSnapshot
import com.makefunstuff.herdr.core.api.TabInfo
import com.makefunstuff.herdr.core.api.WorkspaceInfo
import com.makefunstuff.herdr.core.board.BoardTask
import com.makefunstuff.herdr.core.board.BoardTasks
import com.makefunstuff.herdr.core.brief.Baseline
import com.makefunstuff.herdr.core.brief.Brief
import com.makefunstuff.herdr.core.brief.BriefItem
import com.makefunstuff.herdr.core.brief.BriefMachine
import com.makefunstuff.herdr.core.brief.BriefSchedule
import com.makefunstuff.herdr.core.brief.BriefWords
import com.makefunstuff.herdr.core.brief.Briefs
import com.makefunstuff.herdr.core.brief.Journal
import com.makefunstuff.herdr.core.brief.JournalEntry
import com.makefunstuff.herdr.core.brief.Journals
import com.makefunstuff.herdr.core.brief.MachineLook
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BriefTest {
    private val zone: ZoneId = ZoneOffset.UTC

    /** 2026-10-18 07:30 UTC, a Sunday: the brief's "now". */
    private val now = LocalDateTime.of(2026, 10, 18, 7, 30).toInstant(ZoneOffset.UTC).toEpochMilli()
    /** 23:10 the night before: when the person last looked. */
    private val since = LocalDateTime.of(2026, 10, 17, 23, 10).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun task(json: String, key: String = ""): BoardTask = assertNotNull(BoardTasks.task(Json.parseToJsonElement(json).jsonObject, key))

    private val merged = task("""{"id":"herdr-android/28","title":"Floating chat bubbles","state":"done","stage":"done","pr":"https://github.com/x/herdr-android/pull/26","msg":"Merged"}""", "AND-28")
    private val report = task("""{"id":"herdr-android/36","title":"Research new UI ideas","state":"done","stage":"done","research":true}""", "AND-36")
    private val approve = task("""{"id":"herdr-android/9","title":"Show when each machine last answered","state":"blocked","stage":"approval","kind":"permission","review":{"pass":true}}""", "AND-9")
    private val reviewFailed = task("""{"id":"website/4","title":"Rewrite the install page","state":"blocked","stage":"approval","kind":"permission","review":{"pass":false}}""", "WEB-4")
    private val oldError = task("""{"id":"api-server/2","title":"Retry the webhook","state":"error","stage":"working","msg":"git push failed"}""", "API-2")
    private val working = task("""{"id":"herdr-android/30","title":"Brief","state":"working","stage":"working","progress":40,"msg":"Writing"}""", "AND-30")
    private val queued = task("""{"id":"herdr-android/31","title":"Later","state":"idle","stage":"queued"}""", "AND-31")
    private val remote = task("""{"id":"vm-str:elastic/3","title":"Another machine's","state":"blocked","stage":"approval","kind":"permission"}""")

    private fun agent(pane: String, status: String, name: String? = null, title: String? = null, seq: Long = 3, ws: String = "w1") = AgentInfo(
        name = name, agent = "claude", title = title, agentStatusRaw = status, workspaceId = ws, tabId = "$ws:t1", paneId = pane, stateChangeSeq = seq,
    )

    private fun snapshot(vararg agents: AgentInfo) = SessionSnapshot(
        workspaces = listOf(WorkspaceInfo("w1", label = "api-server"), WorkspaceInfo("w2", label = "AND-30 Brief"), WorkspaceInfo("w3", label = "herdr")),
        tabs = listOf(TabInfo("w1:t1", "w1", label = "main"), TabInfo("w2:t1", "w2", label = "main"), TabInfo("w3:t1", "w3", label = "main")),
        agents = agents.toList(),
    )

    /** Seconds on the machine's clock, which runs [skew] seconds behind the phone's. */
    private fun at(hour: Int, minute: Int, skew: Long = 0) =
        (LocalDateTime.of(2026, 10, if (hour >= 12) 17 else 18, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli() / 1000) - skew

    @Test
    fun journalTellsWhatHappenedAndHowItStands() {
        val skew = 90L
        val asker = agent("w1:p1", "blocked", title = "Speed up /jobs", seq = 9)
        val mine = agent("w3:p1", "done", title = "Tidy the notes", seq = 4, ws = "w3")
        // The task's own worker (its workspace is named after it) and the Coordinator: not listed as agents.
        val worker = agent("w2:p1", "working", name = "sw-herdr-android-30", title = "Brief", ws = "w2")
        val coordinator = agent("w3:p2", "done", name = "sw-coordinator", ws = "w3")
        val journal = Journal(
            now = now / 1000 - skew,
            from = at(20, 0, skew),
            upto = now / 1000 - skew - 5,
            entries = listOf(
                // Before the window: not news.
                JournalEntry.Task(at(22, 0, skew), approve.copy(state = "working", stage = "working", kind = ""), "todo"),
                JournalEntry.Task(at(23, 30, skew), approve, "working"),
                JournalEntry.Task(at(1, 10, skew), merged, "needs"),
                JournalEntry.Task(at(2, 14, skew), reviewFailed, "working"),
                JournalEntry.Task(at(3, 0, skew), working, "new"),
                JournalEntry.Task(at(4, 0, skew), report, "working"),
                JournalEntry.Agent(at(5, 0, skew), mine, "herdr › main", "herdr"),
                JournalEntry.Agent(at(5, 1, skew), coordinator, "herdr › main", "herdr"),
                JournalEntry.Agent(at(5, 2, skew), worker.copy(agentStatusRaw = "done"), "AND-30 Brief › main", "AND-30 Brief"),
                JournalEntry.Agent(at(6, 0, skew), asker, "api-server › main", "api-server"),
            ),
        )
        val look = MachineLook(
            id = "devbox", label = "devbox",
            tasks = listOf(approve, reviewFailed, oldError, working, queued, merged, report, remote),
            snapshot = snapshot(asker, mine, worker, coordinator),
            questions = mapOf("w1:p1" to "May the migration lock the jobs table?"),
            journal = journal,
        )
        val b = Briefs.build(listOf(look), since, now)
        assertEquals(BriefMachine.Coverage.Journal, b.machines.single().coverage)

        // What went wrong: the new failure first, at its time on the phone's clock; the old one is still there, not new.
        assertEquals(listOf("WEB-4", "API-2"), b.failed.map { it.name })
        assertEquals(at(2, 14) * 1000, b.failed[0].at)
        assertTrue(b.failed[0].new && !b.failed[1].new)
        assertEquals("review failed", b.failed[0].status)

        // What waits: the agent that asks (with its question), the task to approve; another machine's task is that machine's.
        assertEquals(listOf("claude", "AND-9"), b.waiting.map { it.name })
        assertEquals("May the migration lock the jobs table?", b.waiting[0].detail)
        assertEquals("ready to approve", b.waiting[1].status)

        assertEquals(listOf(BriefItem.Kind.Report, BriefItem.Kind.Merged), b.done.map { it.kind })
        assertEquals(1, b.merged)
        assertEquals(1, b.reports)
        // Your own agent that finished; not the Coordinator's replies nor the task's worker.
        assertEquals(listOf("Tidy the notes"), b.finished.map { it.title })
        assertEquals(listOf("AND-30"), b.working.map { it.name })
        assertEquals(1, b.started)
        assertEquals(1, b.added)

        assertEquals("2 failed · 2 wait for you · 1 merged · 1 report · 1 agent finished", BriefWords.headline(b))
        assertEquals("Since 23:10 yesterday", BriefWords.since(b, zone))
        assertEquals("Morning brief", BriefWords.title(b.at, zone))
    }

    @Test
    fun readAloud() {
        val b = Brief(
            at = now, since = since,
            machines = listOf(
                BriefMachine("devbox", "devbox", BriefMachine.Coverage.Journal),
                BriefMachine("gpu", "gpu-box", BriefMachine.Coverage.Journal),
                BriefMachine("mini", "mac-mini", BriefMachine.Coverage.Unreached, "No route to host"),
            ),
            failed = listOf(BriefItem(BriefItem.Kind.Failed, "devbox", "devbox", "Rewrite the install page", "WEB-4", "review failed", taskId = "website/4")),
            waiting = listOf(
                BriefItem(BriefItem.Kind.Waiting, "devbox", "devbox", "Speed up /jobs", "claude", "asks you", "May it lock `jobs`?", paneId = "w1:p1"),
            ),
            done = (1..7).map { BriefItem(BriefItem.Kind.Merged, "gpu", "gpu-box", "Task $it", "EVAL-$it", "merged", taskId = "eval/$it") },
            working = listOf(BriefItem(BriefItem.Kind.Working, "devbox", "devbox", "Brief", "AND-30", "working", taskId = "herdr-android/30")),
        )
        val lines = BriefWords.spoken(b, zone)
        assertEquals(
            listOf(
                "Good morning. Here is what happened since 23:10 yesterday, on devbox and gpu-box.",
                "One task went wrong:",
                "Rewrite the install page: review failed. On devbox.",
                "One thing waits for you:",
                "claude asks about Speed up /jobs: May it lock jobs? On devbox.",
                "7 tasks merged:",
                "Task 1. On gpu-box.", "Task 2. On gpu-box.", "Task 3. On gpu-box.", "Task 4. On gpu-box.", "Task 5. On gpu-box.",
                "And 2 more.",
                "Still at work: 1 task.",
                "mac-mini couldn't be reached.",
                "That's all.",
            ),
            lines.map { it.text },
        )
        // Each item's sentence says which item it is, so the screen can show it while it is read.
        assertEquals("task:devbox:website/4", lines[2].ref)
        assertNull(lines[0].ref)

        val note = BriefWords.lines(b)
        assertEquals(6, note.size)
        assertEquals("Failed: Rewrite the install page (review failed)", note[0])
        assertEquals("Asks: claude · Speed up /jobs", note[1])
        assertEquals("and 4 more", note.last())
    }

    @Test
    fun nothingNew() {
        val b = Briefs.build(
            listOf(MachineLook("devbox", "devbox", tasks = listOf(working), snapshot = snapshot(), journal = Journal(now / 1000, at(20, 0), now / 1000, emptyList()))),
            since, now,
        )
        assertTrue(b.quiet)
        assertEquals("Nothing new · 1 at work", BriefWords.headline(b))
        assertEquals(
            listOf(
                "Good morning. Here is what happened since 23:10 yesterday, on devbox.",
                "Nothing new happened.",
                "Still at work: 1 task.",
                "That's all.",
            ),
            BriefWords.spoken(b, zone).map { it.text },
        )
    }

    @Test
    fun withoutAJournalItComparesWithWhatWasSeen() {
        val seenAt = since - 60_000
        val base = Baseline(
            tasks = mapOf(approve.id to "Approval", working.id to "Queued", merged.id to "Reviewing", oldError.id to "Error"),
            tasksAt = seenAt,
            agents = mapOf("w3:p1" to "done#3"),
            agentsAt = seenAt,
        )
        val again = agent("w3:p1", "done", title = "Tidy the notes", seq = 4, ws = "w3")
        val stillDone = agent("w1:p1", "done", title = "Old work", seq = 2)
        val look = MachineLook(
            "mini", "mac-mini",
            tasks = listOf(approve, working, merged, oldError, reviewFailed),
            snapshot = snapshot(again, stillDone.copy(paneId = "w1:p9")),
            baseline = base,
        )
        val b = Briefs.build(listOf(look), since, now)
        val m = b.machines.single()
        assertEquals(BriefMachine.Coverage.Compared, m.coverage)
        assertEquals(seenAt, m.upto)
        assertEquals(listOf("AND-28"), b.done.map { it.name })
        assertEquals(0L, b.done.single().at)
        // The review that failed is new (it wasn't there), the old error isn't.
        assertEquals(listOf("WEB-4" to true, "API-2" to false), b.failed.map { it.name to it.new })
        assertEquals(listOf("AND-9" to false), b.waiting.map { it.name to it.new })
        // An agent that finished again since (a newer state); one never seen before counts as well.
        assertEquals(listOf("Tidy the notes", "Old work"), b.finished.map { it.title })
        assertEquals(1, b.started)
        assertEquals(1, b.added)
        assertEquals(
            "No herdr-push journal there: compared with what the app showed at 23:09 yesterday",
            BriefWords.coverage(m, b, zone),
        )
    }

    @Test
    fun machinesThatCantTellEverything() {
        val b = Briefs.build(
            listOf(
                MachineLook("ci", "ci-runner", snapshot = snapshot(agent("w1:p1", "blocked", title = "Deploy"))),
                MachineLook("mini", "mac-mini", problem = "The machine refused the app's key"),
                MachineLook(
                    "gpu", "gpu-box", boardFailed = true, snapshot = snapshot(),
                    // herdr-push started after the window began.
                    journal = Journal(now / 1000, at(2, 0), now / 1000, emptyList()),
                ),
                MachineLook(
                    "old", "old-box", snapshot = snapshot(),
                    // herdr-push stopped two hours ago.
                    journal = Journal(now / 1000, at(20, 0), at(5, 30), emptyList()),
                ),
            ),
            since, now,
        )
        val (ci, mini, gpu, old) = b.machines
        assertEquals(BriefMachine.Coverage.NowOnly, ci.coverage)
        assertEquals(listOf("Deploy" to true), b.waiting.map { it.title to it.new })
        assertEquals(BriefMachine.Coverage.Unreached, mini.coverage)
        assertEquals("Couldn't be reached: The machine refused the app's key", BriefWords.coverage(mini, b, zone))
        assertEquals(BriefMachine.Coverage.Partial, gpu.coverage)
        assertEquals("herdr-push keeps track from 02:00 on. Its board didn't answer", BriefWords.coverage(gpu, b, zone))
        assertEquals(BriefMachine.Coverage.Partial, old.coverage)
        assertEquals("herdr-push stopped keeping track at 05:30, so what changed after shows as it stands now", BriefWords.coverage(old, b, zone))
        assertEquals(listOf("ci-runner", "gpu-box", "old-box"), b.reached.map { it.label })
    }

    @Test
    fun parsesTheJournal() {
        val o = Json.parseToJsonElement(
            """{"ok":true,"version":"4","now":1760770000,"from":1760700000,"upto":1760769990,"running":true,"entries":[
              {"at":1760760000,"type":"agent","agent":{"pane_id":"w1:p1","workspace_id":"w1","tab_id":"t1","agent":"claude","agent_status":"done","state_change_seq":5},"where":"api › main","workspace":"api"},
              {"at":1760750000,"type":"task","key":"AND-7","task":{"id":"herdr-android/7","title":"Board view","state":"done","stage":"done","pr":"https://x/pull/7"},"was":"needs"},
              {"at":1760750001,"type":"mystery"}
            ]}""",
        ).jsonObject
        val j = assertNotNull(Journals.parse(o))
        assertEquals(1760770000, j.now)
        assertEquals(1760700000, j.from)
        assertEquals(2, j.entries.size)
        // Oldest first.
        val t = j.entries[0] as JournalEntry.Task
        assertEquals("AND-7", t.task.name)
        assertEquals("needs", t.was)
        assertEquals("claude", (j.entries[1] as JournalEntry.Agent).agent.agent)
        // An older herdr-push answers with its help text, not a journal.
        assertNull(Journals.parse(Json.parseToJsonElement("""{"ok":true,"msg":"subscribed"}""").jsonObject))
    }

    @Test
    fun schedule() {
        val s = BriefSchedule(on = true, times = listOf(17 * 60 + 30, 7 * 60 + 30), days = (1..5).toSet())
        assertEquals("07:30 and 17:30, Mon–Fri", s.label)
        // Friday evening, after the last one: Monday morning.
        val friday = LocalDateTime.of(2026, 10, 16, 18, 0)
        assertEquals(LocalDateTime.of(2026, 10, 19, 7, 30), s.next(friday))
        assertEquals(LocalDateTime.of(2026, 10, 16, 17, 30), s.next(LocalDateTime.of(2026, 10, 16, 8, 0)))
        // Exactly at a brief's minute: the next one.
        assertEquals(LocalDateTime.of(2026, 10, 16, 17, 30), s.next(LocalDateTime.of(2026, 10, 16, 7, 30)))
        assertNull(s.copy(on = false).next(friday))
        assertNull(s.copy(days = emptySet()).next(friday))
        assertEquals("Off", s.copy(on = false).label)
        assertEquals(listOf(7 * 60 + 30, 12 * 60, 17 * 60 + 30), s.withTime(12 * 60).withTime(12 * 60).times)
        assertEquals("08:00", BriefSchedule(on = true).label)

        // The window: since the person last looked, at most a week, a day before they ever did.
        assertEquals(now - BriefSchedule.FIRST_WINDOW_MS, BriefSchedule.since(0, now))
        assertEquals(since, BriefSchedule.since(since, now))
        assertEquals(now - BriefSchedule.MAX_WINDOW_MS, BriefSchedule.since(now - 30L * 24 * 3600 * 1000, now))
    }

    @Test
    fun timesInWords() {
        val day = 24L * 3600 * 1000
        assertEquals("07:12", BriefWords.time(now - 18 * 60_000, now, zone))
        assertEquals("23:10 yesterday", BriefWords.time(since, now, zone))
        assertEquals("Wed 23:10", BriefWords.time(since - 3 * day, now, zone))
        assertEquals("7 Oct 23:10", BriefWords.time(since - 10 * day, now, zone))
        assertEquals("Evening brief", BriefWords.title(now + 11 * 3600_000, zone))
        assertEquals("Brief", BriefWords.title(now - 6 * 3600_000, zone))
        assertEquals("Look at a link, then api, main.", BriefWords.speakable("Look at https://x.dev/a_b, then `api` › **main**."))
    }
}
