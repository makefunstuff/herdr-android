package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.api.ApiClient
import com.makefunstuff.herdr.core.api.ApiConnection
import com.makefunstuff.herdr.core.api.HerdrApi
import com.makefunstuff.herdr.core.api.HerdrApiException
import com.makefunstuff.herdr.core.api.PaneDestination
import com.makefunstuff.herdr.core.api.moveIndex
import com.makefunstuff.herdr.core.api.params
import com.makefunstuff.herdr.core.host.WORKSPACE_METADATA_EVENT
import com.makefunstuff.herdr.core.ssh.Duplex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import java.io.OutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The management calls against a real herdr, straight over its API socket. Runs only when
 * HERDR_LIVE_SOCKET is the socket of a throwaway session (`herdr --session test server`): it
 * creates, moves and closes workspaces there.
 */
class LiveManageTest {
    private val socket = System.getenv("HERDR_LIVE_SOCKET")

    private fun open(): Duplex {
        val ch = SocketChannel.open(StandardProtocolFamily.UNIX)
        ch.connect(UnixDomainSocketAddress.of(socket))
        // Not Channels.newInputStream/newOutputStream: those share one lock, so a blocked read stalls every write.
        return object : Duplex {
            override val input: InputStream = object : InputStream() {
                override fun read(): Int {
                    val one = ByteArray(1)
                    return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int = ch.read(ByteBuffer.wrap(b, off, len))
            }
            override val output: OutputStream = object : OutputStream() {
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

                override fun write(b: ByteArray, off: Int, len: Int) {
                    val buf = ByteBuffer.wrap(b, off, len)
                    while (buf.hasRemaining()) ch.write(buf)
                }
            }
            override fun close() = ch.close()
        }
    }

    @Test
    fun managesWorkspacesTabsPanesAndAgents() {
        if (socket == null) return
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val client = ApiClient { open() }
            val api = HerdrApi(client)
            val duplex = open()
            val events = ApiConnection(duplex.input, duplex.output, scope, onClose = { duplex.close() })
            // Every event name the app follows must be one herdr accepts, or the whole subscription fails.
            events.subscribe(HerdrApi.subscriptionParams(emptyList()))

            api.createWorkspace("live-a", "/tmp")
            api.createWorkspace("live-b", null)
            var snap = api.snapshot()
            val a = snap.workspaces.last { it.label == "live-a" }
            val b = snap.workspaces.last { it.label == "live-b" }

            api.renameWorkspace(a.workspaceId, "live-a2")
            withTimeout(5000) {
                events.events.first { it["event"]?.jsonPrimitive?.content == "workspace_renamed" }
            }
            assertEquals("live-a2", api.workspace(a.workspaceId).label)

            // Move b in front of a, then a block of both to the end.
            val order = snap.workspaces.map { it.workspaceId }
            api.moveWorkspace(b.workspaceId, moveIndex(order, b.workspaceId, order.indexOf(a.workspaceId))!!)
            snap = api.snapshot()
            val ids = snap.workspaces.map { it.workspaceId }
            assertEquals(ids.indexOf(a.workspaceId) - 1, ids.indexOf(b.workspaceId))
            api.moveWorkspaces(listOf(a.workspaceId, b.workspaceId), null)
            assertEquals(listOf(a.workspaceId, b.workspaceId), api.snapshot().workspaces.takeLast(2).map { it.workspaceId })
            api.focusWorkspace(b.workspaceId)
            assertTrue(api.workspace(b.workspaceId).focused)

            // Tabs.
            api.createTab(a.workspaceId, "two")
            snap = api.snapshot()
            val tabs = snap.tabs.filter { it.workspaceId == a.workspaceId }
            val two = tabs.last()
            api.renameTab(two.tabId, "zwei")
            api.moveTab(two.tabId, moveIndex(tabs.map { it.tabId }, two.tabId, 0)!!)
            snap = api.snapshot()
            assertEquals(two.tabId, snap.tabs.first { it.workspaceId == a.workspaceId }.tabId)
            assertEquals("zwei", snap.tab(two.tabId)!!.label)
            api.focusTab(two.tabId)

            // Panes, in a's first tab.
            val firstTab = tabs.first().tabId
            val p1 = snap.panes.first { it.tabId == firstTab }.paneId
            api.splitPane(p1, "right")
            snap = api.snapshot()
            val p2 = snap.panes.first { it.tabId == firstTab && it.paneId != p1 }.paneId
            api.renamePane(p1, "shell")
            assertEquals("shell", api.snapshot().pane(p1)!!.label)
            api.renamePane(p1, null)
            assertEquals(null, api.snapshot().pane(p1)!!.label)
            assertEquals(null, api.swapPane(p1, direction = "right").refusal)
            api.focusPane(p1)
            assertEquals(null, api.focusDirection(p1, "left").refusal) // p1 now sits right of p2
            assertEquals("There's no pane in that direction.", api.focusDirection(p1, "up").refusal)
            assertEquals(true, api.zoomPane(p1).zoomed)
            assertEquals("That tab has a zoomed pane. Unzoom it first.", api.movePane(p2, PaneDestination.Tab(two.tabId)).refusal)
            assertEquals(false, api.zoomPane(p1, "off").zoomed)
            assertEquals(null, api.movePane(p2, PaneDestination.Tab(two.tabId, "down")).refusal)
            assertEquals(two.tabId, api.snapshot().pane(p2)!!.tabId)
            api.clearPane(p1)
            val proc = api.processInfo(p1)
            assertEquals(p1, proc.paneId)
            assertTrue(proc.processes.isNotEmpty(), "expected the shell in the foreground")

            // Agents: report one into p1, as an integration would.
            client.call("pane.report_agent", params("pane_id" to p1, "source" to "live-test", "agent" to "claude", "state" to "working"))
            api.renameAgent(p1, "live-rev")
            assertEquals("live-rev", api.agent("live-rev").name)
            api.renameAgent(p1, null)
            assertEquals(null, api.agent(p1).name)
            api.focusAgent(p1)
            val why = api.explainAgent(p1)
            assertTrue(why.summary.startsWith("herdr reads claude as"), why.summary)
            assertTrue(why.rules.isNotEmpty())
            // agent.send_keys only reaches agents herdr started itself; a reported one is refused in
            // a plain sentence, which is what the app shows the person.
            val notReady = assertFailsWith<HerdrApiException> { api.agentSendKeys(p1, listOf("esc")) }
            assertEquals("agent_not_ready", notReady.code)
            assertEquals("Agent $p1 is not an active named agent.", notReady.message)

            // Metadata shows up on the workspace.
            client.call(
                "workspace.report_metadata",
                params("workspace_id" to a.workspaceId, "source" to "live-test", "tokens" to params("branch" to "main")),
            )
            // The app reloads cached metadata on this pushed event, so its name is a contract.
            withTimeout(5000) {
                events.events.first { it["event"]?.jsonPrimitive?.content == WORKSPACE_METADATA_EVENT }
            }
            assertEquals("main", api.workspace(a.workspaceId).tokens["branch"])

            val shown = api.showNotification("From the live test", "hello")
            println("notification: ${shown.message}")

            val e = assertFailsWith<HerdrApiException> { api.closeTab("w999:t1") }
            println("plain error: ${e.message}")
            assertTrue(e.message!!.first().isUpperCase())

            api.closeWorkspace(a.workspaceId)
            api.closeWorkspace(b.workspaceId)
            assertTrue(api.snapshot().workspaces.none { it.workspaceId in setOf(a.workspaceId, b.workspaceId) })
            events.close()
            scope.cancel()
        }
    }
}
