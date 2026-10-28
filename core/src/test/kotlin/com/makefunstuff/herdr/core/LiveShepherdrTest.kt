package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.board.BoardApi
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.core.keys.KeyImport
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
            } finally {
                session.stop()
                scope.cancel()
            }
        }
    }
}
