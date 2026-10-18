package com.makefunstuff.herdr.push

import com.makefunstuff.herdr.core.push.PresenceBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Quiet hours on the phone ([com.makefunstuff.herdr.core.push.QuietHours]): whether it is quiet now,
 * and when it ends, what was held shows as one. Asked as each push or look arrives, when the phone
 * leaves Do Not Disturb or car mode while the app runs, and by an alarm at the schedule's end (also
 * every quarter of an hour while the phone is quiet, since a closed app isn't told when that ends).
 * Also whether you are at one of your machines ([presence]), when notifications then keep silent.
 */
class QuietWork(
    private val scope: CoroutineScope,
    /** The settings ([PushStore.state]). */
    private val settings: StateFlow<PushState>,
    private val phone: PushPhone,
    private val inbox: Inbox,
    private val foreground: () -> Boolean,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val _reason = MutableStateFlow<String?>(null)
    /** Why it is quiet now, in words for the settings; null when it isn't. As of the last [check]. */
    val reason: StateFlow<String?> = _reason.asStateFlow()
    @Volatile private var planned: Long? = null
    /** Who is at which machine, as the machines' pushes and the app's looks said. */
    val presence = PresenceBook()

    /** Whether notifications keep silent now because you are at a machine (and the rules say so). */
    fun atMachine(now: Long = System.currentTimeMillis()): Boolean = settings.value.rules.holdAtMachine && presence.present(now)

    /** Silent now: the app is on screen, or you are at a machine. */
    fun silent(now: Long = System.currentTimeMillis()): Boolean = foreground() || atMachine(now)

    fun start() {
        // Turned on, off or changed: what it means now, at once (also releasing what was held).
        scope.launch {
            settings.map { it.on to it.quiet }.distinctUntilChanged().collect { check() }
        }
    }

    /**
     * Whether it is quiet at [now]. When it isn't, what was held shows; with notifications while closed
     * turned off, it is dropped instead. Also sets the alarm for the next look.
     */
    @Synchronized
    fun check(now: Long = System.currentTimeMillis()): Boolean {
        val st = settings.value
        val hours = st.quiet
        val local = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone())
        val mode = if (st.on && hours.followPhone) phone.quietMode() else null
        val why = if (st.on) hours.reason(local, mode) else null
        _reason.value = why
        when {
            // Turned off: nothing shows from now on, also not what was held.
            !st.on -> inbox.dropHeld()
            why == null -> inbox.release(quiet = foreground())
        }
        plan(if (st.on) nextLook(hours.nextChange(local), mode != null, now) else null)
        return why != null
    }

    /**
     * The next look: where the schedule turns, and while the phone is quiet the next quarter of an hour
     * (fixed quarters, so looking again within one doesn't move the alarm).
     */
    private fun nextLook(change: LocalDateTime?, phoneQuiet: Boolean, now: Long): Long? {
        val turn = change?.atZone(zone())?.toInstant()?.toEpochMilli()
        val quarter = if (phoneQuiet) (now / QUARTER + 1) * QUARTER else null
        return listOfNotNull(turn, quarter).minOrNull()
    }

    private fun plan(at: Long?) {
        if (at == planned) return
        planned = at
        phone.planQuiet(at)
    }

    /** The alarm rang, or the phone restarted (which drops alarms): set it again. */
    fun replan() {
        planned = null
        check()
    }

    private companion object {
        const val QUARTER = 15L * 60 * 1000
    }
}
