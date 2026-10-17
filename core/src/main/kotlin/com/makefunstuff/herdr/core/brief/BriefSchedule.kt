package com.makefunstuff.herdr.core.brief

import com.makefunstuff.herdr.core.push.QuietHours
import kotlinx.serialization.Serializable
import java.time.LocalDateTime

/**
 * When the brief comes as a notification: at each of [times] on [days], and whether it is read aloud
 * then. Plain data, so the rules are tested without a phone.
 */
@Serializable
data class BriefSchedule(
    val on: Boolean = false,
    /** Minutes after midnight on the phone's clock, earliest first. */
    val times: List<Int> = listOf(8 * 60),
    /** 1 (Monday) to 7 (Sunday). */
    val days: Set<Int> = QuietHours.ALL_DAYS,
    /** Whether the brief is read aloud as it comes. */
    val readAloud: ReadAloud = ReadAloud.InCar,
) {
    enum class ReadAloud(val label: String) {
        Never("Never"),
        /** While the phone is in car mode (Android Auto, or a car dock). */
        InCar("In the car"),
        Always("Always"),
    }

    /** The next brief after [at] (to the minute), or null when none is set. */
    fun next(at: LocalDateTime): LocalDateTime? {
        if (!on || times.isEmpty() || days.isEmpty()) return null
        val midnight = at.toLocalDate().atStartOfDay()
        return (0L..7L).asSequence()
            .flatMap { d -> times.sorted().asSequence().map { midnight.plusDays(d).plusMinutes(it.toLong()) } }
            .firstOrNull { it.isAfter(at) && it.dayOfWeek.value in days }
    }

    /** As the settings show it: "Off", "08:00", "08:00 and 17:30, Mon–Fri". */
    val label: String get() {
        if (!on) return "Off"
        if (times.isEmpty()) return "No time set"
        val clocks = times.sorted().map(QuietHours::clock)
        val at = if (clocks.size == 1) clocks.single() else clocks.dropLast(1).joinToString(", ") + " and " + clocks.last()
        val which = QuietHours.daysLabel(days)
        return if (which.isEmpty()) at else "$at, $which"
    }

    /** [times] with [minutes] added (once), earliest first; at most [MAX_TIMES]. */
    fun withTime(minutes: Int): BriefSchedule = copy(times = (times + minutes).distinct().sorted().take(MAX_TIMES))

    companion object {
        const val MAX_TIMES = 4

        /** The brief's window starts where the person last looked, but never more than a week back (what herdr-push keeps). */
        const val MAX_WINDOW_MS = 7L * 24 * 3600 * 1000

        /** Before the person ever looked: the last day. */
        const val FIRST_WINDOW_MS = 24L * 3600 * 1000

        /** Where a brief at [now] starts, given when the person last looked ([seenAt], 0 never). */
        fun since(seenAt: Long, now: Long): Long = when {
            seenAt <= 0 || seenAt > now -> now - FIRST_WINDOW_MS
            else -> maxOf(seenAt, now - MAX_WINDOW_MS)
        }
    }
}
