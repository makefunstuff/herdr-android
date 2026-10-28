package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.chat.ChatItem
import com.makefunstuff.herdr.core.chat.Dictation
import com.makefunstuff.herdr.core.chat.Dictation.Problem
import com.makefunstuff.herdr.core.chat.Dictation.State
import com.makefunstuff.herdr.core.chat.Spoken
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The voice message's states in the composer, against a phone that hears what the test says. */
class DictationTest {
    private class FakeMic(var allowed: Boolean = true) : Dictation.Mic {
        val calls = mutableListOf<String>()
        override fun allowed() = allowed
        override fun ask() { calls += "ask" }
        override fun settings() { calls += "settings" }
        override fun listen() { calls += "listen" }
        override fun stop() { calls += "stop" }
        override fun cancel() { calls += "cancel" }
    }

    private var now = 1_000L
    private val mic = FakeMic()
    private val sent = mutableListOf<Pair<String, Long>>()
    private val d = Dictation(mic, { now }) { text, millis -> sent += text to millis }

    @Test fun holdSpeakAndLetGoPutsTheWordsInTheDraft() {
        d.down()
        val rec = assertIs<State.Recording>(d.state.value)
        assertTrue(rec.held)
        assertEquals(listOf("listen"), mic.calls)
        d.partial("run the")
        assertEquals("run the", (d.state.value as State.Recording).text)
        now += 7_000
        d.up(heldFor = 7_000)
        assertIs<State.Finishing>(d.state.value)
        assertEquals("stop", mic.calls.last())
        d.segment("run the tests again")
        assertEquals(State.Idle, d.state.value)
        assertEquals(listOf("run the tests again" to 7_000L), sent)
    }

    @Test fun theDialogsWordsGoInAsAVoiceMessage() {
        d.down()
        d.away()
        assertEquals(State.Away(1_000L), d.state.value)
        // Letting go, ticking: nothing happens while the dialog is up.
        d.up(heldFor = 2_000)
        d.tick()
        assertIs<State.Away>(d.state.value)
        now += 5_000
        d.fromDialog("  hello  ", Problem.Language)
        assertEquals(listOf("hello" to 5_000L), sent)
        assertEquals(State.Idle, d.state.value)
    }

    @Test fun theDialogsAnswerAfterTheAppWasMadeAnewStillGoesIn() {
        d.fromDialog("from before", null)
        assertEquals(listOf("from before" to 0L), sent)
    }

    @Test fun theDialogsAnswerWhileHearingAgainIsDropped() {
        d.down()
        d.fromDialog("late", null)
        assertTrue(sent.isEmpty())
        assertIs<State.Recording>(d.state.value)
    }

    @Test fun theDialogWithoutWordsSaysTheProblemOrNothing() {
        d.down()
        d.away()
        d.fromDialog(null, null)
        assertEquals(State.Idle, d.state.value)
        d.down()
        d.away()
        d.fromDialog("", Problem.Network)
        assertEquals(State.Failed(Dictation.message(Problem.Network)), d.state.value)
        assertTrue(sent.isEmpty())
    }

    @Test fun downWhileAwayStartsAgain() {
        d.down()
        d.away()
        d.down()
        assertIs<State.Recording>(d.state.value)
    }

    @Test fun aQuickTapKeepsHearingUntilTheNextTap() {
        d.down()
        d.up(heldFor = 120)
        val rec = assertIs<State.Recording>(d.state.value)
        assertEquals(false, rec.held)
        // Letting go again does nothing; the next tap finishes.
        d.up(heldFor = 50)
        assertIs<State.Recording>(d.state.value)
        now += 3_000
        d.down()
        assertIs<State.Finishing>(d.state.value)
        d.segment("yes")
        assertEquals(listOf("yes" to 3_000L), sent)
    }

    @Test fun slidingAwayAndLettingGoCancels() {
        d.down()
        d.partial("never mind")
        d.slide(cancel = true)
        assertTrue((d.state.value as State.Recording).cancelling)
        // Back again: letting go would finish.
        d.slide(cancel = false)
        assertEquals(false, (d.state.value as State.Recording).cancelling)
        d.slide(cancel = true)
        d.up(heldFor = 2_000)
        assertEquals(State.Idle, d.state.value)
        assertEquals("cancel", mic.calls.last())
        assertTrue(sent.isEmpty())
        // What the recognizer says after a cancel is dropped.
        d.segment("never mind")
        assertTrue(sent.isEmpty())
    }

    @Test fun slidingOnlyCountsWhileHeld() {
        d.down()
        d.up(heldFor = 100)
        d.slide(cancel = true)
        assertEquals(false, (d.state.value as State.Recording).cancelling)
    }

    @Test fun longSpeechIsHeardInStretches() {
        d.down()
        d.segment("first look at the logs")
        // Android stops after a pause: it is started again while you still speak.
        assertEquals(listOf("listen", "listen"), mic.calls)
        d.failed(Problem.NoSpeech)
        assertEquals(3, mic.calls.count { it == "listen" })
        d.partial("then fix")
        assertEquals("first look at the logs then fix", (d.state.value as State.Recording).text)
        now += 9_000
        d.stop()
        d.segment("then fix the test")
        assertEquals(listOf("first look at the logs then fix the test" to 9_000L), sent)
    }

    @Test fun finishingWithoutAFinalResultKeepsWhatWasHeard() {
        d.down()
        d.partial("ship it")
        now += 1_500
        d.stop()
        d.failed(Problem.NoSpeech)
        assertEquals(listOf("ship it" to 1_500L), sent)
    }

    @Test fun nothingHeardSaysSo() {
        d.down()
        d.stop()
        d.failed(Problem.NoSpeech)
        val failed = assertIs<State.Failed>(d.state.value)
        assertEquals(Dictation.message(Problem.NoSpeech), failed.message)
        d.dismiss()
        assertEquals(State.Idle, d.state.value)
    }

    @Test fun anErrorWhileHearingKeepsTheWordsOrSaysWhatWentWrong() {
        d.down()
        d.failed(Problem.Network)
        assertEquals(State.Failed(Dictation.message(Problem.Network)), d.state.value)
        d.dismiss()
        d.down()
        d.segment("deploy to staging")
        d.failed(Problem.Audio)
        assertEquals("deploy to staging", sent.single().first)
        assertEquals(State.Idle, d.state.value)
    }

    @Test fun theMicrophoneIsAskedForOnFirstUse() {
        mic.allowed = false
        d.down()
        assertEquals(State.Asking, d.state.value)
        assertEquals(listOf("ask"), mic.calls)
        // Letting go while the phone asks does nothing.
        d.up(heldFor = 900)
        assertEquals(State.Asking, d.state.value)
        d.answered(granted = true, forever = false)
        val rec = assertIs<State.Recording>(d.state.value)
        assertEquals(false, rec.held, "allowed: it hears at once, until tapped")
        assertEquals("listen", mic.calls.last())
    }

    @Test fun aRefusedMicrophoneIsExplained() {
        mic.allowed = false
        d.down()
        d.answered(granted = false, forever = false)
        assertEquals(State.NoMic(forever = false), d.state.value)
        d.askAgain()
        assertEquals(State.Asking, d.state.value)
        d.answered(granted = false, forever = true)
        assertEquals(State.NoMic(forever = true), d.state.value)
        d.settings()
        assertEquals(State.Idle, d.state.value)
        assertEquals("settings", mic.calls.last())
        // Taking the permission away while it hears.
        mic.allowed = true
        d.down()
        d.failed(Problem.NoPermission)
        assertEquals(State.NoMic(forever = false), d.state.value)
    }

    @Test fun aRecordingStopsAfterFiveMinutesAndAFinishTheRecognizerNeverAnswers() {
        d.down()
        d.partial("a long story")
        now += Dictation.MAX_MS - 1
        d.tick()
        assertIs<State.Recording>(d.state.value)
        now += 1
        d.tick()
        assertIs<State.Finishing>(d.state.value)
        now += Dictation.FINISH_MS
        d.tick()
        assertEquals("cancel", mic.calls.last())
        assertEquals(listOf("a long story" to Dictation.MAX_MS), sent)
    }

    @Test fun loudness() {
        d.down()
        d.level(-10f)
        assertEquals(0f, (d.state.value as State.Recording).level)
        d.level(4f)
        assertEquals(0.5f, (d.state.value as State.Recording).level)
        d.level(30f)
        assertEquals(1f, (d.state.value as State.Recording).level)
    }

    @Test fun wordsGoAfterTheDraft() {
        assertEquals("run it", Dictation.append("", "run it"))
        assertEquals("run it", Dictation.append("  ", "run it"))
        assertEquals("Also: run it", Dictation.append("Also:", "run it"))
        assertEquals("Also:\nrun it", Dictation.append("Also:\n", "run it"))
    }

    @Test fun discardingTakesOutOnlyTheWordsHeard() {
        fun spoken(before: String, words: String) = Dictation.drop(Dictation.append(before, words), before, words)
        // What was typed stays whole, also where it holds the same words.
        assertEquals("Check yesterday's logs", spoken("Check yesterday's logs", "yes"))
        assertEquals("Do not run the tests\n- step one\n", spoken("Do not run the tests\n- step one\n", "run the tests"))
        assertEquals("", spoken("", "ship it"))
        assertEquals("", spoken("  ", "ship it"))
        // Two takes in a row go together.
        val two = Dictation.append(Dictation.append("Also:", "run it"), "then push")
        assertEquals("Also:", Dictation.drop(two, "Also:", Dictation.join("run it", "then push")))
        // Changed, or typed after: they are yours now.
        assertEquals(null, Dictation.drop("Also: run it twice", "Also:", "run it"))
        assertEquals(null, Dictation.drop("Also: ran it", "Also:", "run it"))
        assertEquals(null, Dictation.drop("Al: run it", "Also:", "run it"))
        assertEquals(null, Dictation.drop("Also:", "Also:", ""))
    }

    @Test fun wordsGoInWhereTheCursorStands() {
        fun at(draft: String, cursor: Int, text: String) = Dictation.insert(draft, cursor, text)
        assertEquals(Dictation.Inserted("run it", 6), at("", 0, "run it"))
        assertEquals(Dictation.Inserted("run it", 6), at("   ", 2, "run it"))
        // In the middle: a space on each side, the cursor right after the words.
        assertEquals(Dictation.Inserted("Please run the tests now", 20), at("Please now", 6, "run the tests"))
        assertEquals(Dictation.Inserted("Please run the tests now", 20), at("Please  now", 7, "run the tests"))
        // At the start, and before a mark that closes the sentence.
        assertEquals(Dictation.Inserted("Also check the logs", 4), at("check the logs", 0, "Also"))
        assertEquals(Dictation.Inserted("Fix the build, then push", 13), at("Fix, then push", 3, "the build"))
        assertEquals(Dictation.Inserted("(run it)", 7), at("()", 1, "run it"))
        // A cursor past the end (the draft changed under it) puts them at the end.
        assertEquals(Dictation.Inserted("Also: run it", 12), at("Also:", 40, "run it"))
        assertEquals(Dictation.Inserted("run it now", 6), at("now", -3, "run it"))
    }

    @Test fun discardingTakesTheWordsOutWhereTheyWentIn() {
        fun spoken(before: String, cursor: Int, words: String) = Dictation.drop(Dictation.insert(before, cursor, words).text, before, words)
        assertEquals("Please now", spoken("Please now", 6, "run the tests"))
        assertEquals("check the logs", spoken("check the logs", 0, "Also"))
        assertEquals("Fix, then push", spoken("Fix, then push", 3, "the build"))
        // Two takes at the same cursor go together.
        val first = Dictation.insert("Please now", 6, "run")
        val two = Dictation.insert(first.text, first.cursor, "the tests")
        assertEquals("Please run the tests now", two.text)
        assertEquals("Please now", Dictation.drop(two.text, "Please now", Dictation.join("run", "the tests")))
        // Typed into, or the old words changed: they are yours now.
        assertEquals(null, Dictation.drop("Please run all the tests now", "Please now", "run the tests"))
        assertEquals(null, Dictation.drop("Pleas run the tests now", "Please now", "run the tests"))
    }

    @Test fun recognizerErrorsSayWhatWentWrong() {
        val e = Dictation.Errors
        assertEquals(Problem.NoSpeech, Dictation.problem(e.NO_MATCH))
        assertEquals(Problem.NoSpeech, Dictation.problem(e.SPEECH_TIMEOUT))
        assertEquals(Problem.Network, Dictation.problem(e.NETWORK))
        assertEquals(Problem.Network, Dictation.problem(e.NETWORK_TIMEOUT))
        assertEquals(Problem.Network, Dictation.problem(e.SERVER))
        assertEquals(Problem.Network, Dictation.problem(e.SERVER_DISCONNECTED))
        assertEquals(Problem.Busy, Dictation.problem(e.RECOGNIZER_BUSY))
        assertEquals(Problem.Busy, Dictation.problem(e.TOO_MANY_REQUESTS))
        assertEquals(Problem.Audio, Dictation.problem(e.AUDIO))
        assertEquals(Problem.NoPermission, Dictation.problem(e.INSUFFICIENT_PERMISSIONS))
        assertEquals(Problem.Language, Dictation.problem(e.LANGUAGE_NOT_SUPPORTED))
        assertEquals(Problem.Language, Dictation.problem(e.LANGUAGE_UNAVAILABLE))
        assertEquals(Problem.Other, Dictation.problem(e.CLIENT))
        assertEquals(Problem.Other, Dictation.problem(99))
        // Each says something of its own.
        assertEquals(Problem.entries.size, Problem.entries.map(Dictation::message).toSet().size)
    }

    @Test fun aTimeoutWithNothingSaidSaysSoAndTheMicCanBeTriedAgain() {
        d.down()
        d.up(heldFor = 100)
        // A pause before the first word: it keeps hearing.
        d.failed(Dictation.problem(Dictation.Errors.SPEECH_TIMEOUT))
        assertIs<State.Recording>(d.state.value)
        d.stop()
        d.failed(Dictation.problem(Dictation.Errors.NO_MATCH))
        assertEquals(State.Failed(Dictation.message(Problem.NoSpeech)), d.state.value)
        assertTrue(sent.isEmpty())
        d.dismiss()
        assertEquals(State.Idle, d.state.value)
    }

    @Test fun noNetworkWithNothingSaidSaysSo() {
        d.down()
        d.failed(Dictation.problem(Dictation.Errors.NETWORK))
        assertEquals(State.Failed(Dictation.message(Problem.Network)), d.state.value)
        d.down()
        assertIs<State.Recording>(d.state.value)
        d.failed(Problem.Unavailable)
        assertEquals(State.Failed(Dictation.message(Problem.Unavailable)), d.state.value)
    }

    @Test fun elapsed() {
        assertEquals("0:00", Dictation.elapsed(0))
        assertEquals("0:07", Dictation.elapsed(7_900))
        assertEquals("1:05", Dictation.elapsed(65_000))
        assertEquals("12:00", Dictation.elapsed(720_000))
    }

    @Test fun spokenMessagesAreMarkedOnceEachFromTheirTime() {
        val users = listOf(
            ChatItem.User("u1", "yes") to 10_000L,
            ChatItem.User("u2", "Run the failing test again") to 500_000L,
            ChatItem.User("u3", "yes") to 600_000L,
            ChatItem.User("u4", "yes") to 700_000L,
        )
        // The typed "yes" before it is not the spoken one; the machine's clock may run a little behind.
        val sent = listOf(Spoken.Sent("yes", 590_000L + Spoken.SLACK_MS), Spoken.Sent("Run the failing test again", 510_000L))
        assertEquals(setOf("u2", "u3"), Spoken.marks(sent, users))
        assertEquals(emptySet(), Spoken.marks(emptyList(), users))
        // A transcript without times matches by the words alone.
        assertEquals(setOf("u1"), Spoken.marks(listOf(Spoken.Sent("yes", 1L)), users.map { it.first to 0L }))
        assertTrue(Spoken.isSpoken(sent, "Run the failing test again", 512_000L))
        assertEquals(false, Spoken.isSpoken(sent, "Run the failing test again", 512_000L + Spoken.SLACK_MS))
        assertEquals(Spoken.KEEP, (1..Spoken.KEEP + 5).fold(emptyList<Spoken.Sent>()) { l, i -> Spoken.add(l, "m$i", i.toLong()) }.size)
    }
}
