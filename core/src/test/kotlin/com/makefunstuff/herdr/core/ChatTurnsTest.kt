package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.activity.ActivityLog
import com.makefunstuff.herdr.core.chat.AwaySummary
import com.makefunstuff.herdr.core.chat.ChatItem
import com.makefunstuff.herdr.core.chat.ChatTurns
import com.makefunstuff.herdr.core.chat.Transcript
import com.makefunstuff.herdr.core.chat.WorkCounts
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatTurnsTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun read(agent: String, lines: String): Transcript =
        Transcript.forAgent(agent)!!.also { t ->
            t.activity = ActivityLog.forAgent(agent)
            lines.trimIndent().lines().forEach(t::feed)
        }

    /** Two asks: the first done in 2m 36s with an edit that failed once, the second still at work. */
    private val claude = """
        {"type":"user","uuid":"u1","timestamp":"2026-10-17T06:58:10.000Z","cwd":"/home/demo/src/app","message":{"role":"user","content":"Add pull-to-refresh."}}
        {"type":"assistant","uuid":"a1","timestamp":"2026-10-17T06:58:16.000Z","message":{"role":"assistant","content":[{"type":"text","text":"Looking at the screen first."}]}}
        {"type":"assistant","uuid":"a2","timestamp":"2026-10-17T06:58:18.000Z","message":{"role":"assistant","content":[{"type":"tool_use","id":"t1","name":"Read","input":{"file_path":"/home/demo/src/app/core/src/main/kotlin/demo/host/HostState.kt"}}]}}
        {"type":"user","uuid":"r1","timestamp":"2026-10-17T06:58:18.200Z","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"120 lines","is_error":false}]}}
        {"type":"assistant","uuid":"a3","timestamp":"2026-10-17T06:58:40.000Z","message":{"role":"assistant","content":[{"type":"tool_use","id":"t2","name":"Edit","input":{"file_path":"/home/demo/src/app/core/src/main/kotlin/demo/host/HostState.kt","old_string":"val a","new_string":"val a\nval b"}}]}}
        {"type":"user","uuid":"r2","timestamp":"2026-10-17T06:58:40.300Z","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t2","content":"String not found","is_error":true}]}}
        {"type":"assistant","uuid":"a4","timestamp":"2026-10-17T06:59:00.000Z","message":{"role":"assistant","content":[{"type":"tool_use","id":"t3","name":"Edit","input":{"file_path":"/home/demo/src/app/core/src/main/kotlin/demo/host/HostState.kt","old_string":"val x","new_string":"val x\nval y"}}]}}
        {"type":"user","uuid":"r3","timestamp":"2026-10-17T06:59:00.300Z","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t3","content":"Updated","is_error":false}]}}
        {"type":"assistant","uuid":"a5","timestamp":"2026-10-17T06:59:10.000Z","message":{"role":"assistant","content":[{"type":"tool_use","id":"t4","name":"Bash","input":{"command":"./gradlew test","description":"Run the tests"}}]}}
        {"type":"user","uuid":"r4","timestamp":"2026-10-17T07:00:06.000Z","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t4","content":"BUILD SUCCESSFUL in 50s","is_error":false}]}}
        {"type":"assistant","uuid":"a6","timestamp":"2026-10-17T07:00:46.000Z","message":{"role":"assistant","content":[{"type":"text","text":"**Pull-to-refresh** is in. The spinner stays until every machine answered."}]}}
        {"type":"user","uuid":"u2","timestamp":"2026-10-17T07:39:02.000Z","message":{"role":"user","content":"Also show when each machine last answered."}}
        {"type":"assistant","uuid":"a7","timestamp":"2026-10-17T07:39:09.000Z","message":{"role":"assistant","content":[{"type":"text","text":"Adding a timestamp."}]}}
        {"type":"assistant","uuid":"a8","timestamp":"2026-10-17T07:39:42.000Z","message":{"role":"assistant","content":[{"type":"tool_use","id":"t5","name":"Edit","input":{"file_path":"/home/demo/src/app/core/src/main/kotlin/demo/host/HostSession.kt","old_string":"a","new_string":"b"}}]}}
    """

    @Test
    fun turnsFoldTheirWorkUnderOneHeader() {
        val t = read("claude", claude)
        val turns = ChatTurns.of(t.items(), t.activity!!.turns())
        assertEquals(2, turns.size)
        val first = turns[0]
        assertEquals("u1", first.key)
        assertEquals("Add pull-to-refresh.", first.user?.text)
        // The narration and the calls are work; what it said after the last call is the answer.
        assertEquals(listOf("a1/0", "t1", "t2", "t3", "t4"), first.work.map { it.id })
        assertEquals(listOf("a6/0"), first.answer.map { it.id })
        assertEquals(WorkCounts(edits = 2, reads = 1, commands = 1), first.counts)
        assertEquals("2 edits · 1 file read · 1 command", first.counts.summary)
        assertEquals(1, first.failed)
        assertEquals("2m 36s", ChatTurns.duration(first.took!!))
        assertEquals(ms("2026-10-17T06:58:10Z"), first.at)
        assertFalse(first.keepsOpen)
        assertTrue(first.hasWork)

        // The second is still at work: no answer yet, its words stay in the work and keep it open.
        val second = turns[1]
        assertEquals(listOf("a7/0", "t5"), second.work.map { it.id })
        assertTrue(second.answer.isEmpty())
        assertTrue(second.keepsOpen)
        assertEquals("1 edit", second.counts.summary)
        assertEquals("editing HostSession.kt", ChatTurns.doing(t.activity!!.turns().last()))
    }

    @Test
    fun toolRowsShowShortPathsAndWhatAnEditChanged() {
        val t = read("claude", claude)
        val steps = ChatTurns.Steps(t.activity!!.turns())
        val tools = t.items().filterIsInstance<ChatItem.Tool>().associateBy { it.id }
        val read = ChatTurns.line(tools.getValue("t1"), steps)
        assertEquals("core/…/host/HostState.kt", read.text)
        assertTrue(read.isPath)
        assertNull(read.added)
        val edit = ChatTurns.line(tools.getValue("t3"), steps)
        assertEquals("core/…/host/HostState.kt", edit.text)
        assertEquals(2, edit.added)
        assertEquals(1, edit.removed)
        // A command keeps its own words.
        assertEquals("Run the tests", ChatTurns.line(tools.getValue("t4"), steps).text)
    }

    @Test
    fun codexFileChangesCountEveryFile() {
        val t = read(
            "codex",
            """
            {"timestamp":"2026-10-16T09:12:40.000Z","type":"session_meta","payload":{"cwd":"/home/demo/src/website"}}
            {"timestamp":"2026-10-16T09:12:40.000Z","type":"event_msg","payload":{"type":"item_completed","item":{"type":"UserMessage","id":"u1","content":[{"type":"text","text":"Rewrite the install page."}]}}}
            {"timestamp":"2026-10-16T09:13:30.000Z","type":"event_msg","payload":{"type":"item_completed","item":{"type":"FileChange","id":"f1","changes":{"/home/demo/src/website/content/docs/install.md":{"type":"update","unified_diff":"@@ -1,2 +1,3 @@\n # Install\n-old\n+new\n+more\n"},"/home/demo/src/website/README.md":{"type":"add","content":"one\ntwo\n"}},"status":"completed"}}}
            {"timestamp":"2026-10-16T09:13:52.000Z","type":"event_msg","payload":{"type":"item_completed","item":{"type":"CommandExecution","id":"c1","command":["bash","-lc","npm run check-links"],"aggregated_output":"2 broken","exit_code":1,"status":"completed"}}}
            {"timestamp":"2026-10-16T09:14:04.000Z","type":"event_msg","payload":{"type":"item_completed","item":{"type":"AgentMessage","id":"a1","content":[{"type":"text","text":"Done, but two links are broken."}]}}}
            """,
        )
        val turns = ChatTurns.of(t.items(), t.activity!!.turns())
        val turn = turns.single()
        assertEquals("1 edit · 1 command", turn.counts.summary)
        assertEquals(1, turn.failed)
        assertEquals("1m 24s", ChatTurns.duration(turn.took!!))
        val line = ChatTurns.line(turn.work.filterIsInstance<ChatItem.Tool>().first(), ChatTurns.Steps(t.activity!!.turns()))
        assertEquals("content/docs/install.md, README.md", line.text)
        assertEquals(4, line.added)
        assertEquals(1, line.removed)
    }

    @Test
    fun aTurnWithoutAnAnswerAfterItsLastCallStaysOpen() {
        val t = read(
            "claude",
            """
            {"type":"user","uuid":"u1","timestamp":"2026-10-17T07:00:00.000Z","message":{"role":"user","content":"Go."}}
            {"type":"assistant","uuid":"a1","timestamp":"2026-10-17T07:00:05.000Z","message":{"role":"assistant","content":[{"type":"text","text":"Checking."}]}}
            {"type":"assistant","uuid":"a2","timestamp":"2026-10-17T07:00:06.000Z","message":{"role":"assistant","content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"ls"}}]}}
            {"type":"user","uuid":"r1","timestamp":"2026-10-17T07:00:07.000Z","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"a b","is_error":false}]}}
            {"type":"user","uuid":"r2","timestamp":"2026-10-17T07:00:09.000Z","message":{"role":"user","content":[{"type":"text","text":"[Request interrupted by user]"}]}}
            """,
        )
        val turn = ChatTurns.of(t.items(), t.activity!!.turns()).single()
        assertTrue(turn.answer.isEmpty())
        assertTrue(turn.keepsOpen)
        assertEquals("Interrupted", (turn.work.last() as ChatItem.Notice).text)
    }

    @Test
    fun wordsAloneHaveNoHeader() {
        val t = read(
            "claude",
            """
            {"type":"user","uuid":"u1","timestamp":"2026-10-17T07:00:00.000Z","message":{"role":"user","content":"Hi"}}
            {"type":"assistant","uuid":"a1","timestamp":"2026-10-17T07:00:03.000Z","message":{"role":"assistant","content":[{"type":"text","text":"Hello."}]}}
            """,
        )
        val turn = ChatTurns.of(t.items(), t.activity!!.turns()).single()
        assertFalse(turn.hasWork)
        assertEquals(listOf("a1/0"), turn.answer.map { it.id })
    }

    @Test
    fun shortPaths() {
        assertEquals("core/…/host/HostState.kt", ChatTurns.shortPath("core/src/main/kotlin/com/makefunstuff/herdr/core/host/HostState.kt"))
        assertEquals("app/build.gradle.kts", ChatTurns.shortPath("app/build.gradle.kts"))
        assertEquals("~/src/notes/todo.md", ChatTurns.shortPath("/home/demo/src/notes/todo.md"))
        assertEquals("~/…/notes/todo.md", ChatTurns.shortPath("/home/demo/src/app/notes/todo.md"))
        assertEquals("~/.bashrc", ChatTurns.shortPath("/home/demo/.bashrc"))
        assertEquals("/…/nginx/site.conf", ChatTurns.shortPath("/etc/nginx/sites/nginx/site.conf"))
    }

    @Test
    fun durations() {
        assertEquals("0s", ChatTurns.duration(300))
        assertEquals("7s", ChatTurns.duration(7_400))
        assertEquals("1m 12s", ChatTurns.duration(72_000))
        assertEquals("3m", ChatTurns.duration(180_000))
        assertEquals("1h 5m", ChatTurns.duration(3_900_000))
    }

    @Test
    fun awaySaysWhatHappenedSinceTheChatWasLastOnScreen() {
        val t = read("claude", claude)
        val turns = t.activity!!.turns()
        val now = ms("2026-10-17T07:42:00Z")
        val away = assertNotNull(AwaySummary.of(turns, since = ms("2026-10-17T06:58:00Z"), now = now, live = true))
        assertEquals("44 min", away.gone)
        assertEquals(
            listOf(
                "Answered: “Pull-to-refresh is in.”",
                "You asked: “Also show when each machine last answered.”; now editing HostSession.kt",
                "1 file changed, +2 −1 · 1 command · tests passed",
            ),
            away.lines,
        )
        // Looked at a minute ago: no card.
        assertNull(AwaySummary.of(turns, since = now - 60_000, now = now, live = true))
        // Never looked: no card either.
        assertNull(AwaySummary.of(turns, since = 0, now = now, live = true))
        // Nothing happened since.
        assertNull(AwaySummary.of(turns.take(1), since = ms("2026-10-17T07:10:00Z"), now = now, live = false))
    }

    @Test
    fun awayFromWorkStillRunning() {
        val t = read("claude", claude)
        val turns = t.activity!!.turns()
        val away = assertNotNull(AwaySummary.of(turns, since = ms("2026-10-17T07:39:05Z"), now = ms("2026-10-17T07:50:00Z"), live = true))
        assertEquals("10 min", away.gone)
        assertEquals(listOf("Now editing HostSession.kt"), away.lines)
    }

    @Test
    fun goneReadsLikeACard() {
        assertEquals("25 min", AwaySummary.gone(25 * 60_000L))
        assertEquals("3 h", AwaySummary.gone(3 * 3_600_000L + 2 * 60_000L))
        assertEquals("2 h 40 min", AwaySummary.gone(160 * 60_000L))
        assertEquals("1 day", AwaySummary.gone(30 * 3_600_000L))
        assertEquals("3 days", AwaySummary.gone(75 * 3_600_000L))
    }
}
