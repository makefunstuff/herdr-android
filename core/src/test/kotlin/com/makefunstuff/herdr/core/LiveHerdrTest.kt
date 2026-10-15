package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.host.HostSession
import com.makefunstuff.herdr.core.host.HostStatus
import com.makefunstuff.herdr.core.keys.KeyImport
import com.makefunstuff.herdr.core.ssh.HerdrCommands
import com.makefunstuff.herdr.core.ssh.Machine
import com.makefunstuff.herdr.core.ssh.SshConnector
import com.makefunstuff.herdr.core.terminal.TerminalStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end checks against a real herdr server over SSH. Runs only when HERDR_LIVE_KEY points at
 * a private key accepted by HERDR_LIVE_TARGET (user@host:port).
 */
class LiveHerdrTest {
    private val keyPath = System.getenv("HERDR_LIVE_KEY")
    private val target = System.getenv("HERDR_LIVE_TARGET")

    private fun machine(): Machine {
        val (user, host, port) = Machine.parseTarget(target)!!
        return Machine("live", "live", host, port, user ?: "root")
    }

    @Test
    fun connectsSnapshotsAndStreamsATerminal() {
        if (keyPath == null || target == null) return
        val identity = KeyImport.fromPrivateKey(File(keyPath).readText(), "test")
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val learned = mutableListOf<String>()
            val session = HostSession(machine(), { identity }, { null }, scope, { _, fp -> learned += fp })
            val log = scope.launch { session.state.collect { println("STATE ${it.status}") } }
            session.start()
            val state = withTimeout(20_000) {
                session.state.first { it.online || it.status is HostStatus.Attention }
            }
            assertEquals(HostStatus.Online, state.status, "status: ${state.status}")
            assertTrue(learned.single().startsWith("SHA256:"))
            val snap = state.snapshot!!
            assertTrue(snap.workspaces.isNotEmpty(), "expected a workspace on the live server")
            val pane = snap.panes.first()

            val api = session.awaitApi()
            api.sendText(pane.paneId, "echo live-check-\$((40+2))")
            api.sendKeys(pane.paneId, listOf("enter"))
            withTimeout(10_000) {
                while (!api.readPane(pane.paneId, "recent", 30).text.contains("live-check-42")) delay(200)
            }

            // Events: creating a tab should show up in the snapshot without asking.
            val before = session.state.value.snapshot!!.tabs.size
            api.createTab(pane.workspaceId, "from-test")
            withTimeout(10_000) { session.state.first { (it.snapshot?.tabs?.size ?: 0) > before } }

            val conn = session.awaitConnection()
            val stream = TerminalStream(conn, session.machine, pane.paneId, 60, 15, scope)
            stream.open()
            withTimeout(10_000) { stream.status.first { it == TerminalStream.Status.Live } }
            stream.send("echo stream-ok\r")
            withTimeout(10_000) {
                while (!synchronized(stream.emulator.lock) { stream.emulator.screenText() }.contains("stream-ok")) delay(100)
            }
            println(synchronized(stream.emulator.lock) { stream.emulator.screenText() })
            stream.close()

            // A pinned key that doesn't match must be refused.
            val wrong = machine().copy(hostKey = "SHA256:not-the-key")
            val failure = runCatching { SshConnector.connect(wrong, identity) }.exceptionOrNull()
            assertTrue(failure is com.makefunstuff.herdr.core.ssh.ConnectError.HostKeyChanged, "got $failure")

            // herdr session list works with the binary outside PATH.
            val sessions = conn.run(HerdrCommands.sessionList(session.machine))
            assertTrue(sessions.stdout.contains("\"sessions\""), sessions.stderr)

            session.stop()
            scope.cancel()
        }
    }
}
