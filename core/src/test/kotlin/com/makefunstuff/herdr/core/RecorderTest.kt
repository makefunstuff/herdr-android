package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.chat.Recorder
import com.makefunstuff.herdr.core.chat.Recorder.Clip
import com.makefunstuff.herdr.core.chat.Recorder.Problem
import com.makefunstuff.herdr.core.chat.Recorder.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The voice message's states in the composer, against a phone that records what the test says. */
class RecorderTest {
    private class FakeMic(var allowed: Boolean = true) : Recorder.Mic {
        val calls = mutableListOf<String>()
        var opens = true
        var file: String? = "/phone/voice/1.ogg"
        var loud = 0f
        override fun allowed() = allowed
        override fun ask() { calls += "ask" }
        override fun settings() { calls += "settings" }
        override fun start(): Boolean { calls += "start"; return opens }
        override fun level() = loud
        override fun stop(): String? { calls += "stop"; return file }
        override fun cancel() { calls += "cancel" }
    }

    private var now = 1_000L
    private val mic = FakeMic()
    private val clips = mutableListOf<Clip>()
    private val r = Recorder(mic, { now }) { clips += it }

    @Test fun holdSpeakAndLetGoGivesTheRecording() {
        r.down()
        val rec = assertIs<State.Recording>(r.state.value)
        assertTrue(rec.held)
        assertEquals(listOf("start"), mic.calls)
        now += 7_000
        r.up(heldFor = 7_000)
        assertEquals(State.Idle, r.state.value)
        assertEquals(listOf("start", "stop"), mic.calls)
        assertEquals(listOf(Clip("/phone/voice/1.ogg", 7_000)), clips)
    }

    @Test fun aQuickTapKeepsRecordingUntilTheNextTap() {
        r.down()
        r.up(heldFor = 120)
        assertEquals(false, assertIs<State.Recording>(r.state.value).held)
        // Letting go again does nothing; the next tap finishes.
        r.up(heldFor = 50)
        assertIs<State.Recording>(r.state.value)
        now += 3_000
        r.down()
        assertEquals(listOf(Clip("/phone/voice/1.ogg", 3_000)), clips)
    }

    @Test fun slidingAwayAndLettingGoCancels() {
        r.down()
        r.slide(cancel = true)
        assertTrue((r.state.value as State.Recording).cancelling)
        // Back again: letting go would finish.
        r.slide(cancel = false)
        assertEquals(false, (r.state.value as State.Recording).cancelling)
        r.slide(cancel = true)
        now += 2_000
        r.up(heldFor = 2_000)
        assertEquals(State.Idle, r.state.value)
        assertEquals("cancel", mic.calls.last())
        assertTrue(clips.isEmpty())
    }

    @Test fun slidingOnlyCountsWhileHeld() {
        r.down()
        r.up(heldFor = 100)
        r.slide(cancel = true)
        assertEquals(false, (r.state.value as State.Recording).cancelling)
    }

    @Test fun theXOfATappedRecordingThrowsItAway() {
        r.down()
        r.up(heldFor = 100)
        r.cancel()
        assertEquals(State.Idle, r.state.value)
        assertEquals(listOf("start", "cancel"), mic.calls)
        assertTrue(clips.isEmpty())
    }

    @Test fun aSlipOfTheFingerIsNoMessage() {
        r.down()
        now += 300
        r.stop()
        assertIs<State.Failed>(r.state.value)
        assertEquals("cancel", mic.calls.last())
        assertTrue(clips.isEmpty())
        r.dismiss()
        assertEquals(State.Idle, r.state.value)
    }

    @Test fun nothingRecordedSaysTheMicrophoneFailed() {
        mic.file = null
        r.down()
        now += 5_000
        r.up(heldFor = 5_000)
        assertEquals(Recorder.message(Problem.Audio), assertIs<State.Failed>(r.state.value).message)
        assertTrue(clips.isEmpty())
    }

    @Test fun aMicrophoneThatWontOpenSaysSo() {
        mic.opens = false
        r.down()
        assertEquals(Recorder.message(Problem.Audio), assertIs<State.Failed>(r.state.value).message)
    }

    @Test fun theRecorderBreakingOffThrowsItAway() {
        r.down()
        r.failed(Problem.Audio)
        assertIs<State.Failed>(r.state.value)
        assertEquals("cancel", mic.calls.last())
        r.dismiss()
        r.down()
        r.failed(Problem.NoPermission)
        assertEquals(State.NoMic(forever = false), r.state.value)
    }

    @Test fun theMicrophoneIsAskedForFirst() {
        mic.allowed = false
        r.down()
        assertEquals(State.Asking, r.state.value)
        assertEquals(listOf("ask"), mic.calls)
        // A second press while the phone asks does nothing.
        r.down()
        assertEquals(listOf("ask"), mic.calls)
        // Allowed: it records at once, until a tap (the finger has left the button).
        r.answered(granted = true, forever = false)
        assertEquals(false, assertIs<State.Recording>(r.state.value).held)
        assertEquals("start", mic.calls.last())
    }

    @Test fun aRefusedMicrophoneCanBeAskedForAgainOrOpenedInSettings() {
        mic.allowed = false
        r.down()
        r.answered(granted = false, forever = false)
        assertEquals(State.NoMic(forever = false), r.state.value)
        r.askAgain()
        assertEquals(State.Asking, r.state.value)
        r.answered(granted = false, forever = true)
        assertEquals(State.NoMic(forever = true), r.state.value)
        r.settings()
        assertEquals(State.Idle, r.state.value)
        assertEquals("settings", mic.calls.last())
        // An answer nobody waits for any more changes nothing.
        r.answered(granted = true, forever = false)
        assertEquals(State.Idle, r.state.value)
    }

    @Test fun ticksFollowTheVoiceAndEndARecordingThatGoesOnTooLong() {
        r.down()
        mic.loud = 0.6f
        r.tick()
        assertEquals(0.6f, (r.state.value as State.Recording).level)
        mic.loud = 3f
        r.tick()
        assertEquals(1f, (r.state.value as State.Recording).level)
        now += Recorder.MAX_MS
        r.tick()
        assertEquals(State.Idle, r.state.value)
        assertEquals(listOf(Clip("/phone/voice/1.ogg", Recorder.MAX_MS)), clips)
    }

    @Test fun elapsedReadsAsMinutesAndSeconds() {
        assertEquals("0:00", Recorder.elapsed(0))
        assertEquals("0:07", Recorder.elapsed(7_400))
        assertEquals("1:05", Recorder.elapsed(65_000))
        assertEquals("10:00", Recorder.elapsed(600_000))
        assertEquals("0:00", Recorder.elapsed(-5))
    }
}
