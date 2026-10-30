package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.hc.HcMapping
import com.makefunstuff.herdr.core.hc.HcTerminalStream
import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.core.keys.KeyImport
import com.makefunstuff.herdr.core.ssh.Machine
import com.makefunstuff.herdr.core.terminal.TerminalStream
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * herdr-core on a real machine, over SSH as the app reaches it: found next to classic herdr, one
 * `hc connect` kept on the connection, its terminals mapped and followed, a terminal read and
 * attached. Read only: the attach observes, nothing is typed. Runs only with HERDR_LIVE_KEY and
 * HERDR_LIVE_CORE (the machine, `user@host[:port]`) set.
 */
class LiveHerdrCoreTest {
    private val keyPath = System.getenv("HERDR_LIVE_KEY")
    private val target = System.getenv("HERDR_LIVE_CORE")

    @Test
    fun followsHerdrCoreBesideClassicHerdrOverSsh() {
        assumeTrue(keyPath != null && target != null, "set HERDR_LIVE_KEY and HERDR_LIVE_CORE to run")
        val identity = KeyImport.fromPrivateKey(File(keyPath!!).readText(), "test")
        val (user, host, port) = Machine.parseTarget(target!!)!!
        val machine = Machine("live", "live", host, port, user ?: "root", session = System.getenv("HERDR_LIVE_SESSION") ?: "default")
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val session = HostSession(machine, { identity }, { null }, scope)
            try {
                session.start()
                val s = withTimeout(60_000) { session.state.first { it.online && it.herdrCore != null } }
                val core = s.herdrCore!!
                println("herdr-core ${core.version} on ${core.coreName} (${core.binary}); classic herdr ${s.herdrVersion}")
                // Classic herdr's own snapshot is still there, untouched.
                assertNotNull(s.snapshot)
                assertTrue(s.snapshot!!.panes.none { it.paneId.startsWith(HcMapping.PREFIX) })

                val client = session.awaitHerdrCore()
                val live = client.list().terminals.filter { it.live }
                assertEquals(live.map { it.address }.toSet(), core.snapshot.panes.map { it.terminalId }.toSet())
                for (w in core.snapshot.workspaces) {
                    println("space ${w.label} (${w.workspaceId}): ${core.snapshot.tabs.filter { it.workspaceId == w.workspaceId }.joinToString { it.label }}")
                }
                for (a in core.snapshot.agents) println("agent ${a.agent} ${a.agentStatusRaw} ${a.name} @ ${core.snapshot.location(a.workspaceId, a.tabId)}")

                val first = live.firstOrNull() ?: return@runBlocking println("no live terminals to read")
                val read = client.read(first.address, lines = 20)
                println("read ${first.address}: ${read.how}, ${read.text.trim().lines().take(3)}")

                val stream = HcTerminalStream(client, first.address, 80, 24, scope, observe = true)
                stream.open()
                withTimeout(20_000) { stream.revision.first { it > 0 } }
                assertEquals(TerminalStream.Status.Live, stream.status.value)
                val screen = synchronized(stream.emulator.lock) { stream.emulator.screenText() }
                println("attached ${first.address}:\n" + screen.lines().filter { it.isNotBlank() }.take(6).joinToString("\n"))
                stream.close()
            } finally {
                session.stop()
                scope.cancel()
            }
        }
    }

    /**
     * A second machine in the app for another herdr session of the same host, whose session isn't
     * there: herdr-core belongs to the default session's machine, so it isn't followed here too (its
     * agents would show twice), and the status says what classic herdr lacks, as before.
     */
    @Test
    fun anotherSessionsMachineLeavesHerdrCoreToTheDefaultOne() {
        assumeTrue(keyPath != null && target != null, "set HERDR_LIVE_KEY and HERDR_LIVE_CORE to run")
        val identity = KeyImport.fromPrivateKey(File(keyPath!!).readText(), "test")
        val (user, host, port) = Machine.parseTarget(target!!)!!
        val machine = Machine("live", "live", host, port, user ?: "root", session = "ha5-no-such-session")
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val session = HostSession(machine, { identity }, { null }, scope)
            try {
                session.start()
                val s = withTimeout(60_000) { session.state.first { it.status is com.makefunstuff.herdr.core.host.HostStatus.Attention } }
                kotlinx.coroutines.delay(5_000)
                println("status: ${s.status}")
                assertEquals(null, session.state.value.herdrCore)
                assertTrue(!session.state.value.online)
            } finally {
                session.stop()
                scope.cancel()
            }
        }
    }
}
