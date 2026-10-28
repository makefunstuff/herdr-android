package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.board.BoardApi
import com.makefunstuff.herdr.core.board.BoardStatus
import com.makefunstuff.herdr.core.board.CardAnswer
import com.makefunstuff.herdr.core.board.V1Cards
import com.makefunstuff.herdr.core.board.WorkCard
import com.makefunstuff.herdr.core.board.WorkStatus
import com.makefunstuff.herdr.core.board.WorkTask
import com.makefunstuff.herdr.core.board.WorkTasks
import com.makefunstuff.herdr.core.shepherdr.BoardDetection
import com.makefunstuff.herdr.core.shepherdr.CurlTransport
import com.makefunstuff.herdr.core.shepherdr.ForwardTransport
import com.makefunstuff.herdr.core.shepherdr.ShepherdrClient
import com.makefunstuff.herdr.core.shepherdr.ShepherdrJson
import com.makefunstuff.herdr.core.shepherdr.V2Actions
import com.makefunstuff.herdr.core.shepherdr.V2Board
import com.makefunstuff.herdr.core.shepherdr.V2Event
import com.makefunstuff.herdr.core.shepherdr.V2Events
import com.makefunstuff.herdr.core.shepherdr.V2Snapshot
import com.makefunstuff.herdr.core.shepherdr.V2Status
import com.makefunstuff.herdr.core.shepherdr.V2TaskStatus
import com.makefunstuff.herdr.core.shepherdr.V2Version
import com.makefunstuff.herdr.core.shepherdr.V2WorkBoard
import com.makefunstuff.herdr.core.shepherdr.WebSocketLink
import com.makefunstuff.herdr.core.shepherdr.Wire
import com.makefunstuff.herdr.core.ssh.CommandResult
import com.makefunstuff.herdr.core.ssh.Duplex
import com.makefunstuff.herdr.core.ssh.PortForward
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShepherdrTest {
    private fun fixture(name: String) = javaClass.getResource("/shepherdr/$name")!!.readText()

    // ---- the snapshot and chats, as this machine's Shepherdr 2.0.0 sent them -----------------------

    @Test
    fun readsTheSnapshotThisMachineSent() {
        val s = ShepherdrJson.decodeFromString(V2Snapshot.serializer(), fixture("snapshot.json"))
        assertEquals("beefy-machine", s.host)
        assertEquals(listOf("p:beefy-machine:~", "p:beefy-machine:herdr-android"), s.places.map { it.id })
        val home = s.places[0]
        assertTrue(home.home)
        assertEquals("p:beefy-machine:~", home.chat)
        assertEquals(V2Status.Work, home.status)
        assertEquals("herdr-android", s.places[1].name)
        assertTrue(s.places[1].githubRemote)
        assertEquals("beefy-machine", s.machines.single().machine)
        assertEquals(listOf("HA-1", "HA-2", "HA-3"), s.tasks.map { it.id })
        val t = s.task("HA-1")!!
        assertEquals(V2TaskStatus.Work, t.status)
        assertEquals("p:beefy-machine:herdr-android", t.place)
        assertEquals("HA-1/Agent", t.agent!!.chat)
        assertEquals("work/1-shepherdr-2-client-in-core", t.branch)
        assertEquals(listOf("HA-1"), s.task("HA-2")!!.after)
        assertEquals(V2TaskStatus.Queued, s.task("HA-2")!!.status)
        assertTrue(s.asks.isEmpty())
    }

    @Test
    fun theSnapshotAsTheBoardSeesIt() {
        val o = V2Board.overview(ShepherdrJson.decodeFromString(V2Snapshot.serializer(), fixture("snapshot.json")))
        assertEquals(2, o.spaces.size)
        assertFalse(o.spaces.any { it.needsYou })
        assertEquals(listOf("HA-1", "HA-2", "HA-3"), o.tasks.map { it.id })
        val t = o.tasks[0]
        assertEquals(WorkStatus.Work, t.status)
        assertEquals("Shepherdr 2 client in core", t.title)
        assertEquals("HA-1/Agent", t.chat)
        assertEquals("Working", t.line)
        assertEquals(listOf("HA-2", "HA-3"), o.tasksIn("p:beefy-machine:herdr-android").filter { it.status == WorkStatus.Queued }.map { it.id })
    }

    @Test
    fun whatNeedsYouComesFirstAndMarksItsSpace() {
        val s = ShepherdrJson.decodeFromString(V2Snapshot.serializer(), fixture("snapshot.json"))
        val changed = s.copy(
            tasks = s.tasks.mapIndexed { i, t -> if (i == 2) t.copy(status = V2TaskStatus.Need) else if (i == 1) t.copy(status = V2TaskStatus.Ready) else t },
            asks = mapOf("p:beefy-machine:~" to "f1"),
        )
        val o = V2Board.overview(changed)
        assertEquals(listOf("HA-3", "HA-2", "HA-1"), o.tasks.map { it.id })
        assertEquals(listOf("HA-3", "HA-2"), o.needsYou.map { it.id })
        assertTrue(o.spaces.all { it.needsYou })
    }

    @Test
    fun anArchivedTaskIsLeftOutAndAnUnknownStatusStillReads() {
        val s = ShepherdrJson.decodeFromString(
            V2Snapshot.serializer(),
            """{"places":[],"tasks":[{"id":"A-1","status":"someday","archived":5},{"id":"A-2","status":"weird","newField":{"x":1}}]}""",
        )
        assertEquals(V2TaskStatus.Todo, s.tasks[1].status)
        assertEquals(listOf("A-2"), V2Board.overview(s).tasks.map { it.id })
    }

    @Test
    fun readsTheChatEventThisMachineSent() {
        val e = V2Events.parse(fixture("chat-event.json")) as V2Event.Chat
        val c = e.chat
        assertEquals("p:beefy-machine:herdr-android", c.key)
        assertEquals(4, c.messages.size)
        assertTrue(c.messages[0].you)
        assertFalse(c.messages[1].you)
        assertTrue(c.messages[2].activity != null && c.messages[2].text == null)
        val thread = V2Board.thread("p:beefy-machine:herdr-android", c)
        // The turn's tool steps are no bubble.
        assertEquals(3, thread.lines.size)
        assertEquals(listOf(true, false, false), thread.lines.map { it.you })
        assertTrue(thread.cards.isEmpty())
        assertFalse(thread.working)
    }

    @Test
    fun readsQuestionsCardsAndPlansFromShepherdrsDemo() {
        val events = V2Events.parseAll(fixture("demo-chat-events.json")).filterIsInstance<V2Event.Chat>().associateBy { it.chat.key }
        val space = events.getValue("p:vm-str:gtm").chat
        val cards = space.messages.mapNotNull { it.card }
        assertEquals(listOf("GTM-15", "GTM-14", "GTM-16", "GTM-12"), cards.map { it.task })
        assertEquals(V2TaskStatus.Ready, cards[0].status)
        assertEquals(128, cards[0].pr!!.number)
        val q = space.openQuestions.single()
        assertEquals("f1", q.id)
        assertEquals(listOf("Rerun it", "Skip the flaky test", "Leave it for now"), q.choices.map { it.label })
        val thread = V2Board.thread("p:vm-str:gtm", space)
        assertEquals(listOf("f1"), thread.cards.map { it.question.id })
        assertEquals("p:vm-str:gtm", thread.cards[0].chat)
        assertTrue(thread.lines.any { it.text == "GTM-15 is ready to merge" })

        val perm = events.getValue("GTM-14/Agent").chat.openQuestions.single()
        assertEquals("Claude Code asks", perm.asks)
        val plan = events.getValue("GTM-16/Agent").chat.openQuestions.single()
        assertEquals("Plan", plan.kind)
        assertEquals(3, plan.steps!!.size)
        assertEquals(1, plan.noteRequiredFor)
    }

    @Test
    fun aNeedsYouCardCarriesItsTasksQuestionsAndAnsweredOnesAreNotOpen() {
        val chat = V2Events.parse(
            """{"type":"chat","chat":{"key":"p:m:x","cli":"Claude Code","working":true,"messages":[
              {"id":"c1","who":"agent","text":"HA-9 needs you","card":{"id":"c1","task":"HA-9","status":"need","text":"needs you",
                "ask":{"chat":"HA-9/Agent","open":true,"questions":[{"id":"q9","title":"Merge HA-9?","kind":"Form","choices":[{"label":"Merge"},{"label":"Not yet"}],"to":"HA-9 agent"}]}}},
              {"id":"m2","who":"agent","question":{"id":"q2","title":"Done?","kind":"Form","choices":[{"label":"Yes"}],"to":"x","sent":0}},
              {"id":"m3","who":"you","text":"hi","waiting":true},
              {"id":"c4","who":"agent","text":"HA-8 needs you","card":{"id":"c4","task":"HA-8","status":"need","text":"needs you",
                "ask":{"chat":"HA-8/Agent","open":true,"reply":{"id":"m77","text":"Which port should it use?"}}}}
            ]}}""",
        ) as V2Event.Chat
        val thread = V2Board.thread("p:m:x", chat.chat)
        assertEquals(WorkCard("HA-9/Agent", chat.chat.messages[0].card!!.ask!!.questions!![0], "HA-9", space = "p:m:x"), thread.cards[0])
        // A plain question copied here is answered with a reply to its message.
        val plain = thread.cards[1]
        assertEquals("HA-8/Agent", plain.chat)
        assertEquals("m77", plain.replyTo)
        assertEquals("Which port should it use?", plain.question.title)
        assertEquals(2, thread.cards.size)
        assertEquals(listOf(false, false, true, false), thread.lines.map { it.pending })
        assertTrue(thread.working)
        assertTrue(chat.chat.openQuestions.isEmpty())
    }

    @Test
    fun otherEventsAreKeptAsSent() {
        val e = V2Events.parse("""{"type":"build","id":"abc"}""")
        assertEquals("build", (e as V2Event.Other).type)
    }

    // ---- what the app sends ----------------------------------------------------------------------

    @Test
    fun actionBodies() {
        assertEquals("""{"type":"send","chat":"p:beefy-machine:~","text":"hello"}""", V2Actions.send("p:beefy-machine:~", "hello").toString())
        assertEquals(
            """{"type":"send","chat":"HA-1/Agent","text":"yes","cid":"c1","reply":"m4","space":"p:m:x"}""",
            V2Actions.send("HA-1/Agent", "yes", reply = "m4", cid = "c1", space = "p:m:x").toString(),
        )
        assertEquals(
            """{"type":"answer","chat":"GTM-16/Agent","question":"f6","choice":1,"note":"Fewer pages"}""",
            V2Actions.answer("GTM-16/Agent", "f6", 1, note = "Fewer pages").toString(),
        )
        assertEquals(
            """{"type":"answer","chat":"c","question":"q","choice":-1,"choices":[0,2],"other":"Both"}""",
            V2Actions.answer("c", "q", -1, choices = listOf(0, 2), note = "", other = "Both").toString(),
        )
        assertEquals("""{"type":"react","chat":"c","message":"m1","emoji":"👍"}""", V2Actions.react("c", "m1", "👍").toString())
        assertEquals("""{"args":["merge","SHE-3"]}""", V2Actions.cli(listOf("merge", "SHE-3")).toString())
        assertEquals("""{"type":"watch","chats":["p:m:~"],"term":null,"visible":false,"push":null}""", V2Actions.watch(listOf("p:m:~")).toString())
    }

    @Test
    fun aRequestCarriesNoOriginSoTheSameSiteCheckLetsItThrough() {
        val r = Wire.request("POST", "/api/action", """{"a":"é"}""").toString(Charsets.UTF_8)
        assertTrue(r.startsWith("POST /api/action HTTP/1.1\r\nHost: 127.0.0.1:7787\r\n"))
        assertFalse(r.contains("Origin", ignoreCase = true))
        assertTrue(r.contains("Content-Length: 10\r\n"))
        assertTrue(r.endsWith("\r\n\r\n{\"a\":\"é\"}"))
    }

    // ---- detection -------------------------------------------------------------------------------

    @Test
    fun v2WinsWhenBothAnswer() {
        val v2 = ShepherdrJson.decodeFromString(V2Version.serializer(), fixture("version.json"))
        assertEquals(2, v2.major)
        assertEquals(BoardApi.V2("2.0.0"), BoardDetection.decide(v2, "/home/u/.herdr-projects/data/bin/herdr-projects"))
        assertEquals(BoardApi.V2("2.0.0"), BoardDetection.decide(v2, null))
        assertEquals(BoardApi.V1("/x/herdr-projects"), BoardDetection.decide(null, "/x/herdr-projects"))
        assertEquals(BoardApi.V1("/x/herdr-projects"), BoardDetection.decide(V2Version(version = "1.9.0"), "/x/herdr-projects"))
        assertEquals(BoardApi.None, BoardDetection.decide(V2Version(version = "nonsense"), null))
        assertEquals(BoardApi.None, BoardDetection.decide(null, null))
    }

    @Test
    fun detectsShepherdrThroughAForwardAndNothingWhereNothingAnswers() = runBlocking {
        FakeShepherdr().use { fake ->
            fake.on("GET /api/version") { 200 to fixture("version.json") }
            ForwardTransport({ ForwardTransport.socket(fake.port, it) }).use { t ->
                assertEquals("2.0.0", BoardDetection.probe(t)!!.version)
            }
        }
        // A port where nothing listens: the SSH channel is refused, the probe says none.
        val dead = ServerSocket(0).use { it.localPort }
        assertNull(BoardDetection.probe(ForwardTransport({ ForwardTransport.socket(dead, it) })))
        // Something else answers there.
        FakeShepherdr().use { fake ->
            fake.on("GET /api/version") { 404 to """{"error":"Not found"}""" }
            assertNull(BoardDetection.probe(ForwardTransport({ ForwardTransport.socket(fake.port, it) })))
        }
    }

    @Test
    fun curlIsTheFallbackWithItsStatusAtTheEnd() = runBlocking<Unit> {
        val cmd = CurlTransport.command("POST", "/api/cli", true, 30)
        assertEquals(
            "'curl' '-sS' '--max-time' '30' '-X' 'POST' '-H' 'Content-Type: application/json' '--data-binary' '@-' '-w' '\n@@shepherdr-status@@%{http_code}' 'http://127.0.0.1:7787/api/cli'",
            cmd,
        )
        val sent = mutableListOf<Pair<String, String>>()
        val curl = CurlTransport { c, stdin, _ ->
            sent += c to stdin
            CommandResult(0, """{"out":"Merged SHE-3\n","code":0}""" + "\n@@shepherdr-status@@200", "")
        }
        assertFalse(curl.canStream)
        val r = ShepherdrClient(curl).cli(listOf("merge", "SHE-3"))
        assertEquals("Merged SHE-3", r.said)
        assertEquals("""{"args":["merge","SHE-3"]}""", sent.single().second)
        val down = CurlTransport { _, _, _ -> CommandResult(7, "\n@@shepherdr-status@@000", "curl: (7) Failed to connect") }
        assertEquals("curl: (7) Failed to connect", assertFailsWith<IOException> { down.http("GET", "/api/version") }.message)
        assertNull(BoardDetection.probe(down))
        val missing = CurlTransport { _, _, _ -> CommandResult(127, "", "sh: curl: not found") }
        assertFailsWith<IOException> { missing.http("GET", "/api/version") }
    }

    // ---- the client over the wire ----------------------------------------------------------------

    @Test
    fun theClientOverASshStylePortForward() = runBlocking {
        FakeShepherdr().use { fake ->
            fake.on("GET /api/snapshot") { 200 to fixture("snapshot.json") }
            fake.on("GET /api/waiting") { 200 to """{"count":2,"tags":["HA-1"]}""" }
            fake.on("POST /api/action") { body -> if (body.contains("\"gone\"")) 200 to """{"ok":false,"error":"That chat is gone."}""" else 200 to "{}" }
            fake.on("POST /api/cli") { 200 to """{"out":"HA-1 can't merge: its checks failed\n","code":1}""" }
            // As MachineConnection.forward does it, with the SSH channel replaced by a socket to the fake.
            val forward = PortForward("127.0.0.1", 7787, preferred = 0) { _, _ -> fake.channel() }
            val client = ShepherdrClient(ForwardTransport.over(forward))
            assertEquals(3, client.snapshot().tasks.size)
            assertEquals(2, client.waiting().count)
            client.send("p:beefy-machine:~", "hello")
            assertEquals("That chat is gone.", assertFailsWith<IOException> { client.send("gone", "hello") }.message)
            val cli = client.cli(listOf("merge", "HA-1"))
            assertFalse(cli.ok)
            val board = V2WorkBoard(client, "2.0.0")
            val t = board.overview().tasks.first()
            assertEquals("HA-1 can't merge: its checks failed", assertFailsWith<IOException> { board.approve(t) }.message)
            assertEquals("""{"args":["merge","HA-1"]}""", fake.bodies("POST /api/cli").last())
            board.answer(CardAnswer(WorkCard("GTM-16/Agent", V1Cards.plan(t, emptyList())), 0))
            assertEquals("""{"type":"answer","chat":"GTM-16/Agent","question":"v1:plan","choice":0}""", fake.bodies("POST /api/action").last())
            // A task's card answered from a space's chat names that space, so its copy there is settled too.
            board.answer(CardAnswer(WorkCard("HA-9/Agent", V1Cards.plan(t, emptyList()), "HA-9", space = "p:m:x"), 1, note = "Smaller"))
            assertEquals(
                """{"type":"answer","chat":"HA-9/Agent","question":"v1:plan","choice":1,"note":"Smaller","space":"p:m:x"}""",
                fake.bodies("POST /api/action").last(),
            )
            val plain = com.makefunstuff.herdr.core.shepherdr.V2Question(id = "m77", title = "Which port?", kind = "Reply", other = true)
            board.answer(CardAnswer(WorkCard("HA-8/Agent", plain, "HA-8", space = "p:m:x", replyTo = "m77"), -1, other = "8080"))
            assertEquals(
                """{"type":"send","chat":"HA-8/Agent","text":"8080","reply":"m77","space":"p:m:x"}""",
                fake.bodies("POST /api/action").last(),
            )
            assertFailsWith<IOException> { board.answer(CardAnswer(WorkCard("HA-8/Agent", plain, replyTo = "m77"), -1)) }
            board.say("p:beefy-machine:~", "hi")
            assertEquals("""{"type":"send","chat":"p:beefy-machine:~","text":"hi"}""", fake.bodies("POST /api/action").last())
            forward.close()
        }
    }

    @Test
    fun cancellingALongCallLetsGoAtOnce() = runBlocking {
        FakeShepherdr().use { fake ->
            val asked = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            fake.on("POST /api/cli") { asked.countDown(); release.await(); 200 to """{"out":"","code":0}""" }
            val client = ShepherdrClient(ForwardTransport({ ForwardTransport.socket(fake.port, it) }))
            val call = launch(kotlinx.coroutines.Dispatchers.IO) { client.cli(listOf("checks", "HA-1")) }
            assertTrue(asked.await(10, java.util.concurrent.TimeUnit.SECONDS))
            // Shepherdr still waits on GitHub: cancelling doesn't wait with it.
            withTimeout(5_000) { call.cancelAndJoin() }
            release.countDown()
        }
    }

    @Test
    fun aServerErrorSaysWhy() = runBlocking {
        FakeShepherdr().use { fake ->
            fake.on("GET /api/snapshot") { 500 to """{"error":"herdr isn't answering"}""" }
            val client = ShepherdrClient(ForwardTransport({ ForwardTransport.socket(fake.port, it) }))
            assertEquals("herdr isn't answering", assertFailsWith<IOException> { client.snapshot() }.message)
        }
    }

    @Test
    fun readsAChunkedAnswer() {
        val raw = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\n{\"a\"\r\n3\r\n:1}\r\n0\r\n\r\n"
        val r = Wire.exchange(bytes(raw), "GET", "/x", null)
        assertEquals(HttpReplyOf(200, """{"a":1}"""), HttpReplyOf(r.status, r.body))
    }

    @Test
    fun theLiveLinkSendsSnapshotsAndTheWatchedChat() = runBlocking {
        FakeShepherdr().use { fake ->
            val chat = fixture("chat-event.json")
            fake.onSocket { ws ->
                ws.sendText("""{"type":"snapshot","snapshot":${fixture("snapshot.json")}}""")
                ws.sendText("""{"type":"build","id":"b1"}""")
                val watch = ws.readText()
                assertTrue(watch.contains("\"watch\"") && watch.contains("p:beefy-machine:herdr-android"))
                ws.ping()
                // A chat in two frames.
                ws.sendText(chat.substring(0, 100), fin = false)
                ws.sendText(chat.substring(100), opcode = 0)
                ws.close()
                // The pong, then the client's own close.
                ws.readText()
            }
            val client = ShepherdrClient(ForwardTransport({ ForwardTransport.socket(fake.port, it) }))
            val link = client.live()
            val first = link.next() as V2Event.Snapshot
            assertEquals(3, first.snapshot.tasks.size)
            assertEquals("build", (link.next() as V2Event.Other).type)
            link.watch(listOf("p:beefy-machine:herdr-android"))
            val c = link.next() as V2Event.Chat
            assertEquals(4, c.chat.messages.size)
            assertNull(link.next())
            withTimeout(10_000) { while (fake.closedSockets.get() == 0) kotlinx.coroutines.delay(20) }
            assertEquals(listOf("p"), fake.pongs)
        }
    }

    @Test
    fun aChatComesThroughTheLiveLinkAndTheCoordinatorsThreadFollowsIt() = runBlocking {
        FakeShepherdr().use { fake ->
            fake.onSocket { ws ->
                ws.sendText("""{"type":"snapshot","snapshot":{"places":[]}}""")
                ws.readText()
                ws.sendText(fixture("chat-event.json"))
                ws.sendText(fixture("chat-event.json").replace("now adding the tasks.", "and one more."))
                // Held open until the client goes.
                ws.readText()
            }
            val board = V2WorkBoard(ShepherdrClient(ForwardTransport({ ForwardTransport.socket(fake.port, it) })))
            val thread = board.coordinator("p:beefy-machine:herdr-android")
            assertEquals(3, thread.lines.size)
            val two = withTimeout(10_000) { board.coordinatorThreads("p:beefy-machine:herdr-android").take(2).toList() }
            assertTrue(two[1].lines[1].text.endsWith("and one more."))
        }
    }

    @Test
    fun aDroppedLiveLinkIsLinkedAgain() = runBlocking {
        FakeShepherdr().use { fake ->
            val links = java.util.concurrent.atomic.AtomicInteger()
            fake.onSocket { ws ->
                val n = links.incrementAndGet()
                ws.sendText("""{"type":"snapshot","snapshot":{"places":[{"id":"p:m:$n"}]}}""")
                // The first link drops (Shepherdr restarts); the second stays until the app goes.
                if (n == 1) ws.close() else ws.readText()
            }
            val board = V2WorkBoard(ShepherdrClient(ForwardTransport({ ForwardTransport.socket(fake.port, it) })))
            val two = withTimeout(10_000) { board.overviews().take(2).toList() }
            assertEquals(listOf("p:m:1", "p:m:2"), two.map { it.spaces.single().id })
        }
    }

    @Test
    fun theFirstFailureOfALiveFlowIsThrown() = runBlocking<Unit> {
        val dead = ServerSocket(0).use { it.localPort }
        val board = V2WorkBoard(ShepherdrClient(ForwardTransport({ ForwardTransport.socket(dead, it) })))
        assertFailsWith<IOException> { withTimeout(10_000) { board.overviews().first() } }
    }

    @Test
    fun withoutAForwardAChatIsReadByTheMachinesBun() = runBlocking {
        val cmd = CurlTransport.chatCommand("p:m:it's", 15)
        assertTrue(cmd.startsWith("sh -c "))
        assertTrue(cmd.contains("bun -e") && cmd.contains("node -e"))
        // The key goes in as a JSON string, its quote escaped for the shell.
        assertTrue(cmd.contains("const k=\"p:m:it"))
        val event = fixture("chat-event.json").replace("\n", " ")
        val curl = CurlTransport { c, _, _ ->
            if (c.contains("bun -e")) CommandResult(0, "login banner\n@@shepherdr-chat@@" + ShepherdrJson.parseToJsonElement(event) + "\n", "")
            else CommandResult(0, fixture("snapshot.json") + "\n@@shepherdr-status@@200", "")
        }
        val board = V2WorkBoard(ShepherdrClient(curl))
        val thread = board.coordinator("p:beefy-machine:herdr-android")
        assertEquals(3, thread.lines.size)
        // No live link: the flows read again.
        assertEquals(3, withTimeout(10_000) { board.overviews(everyMs = 10).take(2).toList() }.last().tasks.size)
        val none = CurlTransport { _, _, _ -> CommandResult(3, "", "Shepherdr sent no chat x") }
        assertEquals("Shepherdr sent no chat x", assertFailsWith<IOException> { ShepherdrClient(none).chat("x") }.message)
    }

    @Test
    fun cancellingTheLiveLinkClosesIt() = runBlocking {
        FakeShepherdr().use { fake ->
            fake.onSocket { ws ->
                ws.sendText("""{"type":"snapshot","snapshot":{"places":[{"id":"p:m:~","home":true}]}}""")
                ws.readText()
            }
            val board = V2WorkBoard(ShepherdrClient(ForwardTransport({ ForwardTransport.socket(fake.port, it) })))
            val o = withTimeout(10_000) { board.overviews().first() }
            assertEquals("p:m:~", o.spaces.single().id)
            // The fake's read returned once the app closed its end.
            withTimeout(10_000) { while (fake.closedSockets.get() == 0) kotlinx.coroutines.delay(20) }
        }
    }

    @Test
    fun aClientsFrameIsMasked() {
        val mask = byteArrayOf(1, 2, 3, 4)
        val f = WebSocketLink.encode(1, "hi".toByteArray(), mask)
        assertContentEquals(byteArrayOf(0x81.toByte(), 0x82.toByte(), 1, 2, 3, 4, ('h'.code xor 1).toByte(), ('i'.code xor 2).toByte()), f)
        val long = WebSocketLink.encode(1, ByteArray(300), mask)
        assertEquals(0xFE.toByte(), long[1])
        assertEquals(300, (long[2].toInt() and 0xFF shl 8) or (long[3].toInt() and 0xFF))
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", Wire.acceptFor("dGhlIHNhbXBsZSBub25jZQ=="))
    }

    // ---- v1 in the same shapes -------------------------------------------------------------------

    @Test
    fun v1StatusesInV2Words() {
        assertEquals(WorkStatus.Ready, WorkTasks.status(BoardStatus.Approval))
        for (s in listOf(BoardStatus.ReviewFailed, BoardStatus.PlanReady, BoardStatus.PickOne, BoardStatus.Asks, BoardStatus.AsksOk, BoardStatus.NeedsLogin, BoardStatus.Stopped, BoardStatus.Error)) {
            assertEquals(WorkStatus.Need, WorkTasks.status(s), s.name)
        }
        assertEquals(WorkStatus.Work, WorkTasks.status(BoardStatus.Reviewing))
        assertEquals(WorkStatus.Queued, WorkTasks.status(BoardStatus.Queued))
        assertEquals(WorkStatus.Todo, WorkTasks.status(BoardStatus.Todo))
        assertEquals(WorkStatus.Done, WorkTasks.status(BoardStatus.Archived))
        // Every v1 status that needs you still does.
        for (s in BoardStatus.entries) if (s.needsYou) assertTrue(WorkTasks.status(s).needsYou, s.name)
    }

    @Test
    fun pinnedFirstWithinEachStatus() {
        fun t(id: String, s: WorkStatus, pinned: Boolean = false) = WorkTask(id, id, id, "", s, pinned = pinned)
        val out = WorkTasks.ordered(listOf(t("a", WorkStatus.Done), t("b", WorkStatus.Work), t("c", WorkStatus.Work, true), t("d", WorkStatus.Need)))
        assertEquals(listOf("d", "c", "b", "a"), out.map { it.id })
    }

    @Test
    fun v1PickCardsCarryTheAttemptsLetter() {
        val t = WorkTask("repo/3", "AND-3", "x", "", WorkStatus.Need)
        val q = V1Cards.pick(t, listOf(com.makefunstuff.herdr.core.board.Attempt("A", "claude", "", "+3 -1", "", ""), com.makefunstuff.herdr.core.board.Attempt("B", "codex", "", "", "", "")))
        assertEquals(listOf("A · claude", "B · codex"), q.choices.map { it.label })
        assertEquals("B", V1Cards.letter(q, 1))
        assertFailsWith<IOException> { V1Cards.letter(q, 5) }
        assertEquals("Plan", V1Cards.plan(t, listOf("one")).kind)
    }

    private data class HttpReplyOf(val status: Int, val body: String)

    private fun bytes(raw: String): Duplex = object : Duplex {
        override val input: InputStream = raw.toByteArray().inputStream()
        override val output: OutputStream = ByteArrayOutputStream()
        override fun close() {}
    }
}

/** A stand-in for Shepherdr's server on a loopback port: canned HTTP answers and a scripted live link. */
private class FakeShepherdr : AutoCloseable {
    private val server = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    private val routes = HashMap<String, (String) -> Pair<Int, String>>()
    private val seen = CopyOnWriteArrayList<Pair<String, String>>()
    private var socketScript: ((FakeSocket) -> Unit)? = null
    val pongs = CopyOnWriteArrayList<String>()
    val closedSockets = java.util.concurrent.atomic.AtomicInteger()

    init {
        thread(isDaemon = true) {
            while (true) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { serve(s) }; runCatching { s.close() } }
            }
        }
    }

    fun on(route: String, answer: (String) -> Pair<Int, String>) { routes[route] = answer }
    fun onSocket(script: (FakeSocket) -> Unit) { socketScript = script }
    fun bodies(route: String): List<String> = seen.filter { it.first == route }.map { it.second }

    /** A connection to it, as the SSH channel the port forward opens. */
    fun channel(): Duplex {
        val s = Socket("127.0.0.1", port)
        return object : Duplex {
            override val input: InputStream = s.getInputStream()
            override val output: OutputStream = s.getOutputStream()
            override fun close() = s.close()
        }
    }

    private fun serve(s: Socket) {
        val input = BufferedInputStream(s.getInputStream())
        val (line, headers) = head(input)
        val (method, path) = line.split(' ').let { it[0] to it[1] }
        assertNull(headers["origin"])
        assertEquals("127.0.0.1:7787", headers["host"])
        if (headers["upgrade"] == "websocket") {
            val accept = Wire.acceptFor(headers.getValue("sec-websocket-key"))
            s.getOutputStream().write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
            try {
                socketScript?.invoke(FakeSocket(input, s.getOutputStream(), pongs))
            } finally {
                closedSockets.incrementAndGet()
            }
            return
        }
        val body = headers["content-length"]?.toInt()?.let { String(Wire.readBody(input, headers)) } ?: ""
        seen += "$method $path" to body
        val (status, text) = routes["$method $path"]?.invoke(body) ?: (404 to """{"error":"Not found"}""")
        val bytes = text.toByteArray()
        s.getOutputStream().write("HTTP/1.1 $status X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray() + bytes)
        s.getOutputStream().flush()
    }

    private fun head(input: InputStream): Pair<String, Map<String, String>> {
        val lines = mutableListOf<String>()
        val b = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) break
            if (c == '\n'.code) {
                val l = b.toString().trimEnd('\r'); b.clear()
                if (l.isEmpty()) break
                lines += l
            } else b.append(c.toChar())
        }
        return lines.first() to lines.drop(1).associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
    }

    override fun close() { server.close() }
}

/** The server's end of a WebSocket: unmasked frames out, masked ones in. */
private class FakeSocket(private val input: InputStream, private val output: OutputStream, private val pongs: MutableList<String>) {
    fun sendText(text: String, fin: Boolean = true, opcode: Int = 1) = frame(opcode, text.toByteArray(), fin)
    fun ping() = frame(0x9, "p".toByteArray(), true)
    fun close() = frame(0x8, ByteArray(0), true)

    private fun frame(opcode: Int, payload: ByteArray, fin: Boolean) {
        val out = ByteArrayOutputStream()
        out.write((if (fin) 0x80 else 0) or opcode)
        when {
            payload.size < 126 -> out.write(payload.size)
            payload.size <= 0xFFFF -> { out.write(126); out.write(payload.size shr 8); out.write(payload.size and 0xFF) }
            else -> { out.write(127); for (i in 7 downTo 0) out.write(((payload.size.toLong() shr (8 * i)) and 0xFF).toInt()) }
        }
        out.write(payload)
        output.write(out.toByteArray())
        output.flush()
    }

    /** The client's next text message (its pongs noted on the way); "" once it closed. */
    fun readText(): String {
        while (true) {
            val b0 = input.read()
            if (b0 < 0) return ""
            val b1 = input.read()
            var len = b1 and 0x7F
            if (len == 126) len = (input.read() shl 8) or input.read()
            assertTrue(b1 and 0x80 != 0, "a client's frames are masked")
            val mask = Wire.readExactly(input, 4)
            val p = Wire.readExactly(input, len)
            for (i in p.indices) p[i] = (p[i].toInt() xor mask[i % 4].toInt()).toByte()
            when (b0 and 0x0F) {
                0xA -> pongs += String(p)
                0x8 -> return ""
                else -> return String(p)
            }
        }
    }
}
