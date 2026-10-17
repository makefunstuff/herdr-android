package com.makefunstuff.herdr.push

import com.makefunstuff.herdr.core.push.Alert
import com.makefunstuff.herdr.core.push.AlertChange
import com.makefunstuff.herdr.core.push.PhoneQuiet
import com.makefunstuff.herdr.core.push.QuietHours
import com.makefunstuff.herdr.core.push.QuietSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Whether it is quiet now, what shows when it ends, and when the app looks again. */
class QuietWorkTest {
    private class Phone : PushPhone {
        var mode: PhoneQuiet? = null
        val alarms = mutableListOf<Long?>()
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
        override fun planQuiet(at: Long?) { alarms += at }
    }

    private class Sink : AlertSink {
        val log = mutableListOf<String>()
        override fun show(alert: Alert, quiet: Boolean, note: String?) { log += "show ${alert.tag}" }
        override fun working(alert: Alert, words: String) {}
        override fun finished(alert: Alert, words: String) {}
        override fun summary(held: List<Alert>, summary: QuietSummary, quiet: Boolean) { log += "summary ${held.size}" }
        override fun cancel(tag: String) {}
    }

    private val zone: ZoneId = ZoneOffset.UTC
    /** 2026-10-12 is a Monday. */
    private fun at(day: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, 11 + day, h, m).atZone(zone).toInstant().toEpochMilli()

    private val settings = MutableStateFlow(PushState(on = true, quiet = QuietHours(schedule = true, start = 22 * 60, end = 7 * 60)))
    private val phone = Phone()
    private val sink = Sink()
    private val inbox = Inbox(Files.createTempDirectory("quiet").resolve("alerts.json").toFile(), sink)
    private val quiet = QuietWork(CoroutineScope(Dispatchers.Unconfined), settings, phone, inbox, { false }, { zone })

    private fun finished(pane: String) = AlertChange.Show(Alert("agent:m1:$pane", "done#1", Alert.Kind.Finished, "m1", "$pane finished", "", paneId = pane))

    @Test fun theScheduleHoldsUntilItEnds() {
        assertTrue(quiet.check(at(1, 23)))
        assertEquals("Quiet until 07:00", quiet.reason.value)
        // The alarm rings when it ends.
        assertEquals(at(2, 7), phone.alarms.last())
        inbox.apply(listOf(finished("p1"), finished("p2")), quiet = false, hold = quiet.check(at(1, 23, 30)))
        assertEquals(emptyList<String>(), sink.log)

        assertFalse(quiet.check(at(2, 7)))
        assertNull(quiet.reason.value)
        assertEquals(listOf("summary 2"), sink.log)
        // And again when the next night begins.
        assertEquals(at(2, 22), phone.alarms.last())
    }

    @Test fun thePhonesModeIsLookedAtEveryQuarter() {
        settings.value = PushState(on = true, quiet = QuietHours(followPhone = true))
        phone.mode = PhoneQuiet.DoNotDisturb
        assertTrue(quiet.check(at(1, 13, 5)))
        assertEquals(at(1, 13, 15), phone.alarms.last())
        // Asked again within the quarter: the alarm stays as it is.
        quiet.check(at(1, 13, 10))
        assertEquals(1, phone.alarms.size)
        inbox.apply(listOf(finished("p1")), quiet = false, hold = true)

        phone.mode = null
        assertFalse(quiet.check(at(1, 13, 15)))
        assertEquals(listOf("show agent:m1:p1"), sink.log)
        assertNull(phone.alarms.last())
    }

    @Test fun offHoldsNothing() {
        settings.value = PushState(on = true, quiet = QuietHours())
        phone.mode = PhoneQuiet.Driving
        assertFalse(quiet.check(at(1, 23)))
        // Notifications while closed turned off: nothing to hold either.
        settings.value = PushState(on = false, quiet = QuietHours(followPhone = true, schedule = true))
        assertFalse(quiet.check(at(1, 23)))
        assertTrue(phone.alarms.all { it == null })
    }
}
