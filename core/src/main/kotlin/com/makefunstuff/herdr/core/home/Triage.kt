package com.makefunstuff.herdr.core.home

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * An agent put out of the way: off the lists until [until] (ms), or, when that is null, until its
 * state changes from the one it had when snoozed ([seq], herdr's state_change_seq).
 */
@Serializable
data class Snooze(val until: Long? = null, val seq: Long = 0) {
    /** Whether it still holds for [a] at [now]. */
    fun holds(a: HomeAgent, now: Long): Boolean = if (until != null) now < until else a.agent.stateChangeSeq == seq

    /** As a row says it: "until it changes", "until 18:00", "until tomorrow 09:00", "until Mon 09:00". */
    fun label(now: Long, zone: ZoneId): String {
        val at = until ?: return "until it changes"
        val day = Instant.ofEpochMilli(at).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val time = day.format(HM)
        return when (ChronoUnit.DAYS.between(today, day.toLocalDate())) {
            0L -> "until $time"
            1L -> "until tomorrow $time"
            else -> "until ${day.format(DAY)} $time"
        }
    }

    companion object {
        private val HM = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
        private val DAY = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)

        /** The snoozes the sheet offers at [now]: until it changes, for an hour, for three, until the next morning at nine. */
        fun choices(a: HomeAgent, now: Long, zone: ZoneId): List<Pair<String, Snooze>> {
            val morning = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atTime(LocalTime.of(9, 0)).atZone(zone)
            return listOf(
                "Until it changes" to Snooze(seq = a.agent.stateChangeSeq),
                "For an hour" to Snooze(until = now + HOUR),
                "For 3 hours" to Snooze(until = now + 3 * HOUR),
                "Until tomorrow ${morning.format(HM)}" to Snooze(until = morning.toInstant().toEpochMilli()),
            )
        }

        private const val HOUR = 3_600_000L
    }
}

/**
 * The phone's own triage of the Agents list, by [HomeAgent.key]: agents pinned on top (in the order
 * pinned), agents snoozed out of the way, and agents muted (no notifications from them). Seen marks
 * are kept apart (SeenStore), as before. Plain data, so the rules are tested without a phone.
 */
@Serializable
data class Triage(
    val pins: List<String> = emptyList(),
    val snoozes: Map<String, Snooze> = emptyMap(),
    val muted: Set<String> = emptySet(),
    /** Whether the person has triaged anything yet: until then the list says how (swipe right, swipe left). */
    val used: Boolean = false,
) {
    fun pinned(key: String): Boolean = key in pins

    /** Pinned on top; a snooze it had ends. */
    fun pin(key: String): Triage = copy(pins = pins - key + key, snoozes = snoozes - key)

    fun unpin(key: String): Triage = copy(pins = pins - key)

    /** Out of the way as [s] says; a pin it had ends (a pinned agent in the snoozed list would be neither). */
    fun snooze(key: String, s: Snooze): Triage = copy(snoozes = snoozes + (key to s), pins = pins - key)

    fun wake(key: String): Triage = copy(snoozes = snoozes - key)

    /** The snooze on [a] that still holds at [now], or null. */
    fun snoozeOf(a: HomeAgent, now: Long): Snooze? = snoozes[a.key]?.takeIf { it.holds(a, now) }

    fun mute(key: String): Triage = copy(muted = muted + key)

    fun unmute(key: String): Triage = copy(muted = muted - key)

    /**
     * What is worth keeping: snoozes that still hold, and nothing of a pane that is gone. [agents]: every
     * agent on the machines' last snapshots; [read]: the machines those come from. An agent on a machine
     * not read now (offline since the app started) is kept as it is; one on a machine taken out of the app
     * ([machines], every machine the app has) goes.
     */
    fun keep(agents: List<HomeAgent>, read: Set<String>, machines: Set<String>, now: Long): Triage {
        val byKey = agents.associateBy { it.key }
        fun there(key: String): Boolean {
            if (key in byKey) return true
            val machine = machines.firstOrNull { key.startsWith("$it/") } ?: return false
            return machine !in read
        }
        return copy(
            pins = pins.filter(::there),
            snoozes = snoozes.filter { (k, s) -> there(k) && byKey[k]?.let { s.holds(it, now) } != false },
            muted = muted.filter(::there).toSet(),
        )
    }

    /** Whether notifications about [machineId]'s pane [paneId] are muted. */
    fun mutes(machineId: String, paneId: String): Boolean = paneId.isNotEmpty() && "$machineId/$paneId" in muted
}
