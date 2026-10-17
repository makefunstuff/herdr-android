package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.push.Alert
import com.makefunstuff.herdr.core.push.PhoneQuiet
import com.makefunstuff.herdr.core.push.QuietHours
import com.makefunstuff.herdr.core.push.QuietSummary
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuietTest {
    /** 2026-10-12 is a Monday. */
    private fun at(day: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, 11 + day, h, m)

    private val nights = QuietHours(schedule = true, start = 22 * 60, end = 7 * 60)

    @Test
    fun overnight() {
        assertFalse(nights.scheduled(at(1, 21, 59)))
        assertTrue(nights.scheduled(at(1, 22, 0)))
        assertTrue(nights.scheduled(at(2, 6, 59)))
        assertFalse(nights.scheduled(at(2, 7, 0)))
        assertFalse(nights.copy(schedule = false).scheduled(at(1, 23)))
    }

    @Test
    fun theDayIsTheOneItStartsOn() {
        val weeknights = nights.copy(days = (1..5).toSet())
        // Friday night runs into Saturday morning; Saturday night isn't one.
        assertTrue(weeknights.scheduled(at(5, 23)))
        assertTrue(weeknights.scheduled(at(6, 6)))
        assertFalse(weeknights.scheduled(at(6, 23)))
        assertFalse(weeknights.scheduled(at(7, 6)))
        // Sunday night isn't one, so Monday morning isn't quiet either; Monday night is.
        assertFalse(weeknights.scheduled(at(1, 6)))
        assertTrue(weeknights.scheduled(at(1, 22)))
        assertFalse(nights.copy(days = emptySet()).scheduled(at(1, 23)))
    }

    @Test
    fun withinADayAndAllDay() {
        val lunch = QuietHours(schedule = true, start = 12 * 60, end = 13 * 60 + 30)
        assertFalse(lunch.scheduled(at(1, 11, 59)))
        assertTrue(lunch.scheduled(at(1, 13, 29)))
        assertFalse(lunch.scheduled(at(1, 13, 30)))
        val weekends = QuietHours(schedule = true, start = 0, end = 0, days = setOf(6, 7))
        assertTrue(weekends.scheduled(at(6, 0)))
        assertTrue(weekends.scheduled(at(7, 23, 59)))
        assertFalse(weekends.scheduled(at(1, 0)))
        assertEquals("All day, Sat, Sun", weekends.scheduleLabel)
    }

    @Test
    fun nextChange() {
        assertEquals(at(1, 22), nights.nextChange(at(1, 9)))
        assertEquals(at(2, 7), nights.nextChange(at(1, 22)))
        assertEquals(at(2, 7), nights.nextChange(at(2, 3)))
        val weeknights = nights.copy(days = (1..5).toSet())
        // Saturday morning ends Friday's night; the next starts on Monday.
        assertEquals(at(6, 7), weeknights.nextChange(at(6, 1)))
        assertEquals(at(8, 22), weeknights.nextChange(at(6, 7)))
        assertNull(nights.copy(schedule = false).nextChange(at(1, 9)))
        assertNull(nights.copy(days = emptySet()).nextChange(at(1, 9)))
        // All day on Saturday and Sunday: from Saturday 00:00 to Monday 00:00.
        val weekends = QuietHours(schedule = true, start = 0, end = 0, days = setOf(6, 7))
        assertEquals(at(6, 0), weekends.nextChange(at(3, 12)))
        assertEquals(at(8, 0), weekends.nextChange(at(6, 12)))
    }

    @Test
    fun reasons() {
        val both = nights.copy(followPhone = true)
        assertEquals("Driving", both.reason(at(1, 12), PhoneQuiet.Driving))
        assertEquals("Quiet until 07:00", both.reason(at(1, 23), null))
        assertNull(both.reason(at(1, 12), null))
        // The phone's mode counts only when asked to.
        assertNull(nights.reason(at(1, 12), PhoneQuiet.DoNotDisturb))
    }

    @Test
    fun labels() {
        assertEquals("Off", QuietHours().label)
        assertEquals("Bedtime, Driving or Do Not Disturb", QuietHours(followPhone = true).label)
        assertEquals("Bedtime, Driving or Do Not Disturb · 22:00 to 07:00, Mon–Fri", nights.copy(followPhone = true, days = (1..5).toSet()).label)
        assertEquals("22:00 to 07:00, Mon, Wed", nights.copy(days = setOf(3, 1)).scheduleLabel)
        assertEquals("09:05", QuietHours.clock(9 * 60 + 5))
    }

    private fun alert(kind: Alert.Kind, title: String, text: String = "Fixed it", pane: String = "p1") =
        Alert("agent:m1:$pane:$title", "s", kind, "m1", title, text, paneId = pane)

    @Test
    fun onlyWhatCanWaitIsHeld() {
        assertTrue(QuietHours.holds(alert(Alert.Kind.Finished, "api finished")))
        assertFalse(QuietHours.holds(alert(Alert.Kind.NeedsYou, "api needs you")))
        assertFalse(QuietHours.holds(alert(Alert.Kind.Test, "Notifications from devbox work")))
    }

    @Test
    fun summary() {
        val two = QuietSummary.of(listOf(alert(Alert.Kind.Finished, "api finished"), alert(Alert.Kind.Finished, "web finished", "Ready for the next prompt")))
        assertEquals("2 agents finished while it was quiet", two.title)
        assertEquals("api finished, web finished", two.text)
        assertEquals(listOf("api finished: Fixed it", "web finished: Ready for the next prompt"), two.lines)

        val many = QuietSummary.of((1..9).map { alert(Alert.Kind.Finished, "a$it finished") })
        assertEquals(QuietSummary.LINES, many.lines.size)
        assertEquals("and 4 more", many.lines.last())

        val mixed = QuietSummary.of(listOf(alert(Alert.Kind.Finished, "api finished"), alert(Alert.Kind.NeedsYou, "AND-7 ready to approve", pane = "")))
        assertEquals("2 notifications from quiet hours", mixed.title)
    }
}
