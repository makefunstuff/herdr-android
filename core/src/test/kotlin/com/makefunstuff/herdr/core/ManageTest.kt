package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.api.ApiCaller
import com.makefunstuff.herdr.core.api.ApiConnection
import com.makefunstuff.herdr.core.api.HerdrApi
import com.makefunstuff.herdr.core.api.HerdrApiException
import com.makefunstuff.herdr.core.api.LayoutChange
import com.makefunstuff.herdr.core.api.PaneDestination
import com.makefunstuff.herdr.core.api.SessionSnapshot
import com.makefunstuff.herdr.core.api.moveIndex
import com.makefunstuff.herdr.core.host.Notice
import com.makefunstuff.herdr.core.host.Notices
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Records each call and answers with the canned `result` for its method. */
private class FakeCaller(private val results: Map<String, String> = emptyMap()) : ApiCaller {
    val calls = mutableListOf<Pair<String, JsonObject>>()

    override suspend fun call(method: String, params: JsonObject, timeoutMs: Long): JsonObject {
        calls += method to params
        val response = Json.parseToJsonElement("""{"id":"1","result":${results[method] ?: """{"type":"ok"}"""}}""").jsonObject
        return ApiConnection.unwrap(response)
    }

    fun last(): Pair<String, String> = calls.last().let { it.first to it.second.toString() }
}

class ManageApiTest {
    @Test
    fun workspaceCallsSendHerdrsParams() = runBlocking {
        val fake = FakeCaller(
            mapOf(
                "workspace.get" to """{"type":"workspace_info","workspace":{"workspace_id":"w1","number":1,"label":"alpha","focused":true,"pane_count":2,"tab_count":1,"active_tab_id":"w1:t1","agent_status":"working","tokens":{"branch":"main","pr":"12"}}}""",
            ),
        )
        val api = HerdrApi(fake)
        val ws = api.workspace("w1")
        assertEquals(mapOf("branch" to "main", "pr" to "12"), ws.tokens)
        assertEquals("workspace.get" to """{"workspace_id":"w1"}""", fake.last())
        api.renameWorkspace("w1", "api")
        assertEquals("workspace.rename" to """{"workspace_id":"w1","label":"api"}""", fake.last())
        api.closeWorkspace("w1")
        assertEquals("workspace.close" to """{"workspace_id":"w1"}""", fake.last())
        api.focusWorkspace("w2")
        assertEquals("workspace.focus" to """{"workspace_id":"w2"}""", fake.last())
        api.moveWorkspace("w2", 0)
        assertEquals("workspace.move" to """{"workspace_id":"w2","insert_index":0}""", fake.last())
        api.moveWorkspaces(listOf("w1", "w2"), "w3")
        assertEquals("workspace.move_block" to """{"workspace_ids":["w1","w2"],"before_workspace_id":"w3"}""", fake.last())
        api.moveWorkspaces(listOf("w1"), null)
        assertEquals("workspace.move_block" to """{"workspace_ids":["w1"]}""", fake.last())
    }

    @Test
    fun tabCalls() = runBlocking {
        val fake = FakeCaller()
        val api = HerdrApi(fake)
        api.renameTab("w1:t2", "logs")
        assertEquals("tab.rename" to """{"tab_id":"w1:t2","label":"logs"}""", fake.last())
        api.closeTab("w1:t2")
        assertEquals("tab.close" to """{"tab_id":"w1:t2"}""", fake.last())
        api.focusTab("w1:t2")
        assertEquals("tab.focus" to """{"tab_id":"w1:t2"}""", fake.last())
        api.moveTab("w1:t2", 3)
        assertEquals("tab.move" to """{"tab_id":"w1:t2","insert_index":3}""", fake.last())
    }

    @Test
    fun paneCallsAndTheirRefusals() = runBlocking {
        val fake = FakeCaller(
            mapOf(
                "pane.move" to """{"type":"pane_move","move_result":{"changed":false,"reason":"zoomed_tab","previous_pane_id":"w1:p1"}}""",
                "pane.swap" to """{"type":"pane_swap","swap":{"changed":true,"source_pane_id":"w1:p1","focused_pane_id":"w1:p1"}}""",
                "pane.zoom" to """{"type":"pane_zoom","zoom":{"changed":true,"zoom_changed":true,"zoomed":true,"pane_id":"w1:p1"}}""",
                "pane.focus_direction" to """{"type":"pane_focus_direction","focus":{"changed":false,"reason":"no_neighbor","source_pane_id":"w1:p1"}}""",
                "pane.process_info" to """{"type":"pane_process_info","process_info":{"pane_id":"w1:p1","shell_pid":10,"foreground_process_group_id":11,"foreground_processes":[{"pid":11,"name":"vim","argv":["vim","notes.md"],"cwd":"/tmp"},{"pid":12,"name":"sh"}]}}""",
            ),
        )
        val api = HerdrApi(fake)
        api.renamePane("w1:p1", "server")
        assertEquals("pane.rename" to """{"pane_id":"w1:p1","label":"server"}""", fake.last())
        api.renamePane("w1:p1", " ")
        assertEquals("pane.rename" to """{"pane_id":"w1:p1"}""", fake.last())

        val moved = api.movePane("w1:p1", PaneDestination.Tab("w1:t2", "down"))
        assertEquals("pane.move" to """{"pane_id":"w1:p1","destination":{"type":"tab","tab_id":"w1:t2","split":"down"},"focus":false}""", fake.last())
        assertFalse(moved.changed)
        assertEquals("That tab has a zoomed pane. Unzoom it first.", moved.refusal)
        api.movePane("w1:p1", PaneDestination.NewTab(label = "solo"))
        assertEquals("""{"pane_id":"w1:p1","destination":{"type":"new_tab","label":"solo"},"focus":false}""", fake.last().second)
        api.movePane("w1:p1", PaneDestination.NewWorkspace("side"))
        assertEquals("""{"pane_id":"w1:p1","destination":{"type":"new_workspace","label":"side"},"focus":false}""", fake.last().second)

        assertNull(api.swapPane("w1:p1", direction = "right").refusal)
        assertEquals("pane.swap" to """{"pane_id":"w1:p1","direction":"right"}""", fake.last())
        api.swapPane("w1:p1", targetPaneId = "w1:p3")
        assertEquals("""{"source_pane_id":"w1:p1","target_pane_id":"w1:p3"}""", fake.last().second)

        assertEquals(true, api.zoomPane("w1:p1").zoomed)
        assertEquals("pane.zoom" to """{"pane_id":"w1:p1","mode":"toggle"}""", fake.last())

        assertEquals("There's no pane in that direction.", api.focusDirection("w1:p1", "left").refusal)
        assertEquals("pane.focus_direction" to """{"pane_id":"w1:p1","direction":"left"}""", fake.last())

        api.focusPane("w1:p2")
        assertEquals("pane.focus" to """{"pane_id":"w1:p2"}""", fake.last())
        api.clearPane("w1:p2")
        assertEquals("pane.clear" to """{"pane_id":"w1:p2"}""", fake.last())

        val info = api.processInfo("w1:p1")
        assertEquals(10L, info.shellPid)
        assertEquals("vim notes.md", info.processes[0].command)
        assertEquals("sh", info.processes[1].command)
    }

    @Test
    fun zoomOnASinglePaneTabSaysThereIsNothingToZoom() = runBlocking {
        // herdr's `changed` is true here because focusing the pane changed something; `zoom_changed`
        // is what says the zoom itself did nothing.
        val fake = FakeCaller(
            mapOf(
                "pane.zoom" to """{"type":"pane_zoom","zoom":{"changed":true,"zoom_changed":false,"focus_changed":true,"reason":"single_pane","zoomed":false,"pane_id":"w1:p1"}}""",
            ),
        )
        val zoom = HerdrApi(fake).zoomPane("w1:p1", "on")
        assertEquals("pane.zoom" to """{"pane_id":"w1:p1","mode":"on"}""", fake.last())
        assertEquals(false, zoom.zoomed)
        assertEquals("The tab has only one pane, so there's nothing to zoom.", zoom.refusal)
    }

    @Test
    fun agentCalls() = runBlocking {
        val fake = FakeCaller(
            mapOf(
                "agent.get" to """{"type":"agent_info","agent":{"terminal_id":"t1","name":"rev","agent":"claude","agent_status":"working","workspace_id":"w1","tab_id":"w1:t1","pane_id":"w1:p2"}}""",
                "agent.explain" to """{"type":"agent_explain","explain":{"agent":"claude","state":"blocked","matched_rule":"bash_permission_prompt","visible_blocker":true,"manifest_version":"2026.10.11.1","evaluated_rules":[{"id":"bash_permission_prompt","state":"blocked","matched":true,"region":"whole_recent","evidence":{"region_preview":"Do you want to proceed?"}},{"id":"osc_title_idle","state":"idle","matched":false,"region":"osc_title"}]}}""",
            ),
        )
        val api = HerdrApi(fake)
        assertEquals("rev", api.agent("rev").name)
        assertEquals("agent.get" to """{"target":"rev"}""", fake.last())
        api.renameAgent("w1:p2", "reviewer")
        assertEquals("agent.rename" to """{"target":"w1:p2","name":"reviewer"}""", fake.last())
        api.renameAgent("w1:p2", null)
        assertEquals("agent.rename" to """{"target":"w1:p2","name":null}""", fake.last())
        api.focusAgent("rev")
        assertEquals("agent.focus" to """{"target":"rev"}""", fake.last())
        api.agentSendKeys("rev", listOf("esc", "ctrl+c"))
        assertEquals("agent.send_keys" to """{"target":"rev","keys":["esc","ctrl+c"]}""", fake.last())

        val why = api.explainAgent("rev")
        assertEquals("blocked", why.state)
        assertEquals(
            "herdr reads claude as blocked. Its screen matched the rule \"bash permission prompt\". The screen shows a question.",
            why.summary,
        )
        assertEquals(listOf(true, false), why.rules.map { it.matched })
        assertEquals("Do you want to proceed?", why.rules[0].preview)
    }

    @Test
    fun explanationWithoutAMatchedRule() {
        val why = com.makefunstuff.herdr.core.api.AgentExplanation(
            Json.parseToJsonElement("""{"agent":"claude","state":"idle","matched_rule":null,"fallback_reason":"default_known_agent_idle_fallback"}""").jsonObject,
        )
        assertEquals("herdr reads claude as idle. No screen rule matched, so herdr fell back (default known agent idle fallback).", why.summary)
    }

    @Test
    fun notificationShowReportsWhyItWasNotShown() = runBlocking {
        val fake = FakeCaller(mapOf("notification.show" to """{"type":"notification_show","shown":false,"reason":"no_foreground_client"}"""))
        val result = HerdrApi(fake).showNotification("Build", "done", sound = "done")
        assertEquals("notification.show" to """{"title":"Build","body":"done","sound":"done"}""", fake.last())
        assertFalse(result.shown)
        assertEquals("No herdr window is open on the machine to show it.", result.message)
    }

    @Test
    fun errorsReadAsPlainSentences() {
        val e = assertFailsWith<HerdrApiException> {
            ApiConnection.unwrap(Json.parseToJsonElement("""{"id":"1","error":{"code":"agent_not_found","message":"agent target w1:p1 not found"}}""").jsonObject)
        }
        assertEquals("agent_not_found", e.code)
        assertEquals("Agent target w1:p1 not found.", e.message)
        assertEquals("Workspace move failed.", HerdrApiException.plain("workspace_move_failed", ""))
        assertEquals("Nope!", HerdrApiException.plain("x", "nope!"))
    }

    @Test
    fun lifecycleEventsCoverManagementChanges() {
        val events = HerdrApi.LIFECYCLE_EVENTS
        listOf("workspace.moved", "workspace.focused", "workspace.metadata_updated", "tab.focused", "pane.focused", "tab.moved")
            .forEach { assertTrue(it in events, it) }
    }
}

class MoveIndexTest {
    private val order = listOf("a", "b", "c", "d")

    @Test
    fun earlierInsertsAtTheTarget() {
        assertEquals(0, moveIndex(order, "c", 0))
        assertEquals(1, moveIndex(order, "d", 1))
    }

    @Test
    fun laterInsertsPastTheTarget() {
        // herdr inserts before the item at insert_index, counted before the move.
        assertEquals(3, moveIndex(order, "a", 2))
        assertEquals(4, moveIndex(order, "b", 3))
        assertEquals(4, moveIndex(order, "b", 99))
    }

    @Test
    fun noMoveIsNull() {
        assertNull(moveIndex(order, "b", 1))
        assertNull(moveIndex(order, "z", 0))
    }

    @Test
    fun unknownRefusalReasonStillReads() {
        assertEquals("herdr didn't change anything (new thing).", LayoutChange(false, "new_thing").refusal)
        assertNull(LayoutChange(true, null).refusal)
    }
}

class NoticesTest {
    private fun agent(pane: String, status: String, name: String? = null, seq: Long = 1) =
        AgentInfo(terminalId = "t-$pane", name = name, agent = "claude", agentStatusRaw = status, workspaceId = "w1", tabId = "w1:t1", paneId = pane, stateChangeSeq = seq)

    private fun snap(vararg agents: AgentInfo) = SessionSnapshot(agents = agents.toList())

    @Test
    fun finishingAndBlockingRaiseNotices() {
        val old = snap(agent("w1:p1", "working", "rev"), agent("w1:p2", "working"), agent("w1:p3", "idle"))
        val new = snap(agent("w1:p1", "done", "rev", 2), agent("w1:p2", "blocked", seq = 2), agent("w1:p3", "working", seq = 2))
        val notices = Notices.between(old, new, now = 1000)
        assertEquals(listOf(Notice.Kind.Done, Notice.Kind.NeedsYou), notices.map { it.kind })
        assertEquals("rev finished", notices[0].title)
        assertEquals("claude needs you", notices[1].title)
        assertEquals("w1 › w1:t1", notices[1].body)
    }

    @Test
    fun noNoticesForTheFirstSnapshotNewAgentsOrNoChange() {
        val s = snap(agent("w1:p1", "blocked"))
        assertTrue(Notices.between(null, s, 1).isEmpty())
        assertTrue(Notices.between(snap(), s, 1).isEmpty())
        assertTrue(Notices.between(s, s, 1).isEmpty())
    }

    @Test
    fun mergeKeepsNewestFirstAndCaps() {
        val one = Notice("a", 1, Notice.Kind.Done, "p", "w", "t", "a", "")
        val many = (2..60).map { one.copy(id = "n$it", at = it.toLong()) }
        val merged = Notices.merge(listOf(one), many)
        assertEquals(Notices.LIMIT, merged.size)
        assertEquals("n60", merged.first().id)
    }
}
