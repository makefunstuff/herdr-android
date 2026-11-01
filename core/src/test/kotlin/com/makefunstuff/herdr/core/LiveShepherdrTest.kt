package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.board.BoardApi
import com.makefunstuff.herdr.core.board.CardAnswer
import com.makefunstuff.herdr.core.board.WorkStatus
import com.makefunstuff.herdr.core.hc.HcMapping
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.core.keys.KeyImport
import com.makefunstuff.herdr.core.shepherdr.CurlTransport
import com.makefunstuff.herdr.core.shepherdr.ShepherdrClient
import com.makefunstuff.herdr.core.shepherdr.SpaceChat
import com.makefunstuff.herdr.core.shepherdr.SpaceChatItem
import com.makefunstuff.herdr.core.shepherdr.V2Version
import com.makefunstuff.herdr.core.shepherdr.V2WorkBoard
import com.makefunstuff.herdr.core.ssh.Machine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Shepherdr 2 on a real machine, over SSH as the app reaches it: detection, the port forward, the
 * snapshot, a chat through the live link. Read only: nothing is sent or answered. Runs only with
 * HERDR_LIVE_KEY and HERDR_LIVE_SHEPHERDR (the machine, `user@host[:port]`) set.
 */
class LiveShepherdrTest {
    private val keyPath = System.getenv("HERDR_LIVE_KEY")
    private val target = System.getenv("HERDR_LIVE_SHEPHERDR")

    /**
     * Chatting with the home space's coordinator as the Coordinator screen does: a message sent goes in
     * and comes back with its id, the coordinator works and replies, and a choice card it was asked to
     * put up is answered from here. Writes into the home space's chat, so it runs only with
     * HERDR_LIVE_SEND=1 as well.
     */
    @Test
    fun chatsWithTheHomeCoordinatorOverSsh() {
        assumeTrue(keyPath != null && target != null && System.getenv("HERDR_LIVE_SEND") == "1", "set HERDR_LIVE_KEY, HERDR_LIVE_SHEPHERDR and HERDR_LIVE_SEND=1 to run")
        val identity = KeyImport.fromPrivateKey(File(keyPath!!).readText(), "test")
        val (user, host, port) = Machine.parseTarget(target!!)!!
        val machine = Machine("live", "live", host, port, user ?: "root", session = System.getenv("HERDR_LIVE_SESSION") ?: "default")
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val session = HostSession(machine, { identity }, { null }, scope)
            try {
                session.start()
                withTimeout(60_000) { session.state.first { it.online && it.boardChecked } }
                val board = session.awaitWorkBoard()
                val home = board.overview().spaces.first { it.home }
                val cid = "ha3-live-${System.currentTimeMillis()}"
                val title = "HA-3 live check $cid?"
                board.say(
                    home.id,
                    "This is an automated check from the herdr-android app's tests (task HA-3), not the human. " +
                        "Please put up one choice card, exactly: shepherdr ask \"$title\" --choice \"Yes\" --choice \"No\" " +
                        "and end your turn. When it is answered, reply in one short line with the answer you got.",
                    cid = cid,
                )
                val seen = mutableSetOf<String>()
                val card = withTimeout(240_000) {
                    board.coordinatorThreads(home.id).first { t ->
                        val items = SpaceChat.items(home.id, t.v2!!)
                        if (items.any { it.id.startsWith("cid:") }) seen += "outgoing"
                        if (t.v2!!.messages.any { it.cid == cid }) seen += "in"
                        if (t.working) seen += "working"
                        t.cards.any { it.question.title == title }
                    }.cards.first { it.question.title == title }
                }
                println("seen before the card: $seen")
                assertTrue("in" in seen, "the message came back with its id")
                board.answer(CardAnswer(card, choice = 0))
                val after = withTimeout(240_000) {
                    board.coordinatorThreads(home.id).first { t ->
                        val q = t.v2!!.messages.firstNotNullOfOrNull { m -> m.question?.takeIf { it.title == title } }
                        q != null && !q.open && !t.working && t.v2!!.messages.last().question == null && !t.v2!!.messages.last().you
                    }
                }
                val items = SpaceChat.items(home.id, after.v2!!)
                val answered = items.filterIsInstance<SpaceChatItem.Question>().first { it.card.question.title == title }
                assertEquals("Yes", answered.answer)
                println("its reply: " + (items.last() as? SpaceChatItem.Words)?.text)
            } finally {
                session.stop()
                scope.cancel()
            }
        }
    }

    /**
     * Shepherdr 2.1 (the standalone app on herdr-core) as the app sees it: its agents, which run in
     * herdr-core's `shepherdr` session, are on the machine's agents list once each, called by their
     * task or space, beside its board. Read only.
     */
    @Test
    fun seesShepherdr21AgentsBesideItsBoardOverSsh() {
        assumeTrue(keyPath != null && target != null, "set HERDR_LIVE_KEY and HERDR_LIVE_SHEPHERDR to run")
        val identity = KeyImport.fromPrivateKey(File(keyPath!!).readText(), "test")
        val (user, host, port) = Machine.parseTarget(target!!)!!
        val machine = Machine("live", "live", host, port, user ?: "root", session = System.getenv("HERDR_LIVE_SESSION") ?: "default")
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val session = HostSession(machine, { identity }, { null }, scope)
            try {
                session.start()
                val s = withTimeout(60_000) { session.state.first { it.online && it.boardChecked && it.agentsKnown } }
                val version = s.shepherdrVersion
                assumeTrue(version != null && V2Version(version = version).standalone, "Shepherdr 2.1 runs there")
                val core = s.herdrCore!!
                println("herdr-core ${core.version}, sessions: default + ${core.sessions}")
                assertTrue("shepherdr" in core.sessions, "Shepherdr's own session is followed")
                val agents = s.view!!.agents
                val ids = agents.map { it.paneId }
                assertEquals(ids.distinct(), ids, "each agent once")
                val ours = agents.filter { HcMapping.session(it.paneId) == "shepherdr" }
                println("its agents: " + ours.joinToString { "${it.name}:${it.agentStatusRaw}" })
                assertTrue(ours.isNotEmpty())
                assertTrue(ours.none { it.name.orEmpty().startsWith("shep-") }, "called by task or space, not the terminal's own name")

                val board = session.awaitWorkBoard().overview()
                val working = board.tasks.filter { it.status == WorkStatus.Work }.map { it.id }
                println("working tasks: $working")
                // Every working task's agent is on the list.
                assertTrue(working.all { id -> ours.any { it.name == id } }, "working tasks' agents: $working")

                // Its terminal answers through the session's own core.
                val port = session.awaitAgent(ours.first().paneId)
                assertTrue(port.read(ours.first().paneId, 20, visible = true, ansi = false).isNotEmpty())
            } finally {
                session.stop()
                scope.cancel()
            }
        }
    }

    @Test
    fun readsARealShepherdrOverSsh() {
        assumeTrue(keyPath != null && target != null, "set HERDR_LIVE_KEY and HERDR_LIVE_SHEPHERDR to run")
        val identity = KeyImport.fromPrivateKey(File(keyPath!!).readText(), "test")
        val (user, host, port) = Machine.parseTarget(target!!)!!
        val machine = Machine("live", "live", host, port, user ?: "root", session = System.getenv("HERDR_LIVE_SESSION") ?: "default")
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val session = HostSession(machine, { identity }, { null }, scope)
            try {
                session.start()
                val s = withTimeout(60_000) { session.state.first { it.online && it.boardChecked } }
                println("v2: ${s.shepherdrVersion} · v1 launcher: ${s.boardPath} · api: ${s.boardApi}")
                assertTrue(s.boardApi is BoardApi.V2, "Shepherdr 2 answers there")

                val client = session.awaitShepherdr()
                assertTrue(client.canStream, "reached through the port forward, not curl")
                assertEquals(2, client.version().major)
                // The same forward is kept for the next call.
                assertTrue(session.awaitShepherdr().canStream)

                val board = session.awaitWorkBoard()
                assertTrue(board is V2WorkBoard)
                val o = board.overview()
                println("spaces: " + o.spaces.joinToString { "${it.name}${if (it.needsYou) "!" else ""}" })
                println("tasks: " + o.tasks.joinToString { "${it.name}:${it.status}" })
                assertTrue(o.spaces.any { it.home })

                val home = o.spaces.first { it.home }
                val thread = board.coordinator(home.id)
                println("home chat: ${thread.lines.size} lines, working ${thread.working}, ${thread.cards.size} cards")
                assertEquals(home.chat, thread.v2!!.key)

                o.tasks.firstOrNull { it.chat.isNotEmpty() }?.let { t ->
                    val d = board.details(t)
                    println("${t.name}: brief ${d.brief.length} chars, ${d.cards.size} open cards, branch ${d.branch}")
                    assertEquals(t.id, d.task.id)
                }
                println("waiting: ${client.waiting()}")

                // Where the SSH server won't forward: curl for the API, the machine's bun for a chat.
                val conn = session.awaitConnection()
                val curl = V2WorkBoard(ShepherdrClient(CurlTransport { c, stdin, t -> conn.runWithInput(c, CurlTransport.stdin(stdin), t) }))
                assertEquals(o.tasks.map { it.id }, curl.overview().tasks.map { it.id })
                val viaBun = curl.coordinator(home.id)
                println("home chat without a forward: ${viaBun.lines.size} lines")
                assertEquals(home.chat, viaBun.v2!!.key)
            } finally {
                session.stop()
                scope.cancel()
            }
        }
    }
}
