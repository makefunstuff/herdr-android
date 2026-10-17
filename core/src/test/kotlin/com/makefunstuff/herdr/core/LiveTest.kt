package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.board.BoardTask
import com.makefunstuff.herdr.core.board.BoardTasks
import com.makefunstuff.herdr.core.push.LiveItem
import com.makefunstuff.herdr.core.push.LiveItems
import com.makefunstuff.herdr.core.push.LiveTracker
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveItemsTest {
    private fun task(json: String, key: String = "AND-24"): BoardTask = assertNotNull(BoardTasks.task(Json.parseToJsonElement(json).jsonObject, key))

    private fun agent(status: String = "working", name: String? = "api", title: String? = "Speed up /jobs", pane: String = "w1:p1") = AgentInfo(
        name = name, agent = "claude", agentStatusRaw = status, workspaceId = "w1", tabId = "t1", paneId = pane, title = title,
    )

    @Test
    fun workingWithProgress() {
        val t = task("""{"id":"herdr-android/24","title":"Live Updates","state":"working","stage":"working","msg":"Writing the notifier","progress":40,"eta":12}""")
        val i = assertNotNull(LiveItems.task("m1", "devbox", t, since = 5))
        assertEquals("live:task:m1:herdr-android/24", i.tag)
        assertEquals("AND-24 Live Updates", i.title)
        assertEquals("Working · 40% · about 12 min left · Writing the notifier", i.text)
        assertEquals("40%", i.chip)
        assertEquals(LiveItems.Part.Work.ordinal, i.part)
        assertEquals(22, i.progress, "40% of the work part (55)")
        assertEquals(5, i.since)
        assertTrue(i.stopAsks)
        // Without progress, the minutes left; without either, the stage.
        assertEquals("12m", LiveItems.task("m1", "devbox", t.copy(progress = null))!!.chip)
        assertEquals("Work", LiveItems.task("m1", "devbox", t.copy(progress = null, eta = null))!!.chip)
    }

    @Test
    fun reviewRoundOfTheMost() {
        val t = task("""{"id":"herdr-android/24","title":"Live Updates","state":"working","stage":"reviewing","reviewRound":2}""")
        val i = assertNotNull(LiveItems.task("m1", "devbox", t, reviews = 4))
        assertEquals("Rev 2/4", i.chip)
        assertEquals("Review 2 of 4", i.text)
        assertEquals(55 + 15 + 5, i.progress, "the second of four reviews")
        // The most not known (the app's own look), or a round past it (a fallback agent took over).
        assertEquals("Rev 2", LiveItems.task("m1", "devbox", t)!!.chip)
        assertEquals("Rev 5", LiveItems.task("m1", "devbox", t.copy(reviewRound = 5), reviews = 4)!!.chip)
        assertEquals("Review", LiveItems.task("m1", "devbox", t.copy(reviewRound = 0))!!.chip)
    }

    @Test
    fun otherStages() {
        fun at(stage: String) = LiveItems.task("m1", "devbox", task("""{"id":"r/1","title":"T","state":"working","stage":"$stage"}"""))
        assertEquals("Check", at("checking")!!.chip)
        assertEquals(55, at("checking")!!.progress)
        assertEquals("Merge", at("merging")!!.chip)
        assertEquals(90, at("merging")!!.progress)
        assertEquals("Work", at("starting")!!.chip)
        // Queued, waiting for you, done or another machine's: no Live Update.
        assertNull(at("queued"))
        assertNull(at("after"))
        assertNull(at("stopped"))
        assertNull(LiveItems.task("m1", "devbox", task("""{"id":"r/1","title":"T","state":"blocked","stage":"approval","kind":"permission"}""")))
        assertNull(LiveItems.task("m1", "devbox", task("""{"id":"r/1","title":"T","state":"done","stage":"done"}""")))
        assertNull(LiveItems.task("m1", "devbox", task("""{"id":"gpu:r/1","title":"T","state":"working","stage":"working"}""")))
    }

    @Test
    fun workingAgents() {
        val i = assertNotNull(LiveItems.agent("m1", "devbox", agent(), "api › agents", since = 9))
        assertEquals("live:agent:m1:w1:p1", i.tag)
        assertEquals("api", i.title)
        assertEquals("Working · Speed up /jobs · api › agents", i.text)
        assertEquals("", i.chip, "the time it has worked shows instead")
        assertNull(i.progress)
        assertEquals("api", i.agentName)
        assertEquals("api", i.workspace, "from the location, when not given")
        assertTrue(!i.stopAsks)
        assertNull(LiveItems.agent("m1", "devbox", agent("done"), ""))
        assertNull(LiveItems.agent("m1", "devbox", agent("blocked"), ""))
        // A board task's agent is called after its task; the Coordinator gets its name.
        val worker = assertNotNull(LiveItems.agent("m1", "devbox", agent(name = "sw-herdr-android-24", title = "AND-24 Live Updates"), ""))
        assertEquals("AND-24 Live Updates", worker.title)
        assertEquals("sw-herdr-android-24", worker.agentName)
        val coordinator = assertNotNull(LiveItems.agent("m1", "devbox", agent(name = "sw-coordinator", title = "Coordinator"), ""))
        assertEquals("Coordinator", coordinator.title)
        assertEquals("Working", coordinator.text)
        assertEquals("Coordinator", LiveItems.agent("m1", "devbox", agent(name = "sw-coordinator-2", title = null), "")!!.title)
        // Its title is its workspace's label too: said once.
        val reviewer = assertNotNull(
            LiveItems.agent(
                "m1", "devbox", agent(name = "sw-herdr-android-24-r", title = "AND-24 Live Updates"),
                "AND-24 Live Updates › Reviewer #1", workspace = "AND-24 Live Updates",
            ),
        )
        assertEquals("Working · AND-24 Live Updates › Reviewer #1", reviewer.text)
    }

    /** The board's names for a task's agents (herdr-projects: delegate/launch.rs, finish/review_once.rs, attempts/compare.rs). */
    @Test
    fun agentsOfATask() {
        fun task(id: String, key: String) = LiveItem(LiveItems.taskTag("m1", id), LiveItem.Kind.Task, "m1", "devbox", "$key T", "", "", taskId = id, taskName = key)
        fun agent(name: String, workspace: String = "", machine: String = "m1") =
            LiveItem(LiveItems.agentTag(machine, name), LiveItem.Kind.Agent, machine, "devbox", name, "", "", agentName = name, workspace = workspace)
        val t = task("herdr-android/24", "AND-24")
        for (name in listOf(
            "sw-herdr-android-24", // its worker
            "sw-herdr-android-24-3f9a", // the worker, its name taken
            "sw-herdr-android-24-r", // the reviewer
            "sw-herdr-android-24-3f9a-r",
            "sw-herdr-android-24-3f9a-r-0042", // cut to 27 and numbered, its name taken
            "sw-herdr-android-24-pick", // picking among attempts
        )) assertTrue(LiveItems.belongs(agent(name), t), name)
        // A long repo name: 16 characters of it, in lower case, other characters as -.
        val long = task("Very_Long.Repository-Name/7", "VLR-7")
        assertTrue(LiveItems.belongs(agent("sw-very-long-reposi-7-r"), long))
        assertTrue(LiveItems.belongs(agent("sw-very-long-reposi-7-ab12-r"), long))
        // A name that doesn't say which repo: the task's workspace does.
        assertTrue(LiveItems.belongs(agent("sw-task-24-r", workspace = "AND-24 Live Updates"), t))
        assertTrue(LiveItems.belongs(agent("claude", workspace = "AND-24"), t))
        // Another task, another machine, or a look-alike: not this one.
        for (other in listOf(agent("sw-herdr-android-2"), agent("sw-herdr-android-240"), agent("sw-herdr-android-240-r"), agent("sw-herdr-ios-24"),
            agent("sw-task-24-r"), agent("api", workspace = "AND-240 Other"), agent("sw-herdr-android-24", machine = "m2"))) {
            assertTrue(!LiveItems.belongs(other, t), other.agentName + " " + other.workspace + " " + other.machineId)
        }
        // A task without its short key: only names tie agents to it.
        assertTrue(!LiveItems.belongs(agent("x", workspace = "herdr-android/24"), task("herdr-android/24", "herdr-android/24")))
    }

    @Test
    fun machineTimeOnThePhone() {
        // Started 60 s before the push was sent: 60 s before it arrived, whatever the machine's clock says.
        assertEquals(1_000_000L - 60_000, LiveItems.phoneTime(since = 5_000, at = 5_060, now = 1_000_000))
        assertEquals(0, LiveItems.phoneTime(since = 0, at = 5_060, now = 1_000_000))
        assertEquals(1_000_000L, LiveItems.phoneTime(since = 5_070, at = 5_060, now = 1_000_000))
    }
}

class LiveTrackerTest {
    private fun item(
        tag: String, kind: LiveItem.Kind = LiveItem.Kind.Agent, machine: String = "m1", text: String = "Working", since: Long = 0,
        taskId: String = "", taskName: String = "", agentName: String = "", workspace: String = "",
    ) = LiveItem(
        tag = tag, kind = kind, machineId = machine, machineLabel = machine, title = tag, text = text, chip = "", since = since,
        taskId = taskId, taskName = taskName, agentName = agentName, workspace = workspace,
    )

    private val a1 = item(LiveItems.agentTag("m1", "p1"))
    private val a2 = item(LiveItems.agentTag("m1", "p2"))

    @Test
    fun eachListReplacesTheOneBefore() {
        val t = LiveTracker()
        assertEquals(listOf(a1, a2), t.update("m1", LiveItem.Kind.Agent, listOf(a1, a2)).post)
        // Nothing new: nothing to post, unless a push asks to post again.
        assertTrue(t.update("m1", LiveItem.Kind.Agent, listOf(a1, a2)).isEmpty)
        assertEquals(listOf(a1, a2), t.update("m1", LiveItem.Kind.Agent, listOf(a1, a2), refresh = true).post)
        val changed = a1.copy(text = "Working · tests")
        val c = t.update("m1", LiveItem.Kind.Agent, listOf(changed))
        assertEquals(listOf(changed), c.post)
        assertEquals(listOf(a2.tag), c.cancel)
        // Another machine's list leaves this one's alone.
        val b = item(LiveItems.agentTag("m2", "p1"), machine = "m2")
        assertEquals(LiveItem.Kind.Agent, t.update("m2", LiveItem.Kind.Agent, listOf(b)).post.single().kind)
        assertEquals(2, t.items().size)
        assertEquals(listOf(b.tag), t.drop("m2").cancel)
        assertEquals(listOf(changed.tag), t.clear().cancel)
    }

    @Test
    fun theTaskStandsForItsAgent() {
        val t = LiveTracker()
        val agent = item(LiveItems.agentTag("m1", "p9"), agentName = "sw-herdr-android-24")
        val task = item(LiveItems.taskTag("m1", "herdr-android/24"), kind = LiveItem.Kind.Task, taskId = "herdr-android/24", taskName = "AND-24")
        // Only the agent is known yet (the app on screen, no board read): it shows.
        assertEquals(listOf(agent), t.update("m1", LiveItem.Kind.Agent, listOf(agent)).post)
        // The task arrives: it takes the agent's place.
        val c = t.update("m1", LiveItem.Kind.Task, listOf(task))
        assertEquals(listOf(task), c.post)
        assertEquals(listOf(agent.tag), c.cancel)
        // The task ends; its agent still works: the agent shows again.
        val back = t.update("m1", LiveItem.Kind.Task, emptyList())
        assertEquals(listOf(agent), back.post)
        assertEquals(listOf(task.tag), back.cancel)
    }

    /** In review: the worker and the reviewer both work, and the task stands for both. */
    @Test
    fun theTaskStandsForItsReviewerToo() {
        val t = LiveTracker()
        val worker = item(LiveItems.agentTag("m1", "p1"), agentName = "sw-herdr-android-24", workspace = "AND-24 Live Updates")
        val reviewer = item(LiveItems.agentTag("m1", "p2"), agentName = "sw-herdr-android-24-r", workspace = "AND-24 Live Updates")
        val other = item(LiveItems.agentTag("m1", "p3"), agentName = "pi", workspace = "operations")
        val task = item(LiveItems.taskTag("m1", "herdr-android/24"), kind = LiveItem.Kind.Task, taskId = "herdr-android/24", taskName = "AND-24")
        t.update("m1", LiveItem.Kind.Task, listOf(task))
        assertEquals(listOf(other), t.update("m1", LiveItem.Kind.Agent, listOf(worker, reviewer, other)).post)
        assertEquals(listOf(task.tag, other.tag), t.items().map { it.tag })
    }

    @Test
    fun keepsTheFirstKnownStart() {
        val t = LiveTracker()
        t.update("m1", LiveItem.Kind.Agent, listOf(a1.copy(since = 100_000)))
        // A push a moment later rounds differently, and the app's own look doesn't know: the first start stays.
        assertTrue(t.update("m1", LiveItem.Kind.Agent, listOf(a1.copy(since = 100_900))).isEmpty)
        assertTrue(t.update("m1", LiveItem.Kind.Agent, listOf(a1)).isEmpty)
        assertEquals(100_000, t.item(a1.tag)?.since)
    }

    @Test
    fun takesDownWhatARestartLeftUp() {
        val t = LiveTracker()
        t.adopt(listOf(a1.tag, a2.tag, LiveItems.taskTag("m1", "r/1"), "agent:m1:p1"))
        // The agents' list arrives: p1 still works and is posted again, p2 doesn't and comes down. The task waits for its list.
        val c = t.update("m1", LiveItem.Kind.Agent, listOf(a1))
        assertEquals(listOf(a1), c.post)
        assertEquals(listOf(a2.tag), c.cancel)
        assertEquals(listOf(LiveItems.taskTag("m1", "r/1")), t.update("m1", LiveItem.Kind.Task, emptyList()).cancel)
    }
}
