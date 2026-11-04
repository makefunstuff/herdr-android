package com.makefunstuff.herdr.push

import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.push.Alert
import com.makefunstuff.herdr.core.push.PhoneQuiet
import com.makefunstuff.herdr.core.push.Presence
import com.makefunstuff.herdr.core.push.PushEvent
import com.makefunstuff.herdr.core.push.QuietHours
import com.makefunstuff.herdr.core.push.QuietSummary
import com.makefunstuff.herdr.core.push.Watch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** A watch fires once, as a notification, and is gone; silent while you are at a machine, held in quiet hours. */
class WatchWorkTest {
    private class Phone : PushPhone {
        var mode: PhoneQuiet? = null
        override val name = "Pixel"
        override fun distributors() = emptyList<String>()
        override fun label(pkg: String) = pkg
        override fun register() = true
        override fun unregister() {}
        override fun notificationsAllowed() = true
        override fun openNotificationSettings() {}
        override fun liveAllowed(): Boolean? = null
        override fun openLiveSettings() {}
        override fun getPushApp() {}
        override fun planChecks() {}
        override fun quietMode() = mode
        override fun planQuiet(at: Long?) {}
    }

    private class Sink : AlertSink {
        val log = mutableListOf<String>()
        override fun show(alert: Alert, quiet: Boolean, note: String?) { log += "show ${alert.title}${if (quiet) " quiet" else ""}" }
        override fun working(alert: Alert, words: String) {}
        override fun finished(alert: Alert, words: String) {}
        override fun summary(held: List<Alert>, summary: QuietSummary, quiet: Boolean) { log += "summary ${held.size}" }
        override fun cancel(tag: String) {}
    }

    private val dir = Files.createTempDirectory("watch").toFile()
    private val store = PushStore(java.io.File(dir, "push.json")).apply { change { it.copy(on = true) } }
    private val sink = Sink()
    private val inbox = Inbox(java.io.File(dir, "alerts.json"), sink)
    private val phone = Phone()
    private val quiet = QuietWork(CoroutineScope(Dispatchers.Unconfined), store.state, phone, inbox, { false })
    private val work = WatchWork(store, inbox, quiet)

    private fun agent(status: String, seq: Long = 7) = AgentInfo(
        name = "api", agent = "claude", agentStatusRaw = status, workspaceId = "w1", tabId = "t1", paneId = "w1:p1", stateChangeSeq = seq,
    )

    private val finishes = Watch("m1", Watch.What.AgentDone, paneId = "w1:p1", name = "api", seq = 7)

    @Test fun firesOnceAndIsGone() {
        work.add(finishes)
        work.events("m1", "devbox", listOf(PushEvent.Agent(agent("working"), "api")))
        assertTrue(sink.log.isEmpty())
        assertTrue(work.has(finishes.id))
        work.look("m1", "devbox", listOf(agent("done", seq = 9)))
        work.look("m1", "devbox", listOf(agent("done", seq = 9)))
        assertEquals(listOf("show api finished"), sink.log)
        assertTrue(store.state.value.watches.isEmpty())
    }

    @Test fun silentAtTheMachineAndHeldWhenQuiet() {
        work.add(finishes)
        quiet.presence.record("m1", "devbox", Presence(typedAgo = 5), System.currentTimeMillis())
        work.look("m1", "devbox", listOf(agent("done", seq = 9)))
        assertEquals(listOf("show api finished quiet"), sink.log)
        // Quiet hours: it waits for their end, as what finished does.
        store.change { it.copy(quiet = QuietHours(followPhone = true)) }
        phone.mode = PhoneQuiet.DoNotDisturb
        work.add(finishes.copy(seq = 9))
        work.look("m1", "devbox", listOf(agent("idle", seq = 11)))
        assertEquals(1, sink.log.size)
        assertEquals(1, inbox.heldCount.value)
    }

    @Test fun goneSaysSo() {
        work.add(finishes)
        work.look("m1", "devbox", emptyList())
        assertEquals(listOf("show api is gone"), sink.log)
        assertTrue(store.state.value.watches.isEmpty())
    }
}
