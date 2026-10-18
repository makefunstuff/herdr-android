package com.makefunstuff.herdr.push

import com.makefunstuff.herdr.core.board.BoardTasks
import com.makefunstuff.herdr.core.push.Alert
import com.makefunstuff.herdr.core.push.PhoneQuiet
import com.makefunstuff.herdr.core.push.Presence
import com.makefunstuff.herdr.core.push.PushEvent
import com.makefunstuff.herdr.core.push.QuietHours
import com.makefunstuff.herdr.core.push.QuietSummary
import com.makefunstuff.herdr.core.push.Watch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
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

    private fun task(stage: String, state: String = "working") =
        BoardTasks.task(Json.parseToJsonElement("""{"id":"api/3","title":"Jobs","state":"$state","stage":"$stage"}""").jsonObject, "API-3")!!

    private val checks = Watch("m1", Watch.What.ChecksPass, taskId = "api/3", name = "API-3")

    @Test fun firesOnceAndIsGone() {
        work.add(checks)
        work.events("m1", "devbox", listOf(PushEvent.Task(task("checking"))))
        assertTrue(sink.log.isEmpty())
        assertTrue(work.has(checks.id))
        work.look("m1", "devbox", null, listOf(task("reviewing")))
        work.look("m1", "devbox", null, listOf(task("reviewing")))
        assertEquals(listOf("show API-3's checks passed"), sink.log)
        assertTrue(store.state.value.watches.isEmpty())
    }

    @Test fun silentAtTheMachineAndHeldWhenQuiet() {
        work.add(checks)
        quiet.presence.record("m1", "devbox", Presence(typedAgo = 5), System.currentTimeMillis())
        work.look("m1", "devbox", null, listOf(task("reviewing")))
        assertEquals(listOf("show API-3's checks passed quiet"), sink.log)
        // Quiet hours: it waits for their end, as what finished does.
        store.change { it.copy(quiet = QuietHours(followPhone = true)) }
        phone.mode = PhoneQuiet.DoNotDisturb
        work.add(Watch("m1", Watch.What.TaskDone, taskId = "api/3", name = "API-3"))
        work.look("m1", "devbox", null, listOf(task("done", state = "done")))
        assertEquals(1, sink.log.size)
        assertEquals(1, inbox.heldCount.value)
    }

    @Test fun goneEndsWithoutAWord() {
        work.add(checks)
        work.look("m1", "devbox", null, emptyList())
        assertTrue(sink.log.isEmpty())
        assertTrue(store.state.value.watches.isEmpty())
    }
}
