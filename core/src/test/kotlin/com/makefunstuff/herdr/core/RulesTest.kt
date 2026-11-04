package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.board.WorkStatus
import com.makefunstuff.herdr.core.board.WorkTask
import com.makefunstuff.herdr.core.home.HomeAgent
import com.makefunstuff.herdr.core.home.Waiting
import com.makefunstuff.herdr.core.home.WaitingNews
import com.makefunstuff.herdr.core.push.Alert
import com.makefunstuff.herdr.core.push.AlertChange
import com.makefunstuff.herdr.core.push.AlertRules
import com.makefunstuff.herdr.core.push.Alerts
import com.makefunstuff.herdr.core.push.HerdrPush
import com.makefunstuff.herdr.core.push.Presence
import com.makefunstuff.herdr.core.push.PresenceBook
import com.makefunstuff.herdr.core.push.PushEvent
import com.makefunstuff.herdr.core.push.PushMessages
import com.makefunstuff.herdr.core.push.QuietHours
import com.makefunstuff.herdr.core.push.Watch
import com.makefunstuff.herdr.core.push.Watches
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The notification rules: kinds switched on and off, keeping quiet at the machine, one-shot watches, and the in-app alert's news. */
class RulesTest {
    private fun agent(status: String, seq: Long = 7, pane: String = "w1:p1") = AgentInfo(
        name = "api", agent = "claude", agentStatusRaw = status, workspaceId = "w1", tabId = "t1", paneId = pane, stateChangeSeq = seq,
        title = "Speed up /jobs",
    )

    private fun show(c: AlertChange): Alert = assertIs<AlertChange.Show>(c).alert

    @Test
    fun eachAlertHasItsKind() {
        assertEquals(Alert.Kind.Finished, show(Alerts.of("m1", "devbox", PushEvent.Agent(agent("done"), "api"))).kind)
        assertEquals(Alert.Kind.NeedsYou, show(Alerts.of("m1", "devbox", PushEvent.Agent(agent("blocked"), "api"))).kind)
        assertEquals(Alert.Kind.WentWrong, show(Alerts.of("m1", "devbox", PushEvent.Quick("q1", "Find TODOs", "failed", error = "exit 1"))).kind)
        assertEquals(Alert.Kind.Finished, show(Alerts.of("m1", "devbox", PushEvent.Quick("q1", "Find TODOs", "done", "3 found"))).kind)
    }

    @Test
    fun finishedIsOffByDefault() {
        val r = AlertRules()
        fun a(kind: Alert.Kind) = Alert("t", "", kind, "m1", "", "")
        assertTrue(r.allows(a(Alert.Kind.NeedsYou)))
        assertFalse(r.allows(a(Alert.Kind.Finished)))
        assertTrue(r.allows(a(Alert.Kind.WentWrong)))
        // A provider near its limit comes by default, and can be turned off.
        assertTrue(r.allows(a(Alert.Kind.Limits)))
        assertFalse(r.copy(limits = false).allows(a(Alert.Kind.Limits)))
        // A test and a watch you set always come.
        val none = AlertRules(needsYou = false, finished = false, wentWrong = false, limits = false)
        assertTrue(none.allows(a(Alert.Kind.Test)))
        assertTrue(none.allows(a(Alert.Kind.Watch)))
        assertFalse(none.allows(a(Alert.Kind.NeedsYou)))
        assertEquals("Needs you, Went wrong and Limits", r.label)
        assertEquals("Needs you and Went wrong", r.copy(limits = false).label)
        assertEquals("Needs you, Finished, Went wrong and Limits", AlertRules.ALL.label)
        assertEquals("Only your watches", none.label)
        // Quiet hours hold what finished and a watch; what went wrong comes through, as what needs you does.
        assertTrue(QuietHours.holds(a(Alert.Kind.Watch)))
        assertFalse(QuietHours.holds(a(Alert.Kind.WentWrong)))
        assertTrue(QuietHours.holds(a(Alert.Kind.Limits)))
    }

    @Test
    fun atTheMachineForTwoMinutes() {
        val book = PresenceBook()
        val t0 = 1_000_000_000L
        assertFalse(book.present(t0))
        // Typed 30 s before devbox sent its push.
        book.record("m1", "devbox", Presence(typedAgo = 30, unlocked = null), t0)
        assertEquals("devbox", book.where(t0))
        assertTrue(book.present(t0 + 89_000))
        assertFalse(book.present(t0 + 91_000))
        // A machine whose screen is unlocked counts while what it said is fresh, whichever machine pings.
        book.record("m2", "gpu-box", Presence(typedAgo = null, unlocked = true), t0 + 100_000)
        assertEquals("gpu-box", book.where(t0 + 200_000))
        assertFalse(book.present(t0 + 221_000))
        // Locked, and nobody typed for long: away.
        book.record("m2", "gpu-box", Presence(typedAgo = 3600, unlocked = false), t0 + 300_000)
        assertNull(book.where(t0 + 300_000))
    }

    @Test
    fun aPushSaysWhoIsAtTheMachine() {
        val msg = assertNotNull(PushMessages.parse("""{"v":1,"host":"devbox","at":5,"events":[{"type":"test"}],"present":{"typed":12,"unlocked":false}}"""))
        assertEquals(Presence(typedAgo = 12, unlocked = false), msg.presence)
        assertEquals(Presence(null, null), assertNotNull(PushMessages.parse("""{"v":1,"at":5,"events":[],"present":{"typed":null,"unlocked":null}}""")).presence)
        // An older herdr-push says nothing about it.
        assertNull(assertNotNull(PushMessages.parse("""{"v":1,"at":5,"events":[]}""")).presence)
    }

    @Test
    fun anAgentWatchFiresWhenItFinishes() {
        val w = Watches.offers("m1", agent("working", seq = 7)).single()
        assertEquals("When api finishes", w.label)
        // Done, or idle, at a later state change.
        assertNull(Watches.agent(w, agent("working", seq = 7), "devbox"))
        assertNull(Watches.agent(w, agent("blocked", seq = 8), "devbox"))
        val fired = assertNotNull(Watches.agent(w, agent("done", seq = 9), "devbox")?.alert)
        assertEquals("api finished", fired.title)
        assertEquals(Alert.Kind.Watch, fired.kind)
        assertEquals("w1:p1", fired.paneId)
        assertEquals(listOf("Reply"), fired.actions.map { it.label })
        assertNotNull(Watches.agent(w, agent("idle", seq = 9), "devbox"))
        // Its pane closed: it says so.
        assertEquals("api is gone", Watches.agent(w, null, "devbox")?.alert?.title)
        // An agent at rest offers nothing to wait for.
        assertTrue(Watches.offers("m1", agent("idle")).isEmpty())
    }

    @Test
    fun watchesFireFromPushesAndLooks() {
        val a = Watch("m1", Watch.What.AgentDone, paneId = "w1:p1", name = "api", seq = 7)
        val other = Watch("m2", Watch.What.AgentDone, paneId = "w1:p1", name = "api", seq = 7)
        val all = listOf(a, other)
        // The agent's done push: it fires, the other machine's doesn't.
        val fired = Watches.events(all, "m1", "devbox", listOf(PushEvent.Agent(agent("done", seq = 9), "api")))
        assertEquals(listOf(a.id), fired.map { it.watch.id })
        // A look: the agent still works.
        assertTrue(Watches.look(all, "m1", "devbox", listOf(agent("working"))).isEmpty())
        // A look without the agent: its pane is gone.
        assertEquals(listOf(a.id), Watches.look(all, "m1", "devbox", emptyList()).map { it.watch.id })
        // herdr-push follows what is watched on its machine.
        assertEquals("pane:w1:p1", HerdrPush.watching(all, "m1"))
        assertTrue(HerdrPush.subscription("p:m1", "Pixel", "https://x", ByteArray(32), "default", null, watchPanes = listOf("w1:p1")).contains("\"watch_panes\":[\"w1:p1\"]"))
    }

    @Test
    fun theInAppAlertTellsOnlyWhatIsNew() {
        fun asking(seq: Long, pane: String = "w1:p1") = Waiting.Question(HomeAgent("m1", "devbox", agent("blocked", seq, pane), "api › main"), null)
        val news = WaitingNews()
        // The machine's first read: what already waited isn't news.
        assertTrue(news.update(listOf(asking(4)), agentsRead = setOf("m1"), boardsRead = emptySet()).isEmpty())
        assertTrue(news.update(listOf(asking(4)), setOf("m1"), emptySet()).isEmpty())
        // Another agent asks, and the first asks again (a new question): both are news.
        val fresh = news.update(listOf(asking(6), asking(5, "w1:p2")), setOf("m1"), emptySet())
        assertEquals(listOf("agent/m1/w1:p1", "agent/m1/w1:p2"), fresh.map { it.key })
        // A task from a board read for the first time isn't news either.
        val t = Waiting.Task("m1", "devbox", WorkTask("HA-3", "HA-3", "Jobs", "s1", WorkStatus.Ready), null)
        assertTrue(news.update(listOf(t), setOf("m1"), setOf("m1")).isEmpty())
    }
}
