package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.hc.HcClient
import com.makefunstuff.herdr.core.hc.HcConnection
import com.makefunstuff.herdr.core.hc.HcDetection
import com.makefunstuff.herdr.core.hc.HcEvent
import com.makefunstuff.herdr.core.hc.HcEvents
import com.makefunstuff.herdr.core.hc.HcException
import com.makefunstuff.herdr.core.hc.HcFrames
import com.makefunstuff.herdr.core.hc.HcList
import com.makefunstuff.herdr.core.hc.HcMapping
import com.makefunstuff.herdr.core.hc.HcSubscribed
import com.makefunstuff.herdr.core.hc.HcTerminal
import com.makefunstuff.herdr.core.hc.HcTerminalStream
import com.makefunstuff.herdr.core.hc.HcView
import com.makefunstuff.herdr.core.host.Notice
import com.makefunstuff.herdr.core.host.Notices
import com.makefunstuff.herdr.core.ssh.Duplex
import com.makefunstuff.herdr.core.terminal.TerminalStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * herdr-core (`hc` 0.1.14) as the app reads it, from what a real `hc connect` said on a dev
 * machine (src/test/resources/hc, paths and names scrubbed): its schema, the requests the client
 * sends, framed mode, events, attach, detection and the mapping onto the app's own models.
 */
class HerdrCoreTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun res(name: String) = javaClass.getResource("/hc/$name")!!.readText()
    private fun bytes(name: String) = javaClass.getResource("/hc/$name")!!.readBytes()
    private fun result(name: String) = json.parseToJsonElement(res(name)).jsonObject["result"]!!.jsonObject
    private val list: HcList get() = json.decodeFromJsonElement(result("list.json"))

    // --- the schema the requests are built against ---

    private val schema by lazy { json.parseToJsonElement(res("schema.json")).jsonObject }

    private fun params(method: String): Pair<Set<String>, Set<String>> {
        val m = schema["methods"]!!.jsonArray.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == method }
        val ps = m["params"]!!.jsonArray.map { it.jsonObject }
        return ps.map { it["name"]!!.jsonPrimitive.content }.toSet() to
            ps.filter { it["required"]?.jsonPrimitive?.content == "true" }.map { it["name"]!!.jsonPrimitive.content }.toSet()
    }

    @Test
    fun everyRequestFitsWhatHcSchemaSays() {
        assertEquals("0.1.14", schema["info"]!!.jsonObject["version"]!!.jsonPrimitive.content)
        val bodies = mapOf(
            "terminal.list" to HcClient.Requests.list(all = true),
            "terminal.get" to HcClient.Requests.terminal("t4"),
            "terminal.interrupt" to HcClient.Requests.terminal("t4"),
            "terminal.read" to HcClient.Requests.read("t4", 200),
            "terminal.send" to HcClient.Requests.send("t4", "hi", force = true),
            "terminal.keys" to HcClient.Requests.keys("t4", listOf("y", "enter")),
            "terminal.label" to HcClient.Requests.label("t4", mapOf("where" to "a / b")),
            "terminal.attach" to HcClient.Requests.attach("t4", 80, 24, history = 1000, observe = true),
        )
        for ((method, body) in bodies) {
            val (known, required) = params(method)
            assertTrue(body.keys.all { it in known }, "$method: ${body.keys - known} aren't in hc's schema")
            assertTrue(required.all { it in body.keys }, "$method: ${required - body.keys} are required")
        }
        // events.subscribe and server.status take nothing; hello takes what HcConnection.open sends.
        assertTrue(params("hello").first.containsAll(listOf("generation", "framed")))
        assertTrue(params("events.subscribe").second.isEmpty())
    }

    @Test
    fun requestBodiesAreExactlyTheseJson() {
        assertEquals("""{"terminal":"t4","text":"Run the tests","force":true}""", HcClient.Requests.send("t4", "Run the tests", true).toString())
        assertEquals("""{"terminal":"t4","text":"hi"}""", HcClient.Requests.send("t4", "hi", false).toString())
        assertEquals("""{"terminal":"t2","keys":["y","enter"]}""", HcClient.Requests.keys("t2", listOf("y", "enter")).toString())
        assertEquals("""{"terminal":"t3","cols":60,"rows":10,"history":20,"observe":true}""", HcClient.Requests.attach("t3", 60, 10, 20, true).toString())
        assertEquals("""{"terminal":"t3","cols":60,"rows":10}""", HcClient.Requests.attach("t3", 60, 10, 0, false).toString())
        assertEquals("""{}""", HcClient.Requests.list(false).toString())
        assertEquals("""{"terminal":"rohan/t7","labels":{"where":""}}""", HcClient.Requests.label("rohan/t7", mapOf("where" to "")).toString())
        assertEquals(
            """{"jsonrpc":"2.0","id":3,"method":"terminal.read","params":{"terminal":"t4","lines":80}}""",
            HcConnection.request(3, "terminal.read", HcClient.Requests.read("t4", 80)),
        )
    }

    // --- decoding what hc answered ---

    @Test
    fun decodesTheTerminalsHcListed() {
        val l = list
        assertEquals(listOf("t1", "t2", "t3", "t4"), l.terminals.map { it.address })
        val blocked = l.terminals.first { it.address == "t2" }
        assertEquals("blocked", blocked.status?.state)
        assertEquals("permission", blocked.status?.kind)
        assertEquals("Run ./gradlew test?", blocked.status?.msg)
        assertEquals("codex", blocked.status?.app)
        assertEquals("herdr-android / build", blocked.labels["where"])
        val claude = l.terminals.first { it.address == "t4" }
        assertEquals("claude-code", claude.status?.app)
        assertEquals("Greeting reply", claude.title)
        assertEquals(1, claude.turn)
        assertEquals("claude", claude.foreground?.name)
        val shell = l.terminals.first { it.address == "t3" }
        assertNull(shell.status)
        assertFalse(shell.reported)
        assertEquals("sleep", shell.foreground?.name)
    }

    @Test
    fun anHcErrorReadsAsItsSentenceWithItsKind() {
        val e = assertFailsWith<HcException> { HcConnection.unwrap(json.parseToJsonElement(res("error-not-found.json")).jsonObject) }
        assertEquals("not_found", e.kind)
        assertEquals("No terminal \"nope\".", e.message)
        assertEquals("see `hc ls --all`", e.hint)
    }

    // --- the mapping onto the app's models ---

    @Test
    fun spacesTabsAndAgentsMapOntoTheAppsSnapshot() {
        val snap = HcMapping.snapshot(list.terminals, "0.1.14")
        assertEquals(listOf("herdr-android", "scratch", "Other terminals"), snap.workspaces.map { it.label })
        assertEquals(listOf("hc:sp1", "hc:sp2", "hc:other"), snap.workspaces.map { it.workspaceId })
        assertEquals(listOf("review", "build", "claude", "Terminals"), snap.tabs.map { it.label })
        assertEquals(listOf("hc:sp1/tb1", "hc:sp1/tb2", "hc:sp2/tb3", "hc:other/tab"), snap.tabs.map { it.tabId })
        assertEquals(2, snap.workspace("hc:sp1")!!.tabCount)
        // A space shows what most needs the human in it, as classic herdr's workspaces do.
        assertEquals(AgentStatus.Blocked, snap.workspace("hc:sp1")!!.agentStatus)
        assertEquals("herdr-android › build", snap.location("hc:sp1", "hc:sp1/tb2"))

        assertEquals(listOf("hc:t1", "hc:t2", "hc:t3", "hc:t4"), snap.panes.map { it.paneId })
        assertEquals("t3", HcMapping.address(snap.panes[2].paneId))
        assertEquals("ha5-shell", snap.panes[2].displayName)

        // The plain shell is a terminal, not an agent.
        val agents = snap.agents.associateBy { it.terminalId }
        assertEquals(setOf("t1", "t2", "t4"), agents.keys)
        assertEquals("claude", agents["t1"]!!.agent)
        assertEquals(AgentStatus.Working, agents["t1"]!!.agentStatus)
        assertEquals("Reviewing the diff", agents["t1"]!!.subtitle)
        assertEquals("codex", agents["t2"]!!.agent)
        assertEquals(AgentStatus.Blocked, agents["t2"]!!.agentStatus)
        assertEquals("Run ./gradlew test?", agents["t2"]!!.title)
        assertEquals(AgentStatus.Done, agents["t4"]!!.agentStatus)
        assertEquals("Greeting reply", agents["t4"]!!.terminalTitle)
        assertEquals(1L, agents["t4"]!!.completionSeq)
        assertEquals(list.terminals.first { it.address == "t4" }.statusMs, agents["t4"]!!.stateChangeSeq)
        assertEquals("/home/dev/src/demo", agents["t4"]!!.cwd)
    }

    @Test
    fun helpersSitWithTheirParentAndErrorsNeedTheHuman() {
        val parent = HcTerminal("t1", labels = mapOf("herdr.space" to "sp1", "herdr.tab" to "tb1", "where" to "app / main"), createdMs = 1)
        val helper = HcTerminal("t9", parent = "t1", createdMs = 2, reported = true, status = com.makefunstuff.herdr.core.hc.HcStatus("error", app = "codex"))
        val remote = HcTerminal("rohan/t3", createdMs = 3, foreground = com.makefunstuff.herdr.core.hc.HcForeground(name = "claude"))
        val snap = HcMapping.snapshot(listOf(parent, helper, remote))
        assertEquals("hc:sp1/tb1", snap.pane("hc:t9")!!.tabId)
        val err = snap.agents.first { it.terminalId == "t9" }
        assertEquals(AgentStatus.Blocked, err.agentStatus)
        assertEquals("error", err.statusLabel)
        // A known agent that hasn't reported yet is an agent already; another machine's sits apart.
        val r = snap.agents.first { it.terminalId == "rohan/t3" }
        assertEquals("claude", r.agent)
        assertEquals(AgentStatus.Unknown, r.agentStatus)
        assertEquals("Other terminals on rohan", snap.workspace(r.workspaceId)!!.label)
    }

    // --- events, as hc sent them ---

    private fun events(name: String): List<HcEvent> = res(name).lines().mapNotNull { HcEvents.parse(it) }

    @Test
    fun statusEventsFollowAClaudeTurnAndRaiseNotices() {
        val start = HcView.of(json.decodeFromJsonElement<HcSubscribed>(result("subscribe-status.json")))
        assertEquals(16, start.seq)
        val evs = events("events-status.jsonl")
        assertEquals(listOf("labels", "status", "status", "status", "turn", "status", "status", "turn", null), evs.map { it.type })
        assertEquals("heartbeat", evs.last().method)

        var view = start
        var snap = HcMapping.snapshot(view.terminals.values)
        val notices = mutableListOf<Notice>()
        var now = 1_000L
        for (e in evs) {
            view = view.apply(e, now = now++)
            val next = HcMapping.snapshot(view.terminals.values)
            notices += Notices.between(snap, next, now)
            snap = next
        }
        assertEquals(24, view.seq)
        assertEquals("misc / shell", view.terminals["t3"]!!.labels["where"])
        assertEquals("misc", snap.workspace(snap.pane("hc:t3")!!.workspaceId)!!.label)
        val t4 = view.terminals["t4"]!!
        assertEquals("idle", t4.status?.state)
        assertEquals(3, t4.turn)
        // working → done raised "finished" once, as classic herdr's agents do.
        assertEquals(listOf(Notice.Kind.Done), notices.map { it.kind })
        assertEquals("hc:t4", notices.single().paneId)
        assertEquals("ha5-claude finished", notices.single().title)

        // Replayed events at or below the seq are already in: nothing changes.
        assertEquals(view, view.apply(evs[1]))
    }

    @Test
    fun lifecycleEventsAddFinishAndRemoveTerminals() {
        var view = HcView.of(json.decodeFromJsonElement<HcSubscribed>(result("subscribe-lifecycle.json")))
        val evs = events("events-lifecycle.jsonl")
        assertEquals(listOf("created", "title", "foreground", "foreground", "finished", "removed", "finished"), evs.map { it.type })
        view = view.apply(evs[0])
        assertEquals("ha5-build2", view.terminals["t7"]!!.name)
        assertEquals("hc:sp1/tb2", HcMapping.snapshot(view.terminals.values).pane("hc:t7")!!.tabId)
        view = view.apply(evs[1]).apply(evs[2]).apply(evs[3])
        assertEquals("builder", view.terminals["t7"]!!.title)
        assertEquals("sleep", view.terminals["t6"]!!.foreground?.name)
        view = view.apply(evs[4])
        assertEquals("finished", view.terminals["t7"]!!.state)
        assertEquals(3, view.terminals["t7"]!!.exitCode)
        assertNull(view.terminals["t7"]!!.foreground)
        assertFalse(HcMapping.snapshot(view.terminals.values).panes.any { it.terminalId == "t7" })
        view = view.apply(evs[5])
        assertNull(view.terminals["t7"])
        view = view.apply(evs[6])
        assertEquals("killed", view.terminals["t6"]!!.reason)
        assertFalse(HcMapping.snapshot(view.terminals.values).panes.any { it.terminalId == "t6" })
    }

    @Test
    fun aMissedSnapshotReplacesEverything() {
        val view = HcView.of(json.decodeFromJsonElement<HcSubscribed>(result("subscribe-status.json")))
        val missed = HcEvent("missed", seq = 57, terminals = listOf(HcTerminal("t9", createdMs = 1)))
        val after = view.apply(missed)
        assertEquals(57, after.seq)
        assertEquals(setOf("t9"), after.terminals.keys)
    }

    // --- the wire: framed mode over a pipe, as `hc connect` speaks it ---

    /** A core on the other end of two pipes that answers from the fixtures. */
    private inner class FakeCore {
        val toCore = PipedOutputStream()
        private val coreIn = PipedInputStream(toCore, 1 shl 20)
        private val coreOut = PipedOutputStream()
        val fromCore = PipedInputStream(coreOut, 1 shl 20)
        val helloLine = java.util.concurrent.CompletableFuture<String>()
        val requests: MutableList<JsonObject> = Collections.synchronizedList(mutableListOf())
        val credits: MutableList<Pair<Int, Int>> = Collections.synchronizedList(mutableListOf())
        val frames: MutableList<HcFrames.Frame> = Collections.synchronizedList(mutableListOf())

        val duplex = object : Duplex {
            override val input: InputStream = fromCore
            override val output: OutputStream = toCore
            override fun close() {
                runCatching { toCore.close() }
                runCatching { fromCore.close() }
            }
        }

        private fun send(channel: Int, kind: Int, payload: ByteArray) = synchronized(this) {
            coreOut.write(HcFrames.encode(channel, kind, payload))
            coreOut.flush()
        }

        private fun reply(id: Long, fixture: String) {
            val r = json.parseToJsonElement(res(fixture)).jsonObject
            send(0, HcFrames.DATA, JsonObject(r + ("id" to JsonPrimitive(id))).toString().toByteArray())
        }

        val thread = Thread {
            runCatching {
                val line = ByteArrayOutputStream()
                while (true) {
                    val b = coreIn.read()
                    if (b == '\n'.code) break
                    line.write(b)
                }
                helloLine.complete(line.toString(Charsets.UTF_8))
                coreOut.write(res("hello.json").toByteArray())
                coreOut.flush()
                while (true) {
                    val f = HcFrames.read(coreIn) ?: break
                    frames += f
                    if (f.kind == HcFrames.CREDIT) credits += f.channel to ByteBuffer.wrap(f.payload).order(ByteOrder.LITTLE_ENDIAN).int
                    if (f.channel != 0 || f.kind != HcFrames.DATA) {
                        if (f.kind == HcFrames.CLOSE && f.channel == 2) send(2, HcFrames.CLOSE, ByteArray(0))
                        continue
                    }
                    val req = json.parseToJsonElement(f.payload.toString(Charsets.UTF_8)).jsonObject
                    requests += req
                    val id = req["id"]!!.jsonPrimitive.content.toLong()
                    when (req["method"]!!.jsonPrimitive.content) {
                        "terminal.list" -> reply(id, "list.json")
                        "terminal.get" -> reply(id, "error-not-found.json")
                        "terminal.send" -> reply(id, "send.json")
                        "events.subscribe" -> {
                            reply(id, "subscribe-status.json")
                            // In uneven pieces: a line may span frames.
                            res("events-status.jsonl").toByteArray().toList().chunked(97).forEach { send(1, HcFrames.DATA, it.toByteArray()) }
                        }
                        "terminal.attach" -> {
                            // The first paint before the reply: the client must keep it for the stream.
                            send(2, HcFrames.DATA, bytes("attach.bin"))
                            reply(id, "attach.json")
                        }
                    }
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    @Test
    fun speaksHelloThenFramedJsonRpcEventsAndAttachOverOnePipe() = runBlocking {
        val core = FakeCore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val conn = HcConnection.open(core.duplex, scope)
            assertEquals("""{"jsonrpc":"2.0","id":0,"method":"hello","params":{"generation":1,"framed":true}}""", core.helloLine.get())
            assertEquals("0.1.14", conn.hello.version)
            assertEquals("devbox", conn.hello.core.name)
            assertTrue("attach" in conn.hello.features)
            val client = HcClient(conn)

            // Calls side by side on channel 0, each answered by its id.
            val l = client.list()
            assertEquals(4, l.terminals.size)
            val e = assertFailsWith<HcException> { client.get("nope") }
            assertEquals("not_found", e.kind)
            val sent = client.send("t4", "Run the shell command `date`")
            assertEquals(true, sent.started)
            assertEquals(
                """{"terminal":"t4","text":"Run the shell command `date`"}""",
                core.requests.first { it["method"]!!.jsonPrimitive.content == "terminal.send" }["params"].toString(),
            )
            assertTrue(core.requests.all { it["jsonrpc"]!!.jsonPrimitive.content == "2.0" })

            val sub = client.subscribe()
            assertEquals(16, sub.snapshot.seq)
            val got = withTimeout(10_000) { sub.events.take(9).toList() }
            assertEquals(listOf(17L, 18, 19, 20, 21, 22, 23, 24, 24), got.map { it.seq })
            // Every piece read was credited back on its channel.
            assertEquals(res("events-status.jsonl").toByteArray().size, core.credits.filter { it.first == 1 }.sumOf { it.second })

            val stream = HcTerminalStream(client, "t3", 60, 10, scope, history = 20)
            stream.open()
            withTimeout(10_000) { stream.revision.first { it > 0 } }
            assertEquals(TerminalStream.Status.Live, stream.status.value)
            assertTrue(synchronized(stream.emulator.lock) { stream.emulator.screenText() }.startsWith("plain shell"))
            assertEquals(
                """{"terminal":"t3","cols":60,"rows":10,"history":20}""",
                core.requests.first { it["method"]!!.jsonPrimitive.content == "terminal.attach" }["params"].toString(),
            )
            stream.send("ls\r")
            stream.resize(80, 24)
            withTimeout(10_000) { while (core.frames.none { it.channel == 2 && it.kind == HcFrames.RESIZE }) kotlinx.coroutines.delay(10) }
            val keys = core.frames.first { it.channel == 2 && it.kind == HcFrames.DATA }
            assertEquals("ls\r", keys.payload.toString(Charsets.UTF_8))
            val resize = core.frames.first { it.channel == 2 && it.kind == HcFrames.RESIZE }
            assertEquals(listOf<Byte>(80, 0, 24, 0), resize.payload.toList())
            stream.close()
            withTimeout(10_000) { while (core.frames.none { it.channel == 2 && it.kind == HcFrames.CLOSE }) kotlinx.coroutines.delay(10) }

            conn.close()
            assertNull(withTimeout(10_000) { conn.closed.await() })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun framesAreLittleEndianChannelKindLength() {
        val f = HcFrames.encode(2, HcFrames.RESIZE, HcFrames.size(120, 40))
        assertEquals(listOf<Byte>(2, 0, 0, 0, 3, 4, 0, 0, 0, 120, 0, 40, 0), f.toList())
        val back = HcFrames.read(f.inputStream())!!
        assertEquals(2, back.channel)
        assertEquals(HcFrames.RESIZE, back.kind)
        assertNull(HcFrames.read(ByteArray(0).inputStream()))
    }

    // --- detection ---

    @Test
    fun detectsARunningCoreAndPrefersItsOwnBuild() {
        val out = """
            core	/home/dev/.local/bin/herdr-core	1924723
            bin	/home/dev/.local/bin/herdr-core	hc 0.1.14	core
            bin	/usr/local/bin/herdr-core	hc 0.1.9	path
            motd noise
        """.trimIndent()
        val p = HcDetection.parse(out)
        assertTrue(p.running)
        assertEquals("/home/dev/.local/bin/herdr-core", p.binary!!.path)
        assertEquals("0.1.14", p.binary!!.version)
        assertEquals(2, p.binaries.size)
        assertEquals("'/home/dev/.local/bin/herdr-core' connect", HcDetection.connectCommand(p.binary!!))
    }

    @Test
    fun anInstalledCoreThatDoesntRunIsNotUsed() {
        val p = HcDetection.parse("bin\t/home/dev/.local/bin/herdr-core\thc 0.1.14\tpath\n")
        assertFalse(p.running)
        assertNotNull(p.binary)
        assertTrue(HcDetection.parse("").binaries.isEmpty())
        // Without pgrep, a socket where a core keeps one counts.
        assertTrue(HcDetection.parse("socket\t/run/user/1000/herdr-core/default.sock\nbin\t/usr/bin/herdr-core\thc 0.1.14\tpath\n").running)
    }

    @Test
    fun theProbeScriptFindsHerdrCoreInAHome() {
        val home = Files.createTempDirectory("hc-home").toFile()
        try {
            val bin = File(home, ".local/bin").apply { mkdirs() }
            File(bin, "herdr-core").apply {
                writeText("#!/bin/sh\necho 'hc 0.1.14'\n")
                setExecutable(true)
            }
            // An `hc` alone isn't taken for herdr-core.
            File(bin, "hc").apply {
                writeText("#!/bin/sh\necho 'herbstclient 0.9.5'\n")
                setExecutable(true)
            }
            val p = ProcessBuilder("sh", "-c", HcDetection.probe)
                .apply { environment().apply { put("HOME", home.path); put("SHELL", "/bin/sh"); put("PATH", "/usr/bin:/bin") } }
                .redirectErrorStream(true)
                .start()
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
            assertEquals(0, p.waitFor())
            val probe = HcDetection.parse(out)
            val mine = probe.binaries.firstOrNull { it.path == "${home.path}/.local/bin/herdr-core" }
            assertNotNull(mine, out)
            assertEquals("0.1.14", mine.version)
            assertEquals("path", mine.source)
            assertFalse(probe.binaries.any { it.path.endsWith("/hc") }, out)
        } finally {
            home.deleteRecursively()
        }
    }
}
