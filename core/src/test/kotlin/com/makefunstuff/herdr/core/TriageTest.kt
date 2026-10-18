package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.api.AgentInfo
import com.makefunstuff.herdr.core.home.Home
import com.makefunstuff.herdr.core.home.HomeAgent
import com.makefunstuff.herdr.core.home.Snooze
import com.makefunstuff.herdr.core.home.Triage
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TriageTest {
    private val now = LocalDateTime.of(2026, 10, 17, 14, 30).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun agent(pane: String, status: String, seq: Long = 1, completion: Long? = null, machine: String = "devbox") = HomeAgent(
        machine, machine,
        AgentInfo(name = "claude", agent = "claude", agentStatusRaw = status, workspaceId = pane.substringBefore(':'), tabId = "t", paneId = pane, stateChangeSeq = seq, completionSeq = completion),
        "src › main",
    )

    private val eval = agent("w1:p1", "working", seq = 3)
    private val done = agent("w2:p1", "done", seq = 5, completion = 5)
    private val idle = agent("w3:p1", "idle", seq = 2)
    private val asks = agent("w4:p1", "blocked", seq = 8)
    private val all = listOf(eval, done, idle, asks)

    @Test
    fun pinnedStayOnTopInTheOrderPinned() {
        val t = Triage().pin(idle.key).pin(eval.key)
        val home = Home.of(all, emptyList(), triage = t, now = now)
        assertEquals(listOf("w3:p1", "w1:p1"), home.pinned.map { it.agent.agent.paneId })
        assertTrue(home.pinned.all { it.pinned })
        assertTrue(home.working.isEmpty())
        assertTrue(home.idle.isEmpty())
        // Pinning again moves it last; unpinning puts it back with its state.
        assertEquals(listOf(eval.key, idle.key), t.pin(idle.key).pins)
        assertEquals(listOf("w1:p1"), Home.of(all, emptyList(), triage = t.unpin(eval.key), now = now).working.map { it.agent.agent.paneId })
    }

    @Test
    fun aQuestionShowsWhateverItsTriage() {
        val t = Triage().pin(asks.key).mute(asks.key)
        val home = Home.of(all, emptyList(), triage = t, now = now)
        assertEquals(listOf("agent/${asks.key}"), home.needsYou.map { it.key })
        assertTrue(home.pinned.isEmpty())
    }

    @Test
    fun snoozedUntilItChanges() {
        val t = Triage().snooze(done.key, Snooze(seq = done.agent.stateChangeSeq))
        val home = Home.of(all, emptyList(), triage = t, now = now)
        assertEquals(listOf("w2:p1"), home.snoozed.map { it.agent.agent.paneId })
        assertTrue(home.done.isEmpty())
        assertEquals("until it changes", home.snoozed.single().snooze?.label(now, ZoneOffset.UTC))
        // It works again: the snooze is over, and keep drops it.
        val again = agent("w2:p1", "working", seq = 6)
        val later = Home.of(listOf(again), emptyList(), triage = t, now = now)
        assertTrue(later.snoozed.isEmpty())
        assertEquals(listOf("w2:p1"), later.working.map { it.agent.agent.paneId })
        assertTrue(t.keep(listOf(again), read = setOf("devbox"), machines = setOf("devbox"), now = now).snoozes.isEmpty())
    }

    @Test
    fun snoozedUntilATime() {
        val choices = Snooze.choices(eval, now, ZoneOffset.UTC)
        assertEquals(listOf("Until it changes", "For an hour", "For 3 hours", "Until tomorrow 09:00"), choices.map { it.first })
        val hour = choices[1].second
        assertEquals("until 15:30", hour.label(now, ZoneOffset.UTC))
        assertEquals("until tomorrow 09:00", choices[3].second.label(now, ZoneOffset.UTC))
        assertEquals("until Mon 09:00", Snooze(until = now + 2 * 86_400_000L - 5 * 3_600_000L - 1_800_000L).label(now, ZoneOffset.UTC))
        val t = Triage().snooze(eval.key, hour)
        assertEquals(listOf("w1:p1"), Home.of(all, emptyList(), triage = t, now = now).snoozed.map { it.agent.agent.paneId })
        // A change of state doesn't end a snooze for a time; the time does.
        val changed = agent("w1:p1", "done", seq = 9, completion = 9)
        assertEquals(1, Home.of(listOf(changed), emptyList(), triage = t, now = now + 60_000).snoozed.size)
        val after = Home.of(listOf(changed), emptyList(), triage = t, now = now + 3_600_000)
        assertTrue(after.snoozed.isEmpty())
        assertTrue(after.done.single().new)
    }

    @Test
    fun inTheSmallHoursTheMorningIsTodays() {
        val night = LocalDateTime.of(2026, 10, 18, 1, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val (label, s) = Snooze.choices(eval, night, ZoneOffset.UTC).last()
        assertEquals("Until 09:00", label)
        assertEquals(night + 8 * 3_600_000L, s.until)
        // From five on, it is the next day's.
        val early = LocalDateTime.of(2026, 10, 18, 6, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals("Until tomorrow 09:00", Snooze.choices(eval, early, ZoneOffset.UTC).last().first)
    }

    @Test
    fun pinAndSnoozeExcludeEachOther() {
        val t = Triage().pin(eval.key).snooze(eval.key, Snooze(seq = 3))
        assertFalse(t.pinned(eval.key))
        val back = t.pin(eval.key)
        assertNull(back.snoozes[eval.key])
        assertTrue(back.pinned(eval.key))
    }

    @Test
    fun mutedPanes() {
        val t = Triage().mute(done.key)
        assertTrue(t.mutes("devbox", "w2:p1"))
        assertFalse(t.mutes("devbox", "w1:p1"))
        assertFalse(t.mutes("devbox", ""))
        assertTrue(Home.of(all, emptyList(), triage = t, now = now).done.single().muted)
        assertFalse(t.unmute(done.key).mutes("devbox", "w2:p1"))
    }

    @Test
    fun keepDropsWhatIsGone() {
        val gpu = agent("w1:p1", "working", machine = "gpu")
        val t = Triage(
            pins = listOf(eval.key, "devbox/w9:p1", gpu.key, "old/w1:p1"),
            snoozes = mapOf(idle.key to Snooze(until = now - 1), done.key to Snooze(until = now + 1)),
            muted = setOf("devbox/w9:p1", gpu.key),
        )
        // devbox was read (w9 is gone); gpu wasn't (offline): its marks stay; "old" is out of the app.
        val kept = t.keep(listOf(eval, done, idle), read = setOf("devbox"), machines = setOf("devbox", "gpu"), now = now)
        assertEquals(listOf(eval.key, gpu.key), kept.pins)
        assertEquals(setOf(done.key), kept.snoozes.keys)
        assertEquals(setOf(gpu.key), kept.muted)
    }
}
