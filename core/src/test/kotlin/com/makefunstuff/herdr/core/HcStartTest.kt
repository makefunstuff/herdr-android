package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.api.ApiCaller
import com.makefunstuff.herdr.core.hc.HcClient
import com.makefunstuff.herdr.core.hc.HcException
import com.makefunstuff.herdr.core.hc.HcList
import com.makefunstuff.herdr.core.hc.HcMapping
import com.makefunstuff.herdr.core.hc.HcStart
import com.makefunstuff.herdr.core.hc.HcTerminal
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Starting and closing herdr-core terminals from the phone (issue #99): `hc run` into a space, `hc kill`. */
class HcStartTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun res(name: String) = javaClass.getResource("/hc/$name")!!.readText()
    private val list: HcList get() = json.decodeFromJsonElement(json.parseToJsonElement(res("list.json")).jsonObject["result"]!!.jsonObject)
    private val snapshot get() = HcMapping.snapshot(list.terminals)

    private class Core(val answers: (String, JsonObject) -> JsonObject) : ApiCaller {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        override suspend fun call(method: String, params: JsonObject, timeoutMs: Long): JsonObject {
            calls += method to params
            return answers(method, params)
        }
    }

    private fun terminal(address: String, status: String? = null): JsonObject {
        val t = HcTerminal(address = address)
        val obj = json.encodeToJsonElement(t).jsonObject
        return if (status == null) obj else JsonObject(obj + ("status" to JsonObject(mapOf("state" to JsonPrimitive(status)))))
    }

    @Test
    fun aSpacesTerminalIsLabelledIntoANewTabOfIt() {
        val p = HcStart.place("hc:sp1", "herdr-android", "Terminal", tabId = "tbnew")!!
        assertNull(p.session)
        assertNull(p.machine)
        assertEquals(mapOf("herdr.space" to "sp1", "herdr.tab" to "tbnew", "where" to "herdr-android / Terminal"), p.labels)
        // The app maps it back into the same space, as a tab of its own.
        val t = HcTerminal(address = "t9", labels = p.labels, cwd = "/home/dev/src/demo")
        val snap = HcMapping.snapshot(list.terminals + t)
        val pane = snap.panes.single { it.paneId == "hc:t9" }
        assertEquals("hc:sp1", pane.workspaceId)
        assertEquals("Terminal", snap.tabs.single { it.tabId == pane.tabId }.label)
    }

    @Test
    fun placesInOtherSessionsMachinesAndOtherTerminals() {
        assertNull(HcStart.place("w1", "classic", "Terminal"))
        val other = HcStart.place("hc:@shepherdr:sp7", "app", "claude", tabId = "tb1")!!
        assertEquals("shepherdr", other.session)
        assertEquals("sp7", other.labels["herdr.space"])
        val remote = HcStart.place("hc:rohan/sp2", "scratch", "Terminal", tabId = "tb1")!!
        assertEquals("rohan", remote.machine)
        assertEquals("sp2", remote.labels["herdr.space"])
        val named = HcStart.place("hc:where:a/b", "a/b", "Terminal")!!
        assertNull(named.machine)
        // Its own tab too: a second terminal there doesn't land in the first one's.
        assertEquals("a/b / Terminal", named.labels["where"])
        assertTrue(named.labels["herdr.tab"]!!.startsWith("tb"))
        assertTrue(HcStart.place("hc:where:a/b", "a/b", "Terminal")!!.labels["herdr.tab"] != named.labels["herdr.tab"])
        assertEquals(HcStart.Place(null, null, emptyMap()), HcStart.place(HcMapping.OTHER, "Other terminals", "Terminal"))
        assertEquals(HcStart.Place(null, "rohan", emptyMap()), HcStart.place("${HcMapping.OTHER}@rohan", "Other terminals on rohan", "Terminal"))
    }

    @Test
    fun anAgentStartsInTheSpaceThatSitsInItsFolder() {
        assertEquals("hc:sp1", HcStart.spaceIn(snapshot, "/home/dev/src/demo/"))
        assertNull(HcStart.spaceIn(snapshot, "/elsewhere"))
        // Only the desktop app's own (default) session: not Shepherdr's session, not Other terminals.
        val shepherdr = HcMapping.snapshot(listOf(HcTerminal(address = "t5", cwd = "/srv/app", labels = mapOf("herdr.space" to "sp9", "where" to "app / 1"))), session = "shepherdr")
        val other = HcMapping.snapshot(listOf(HcTerminal(address = "t6", cwd = "/srv/loose")))
        assertNull(HcStart.spaceIn(shepherdr, "/srv/app"))
        assertNull(HcStart.spaceIn(other, "/srv/loose"))
    }

    @Test
    fun namesAndLines() {
        assertEquals("add-last-answered", HcStart.hcName("Add last answered"))
        assertEquals(32, HcStart.hcName("a".repeat(40))!!.length)
        assertNull(HcStart.hcName("!!"))
        assertEquals("exec \"\${SHELL:-/bin/sh}\" -lc 'exec '\\''claude'\\'''", HcStart.programLine("claude"))
        assertTrue(HcStart.newTabId().matches(Regex("tb[0-9a-f]{32}")))
    }

    @Test
    fun aShellRunsWithItsLabelsAndNoParent() = runBlocking {
        val core = Core { method, _ -> if (method == "terminal.run") terminal("t9") else JsonObject(emptyMap()) }
        val place = HcStart.place("hc:@shepherdr:sp1", "herdr-android", "Terminal", tabId = "tbnew")!!
        val pane = HcStart.shell(HcClient(core), place, "/home/dev/src/demo")
        assertEquals("hc:@shepherdr:t9", pane)
        val (method, params) = core.calls.single()
        assertEquals("terminal.run", method)
        assertEquals("/home/dev/src/demo", params["cwd"]!!.jsonPrimitive.content)
        assertEquals("true", params["no_parent"]!!.jsonPrimitive.content)
        assertNull(params["line"], "no line: the machine's own shell")
        assertEquals("xterm-256color", params["env"]!!.jsonObject["TERM"]!!.jsonPrimitive.content)
        assertEquals("sp1", params["labels"]!!.jsonObject["herdr.space"]!!.jsonPrimitive.content)
    }

    @Test
    fun anAgentGetsWhatWasSaidOnceItReports() = runBlocking {
        var gets = 0
        val core = Core { method, _ ->
            when (method) {
                "terminal.run" -> terminal("t9")
                "terminal.get" -> terminal("t9", status = when (++gets) { 1 -> null; 2 -> "working"; else -> "idle" })
                "terminal.send" -> JsonObject(mapOf("sent" to JsonPrimitive(true)))
                else -> JsonObject(emptyMap())
            }
        }
        val started = HcStart.agent(HcClient(core), HcStart.Place(null, null, emptyMap()), "/src", "claude", "Fix the login", "fix the login")
        assertEquals(HcStart.Started("hc:t9", null), started)
        assertEquals(listOf("terminal.run", "terminal.get", "terminal.get", "terminal.get", "terminal.send"), core.calls.map { it.first })
        val run = core.calls.first().second
        assertEquals("fix-the-login", run["name"]!!.jsonPrimitive.content)
        assertEquals(HcStart.programLine("claude"), run["line"]!!.jsonPrimitive.content)
        assertEquals("fix the login", core.calls.last().second["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun aTakenNameStartsItWithoutOneAndAQuestionGetsNoEnter() = runBlocking {
        val core = Core { method, params ->
            when {
                method == "terminal.run" && params["name"] != null -> throw HcException("name_taken", "name taken")
                method == "terminal.run" -> terminal("t9")
                method == "terminal.get" -> terminal("t9", status = "blocked")
                else -> JsonObject(emptyMap())
            }
        }
        val started = HcStart.agent(HcClient(core), HcStart.Place(null, null, emptyMap()), "/src", "codex", "fix", "fix it")
        assertEquals(HcStart.Started("hc:t9", "it asks something first"), started)
        assertEquals(2, core.calls.count { it.first == "terminal.run" })
        assertTrue(core.calls.none { it.first == "terminal.send" }, "an Enter could answer its question")
    }

    @Test
    fun anAgentThatNeverSaysItIsReadyIsTypedNothing() = runBlocking {
        val core = Core { method, _ -> if (method == "terminal.run" || method == "terminal.get") terminal("t9") else JsonObject(emptyMap()) }
        val started = HcStart.agent(HcClient(core), HcStart.Place(null, null, emptyMap()), "/src", "pi", "fix", "fix it", readyMs = 1_200)
        assertEquals(HcStart.Started("hc:t9", "it didn't say it was ready"), started)
        assertTrue(core.calls.none { it.first == "terminal.send" })
    }

    @Test
    fun anErrorIsSaidAsOne() = runBlocking {
        val core = Core { method, _ -> if (method == "terminal.run" || method == "terminal.get") terminal("t9", status = if (method == "terminal.get") "error" else null) else JsonObject(emptyMap()) }
        assertEquals("it reports an error", HcStart.deliver(HcClient(core), "hc:t9", "fix it"))
        assertTrue(core.calls.none { it.first == "terminal.send" })
    }

    @Test
    fun aRefusedSendIsSaid() = runBlocking {
        val core = Core { method, _ ->
            when (method) {
                "terminal.run" -> terminal("t9")
                "terminal.get" -> terminal("t9", status = "idle")
                "terminal.send" -> throw HcException("blocked", "it waits for an answer")
                else -> JsonObject(emptyMap())
            }
        }
        assertEquals("It waits for an answer.", HcStart.agent(HcClient(core), HcStart.Place(null, null, emptyMap()), "/src", "claude", "fix", "fix it").unsent)
    }

    @Test
    fun closeKillsTheTerminal() = runBlocking {
        val core = Core { _, _ -> terminal("t9") }
        HcClient(core).kill("t9")
        assertEquals("terminal.kill" to JsonObject(mapOf("terminal" to JsonPrimitive("t9"))), core.calls.single())
    }
}
