package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.chat.Dictation
import com.makefunstuff.herdr.core.chat.Dictation.Errors
import com.makefunstuff.herdr.core.chat.Dictation.Problem
import com.makefunstuff.herdr.core.chat.Dictation.State
import com.makefunstuff.herdr.core.chat.RecognizerMic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A voice message on a fake phone, as the app's Android side drives it: the regular recognizer
 * asked plainly, and the phone's speech dialog when it fails.
 */
class RecognizerMicTest {
    /** The fake phone's recognizer: what it was told, as `make`, `start`, `stop`. */
    private inner class FakeRecognizer : RecognizerMic.Recognizer {
        override fun start() { log += "start" }
        override fun stop() { log += "stop" }
        override fun cancel() { log += "cancel" }
        override fun destroy() { log += "destroy" }
    }

    private inner class FakePhone : RecognizerMic.Phone {
        var fails = false
        var hasDialog = true
        val pending = mutableListOf<Pair<Long, () -> Unit>>()
        val lines = mutableListOf<String>()
        override fun allowed() = true
        override fun ask() {}
        override fun settings() {}
        override fun recognizer(): RecognizerMic.Recognizer {
            if (fails) error("no recognizer")
            log += "make"
            return FakeRecognizer()
        }
        override fun dialog(): Boolean {
            if (!hasDialog) return false
            log += "dialog"
            return true
        }
        override fun later(ms: Long, run: () -> Unit) { pending += now + ms to run }
        override fun cancelLater() = pending.clear()
        override fun log(line: String) { lines += line }
    }

    private val log = mutableListOf<String>()
    private var now = 1_000L
    private val phone = FakePhone()
    private val sent = mutableListOf<Pair<String, Long>>()

    private fun mic(): Pair<RecognizerMic, Dictation> {
        val mic = RecognizerMic(phone)
        val d = Dictation(mic, { now }) { text, millis -> sent += text to millis }
        mic.dictation = d
        return mic to d
    }

    /** The clock on by [ms], running what was due. */
    private fun pass(ms: Long) {
        now += ms
        val due = phone.pending.filter { it.first <= now }
        phone.pending.removeAll(due)
        due.forEach { it.second() }
    }

    @Test fun theRegularRecognizerHearsAndIsKeptForTheNextStretch() {
        val (_, d) = mic()
        d.down()
        assertEquals(listOf("make", "start"), log)
        d.segment("hello")
        d.partial("there")
        d.stop()
        d.segment("there")
        assertEquals(listOf("make", "start", "start", "stop"), log)
        assertEquals("hello there", sent.single().first)
    }

    @Test fun aLanguageErrorOpensTheDialogAndItsWordsGoIn() {
        val (mic, d) = mic()
        d.down()
        mic.error(Errors.LANGUAGE_NOT_SUPPORTED)
        assertEquals("dialog", log.last())
        // Not the language's message: the dialog hears instead.
        assertEquals(State.Away(1_000L), d.state.value)
        pass(4_000)
        mic.dialog("ship it", failed = false)
        assertEquals(listOf("ship it" to 4_000L), sent)
        assertIs<State.Idle>(d.state.value)
        assertTrue(phone.lines.any { it.contains("error ${Errors.LANGUAGE_NOT_SUPPORTED}") }, "${phone.lines}")
    }

    @Test fun everyOtherFailureBeforeAnyWordsOpensTheDialog() {
        for (error in listOf(Errors.LANGUAGE_UNAVAILABLE, Errors.NETWORK, Errors.NETWORK_TIMEOUT, Errors.SERVER, Errors.AUDIO, Errors.SERVER_DISCONNECTED, Errors.TOO_MANY_REQUESTS)) {
            log.clear()
            val (mic, d) = mic()
            d.down()
            mic.error(error)
            assertEquals("dialog", log.last(), "error $error")
            assertIs<State.Away>(d.state.value)
        }
    }

    @Test fun nothingSaidKeepsTheRecognizerHearing() {
        val (mic, d) = mic()
        d.down()
        mic.error(Errors.NO_MATCH)
        mic.error(Errors.SPEECH_TIMEOUT)
        assertEquals(listOf("make", "start", "start", "start"), log)
        assertIs<State.Recording>(d.state.value)
    }

    @Test fun noMicrophoneOpensNoDialog() {
        val (mic, d) = mic()
        d.down()
        mic.error(Errors.INSUFFICIENT_PERMISSIONS)
        assertTrue("dialog" !in log)
        assertEquals(State.NoMic(forever = false), d.state.value)
    }

    @Test fun aFailureAfterWordsKeepsThemAndOpensNoDialog() {
        val (mic, d) = mic()
        d.down()
        d.partial("almost")
        mic.error(Errors.LANGUAGE_NOT_SUPPORTED)
        assertTrue("dialog" !in log)
        assertEquals("almost", sent.single().first)
    }

    @Test fun theLanguageErrorIsSaidOnlyWhenTheDialogFailsToo() {
        val (mic, d) = mic()
        d.down()
        mic.error(Errors.LANGUAGE_UNAVAILABLE)
        mic.dialog(null, failed = true)
        assertEquals(State.Failed(Dictation.message(Problem.Language)), d.state.value)
        assertTrue(sent.isEmpty())
    }

    @Test fun withoutADialogTheErrorIsSaid() {
        phone.hasDialog = false
        val (mic, d) = mic()
        d.down()
        mic.error(Errors.LANGUAGE_NOT_SUPPORTED)
        assertEquals(State.Failed(Dictation.message(Problem.Language)), d.state.value)
    }

    @Test fun theDialogClosedOrHearingNothingSaysNothing() {
        val (mic, d) = mic()
        d.down()
        mic.error(Errors.NETWORK)
        mic.dialog(null, failed = false)
        assertIs<State.Idle>(d.state.value)
        assertTrue(sent.isEmpty())
    }

    @Test fun noRecognizerOpensTheDialog() {
        phone.fails = true
        val (mic, d) = mic()
        d.down()
        assertEquals(listOf("dialog"), log)
        mic.dialog(null, failed = true)
        assertEquals(State.Failed(Dictation.message(Problem.Unavailable)), d.state.value)
    }

    @Test fun aBusyRecognizerIsStartedOnceMoreAMomentLater() {
        val (mic, d) = mic()
        d.down()
        mic.error(Errors.RECOGNIZER_BUSY)
        assertEquals(listOf("make", "start"), log)
        pass(RecognizerMic.RETRY_MS)
        assertEquals(listOf("make", "start", "start"), log)
        // Busy again: the dialog.
        mic.error(Errors.RECOGNIZER_BUSY)
        assertEquals("dialog", log.last())
    }

    @Test fun aClientHiccupAfterAPauseIsStartedOnceMoreNotTheDialog() {
        val (mic, d) = mic()
        d.down()
        d.up(heldFor = 100)
        mic.error(Errors.SPEECH_TIMEOUT)
        mic.error(Errors.CLIENT)
        pass(RecognizerMic.RETRY_MS)
        assertEquals(listOf("make", "start", "start", "start"), log)
        assertIs<State.Recording>(d.state.value)
        mic.error(Errors.CLIENT)
        assertEquals("dialog", log.last())
    }

    @Test fun busyAfterStoppingOpensTheDialogAtOnce() {
        val (mic, d) = mic()
        d.down()
        d.up(heldFor = 1_000)
        assertIs<State.Finishing>(d.state.value)
        mic.error(Errors.RECOGNIZER_BUSY)
        assertEquals("dialog", log.last())
        assertIs<State.Away>(d.state.value)
        // No hang on Transcribing, no "Didn't catch that".
        pass(Dictation.FINISH_MS + 1_500)
        d.tick()
        assertIs<State.Away>(d.state.value)
    }

    @Test fun busyAndThenStoppedBeforeTheRetryOpensTheDialog() {
        val (mic, d) = mic()
        d.down()
        mic.error(Errors.RECOGNIZER_BUSY)
        d.stop()
        pass(RecognizerMic.RETRY_MS)
        assertEquals(listOf("make", "start", "stop", "dialog"), log)
        assertIs<State.Away>(d.state.value)
    }

    @Test fun busyAfterStoppingWithWordsKeepsThem() {
        val (mic, d) = mic()
        d.down()
        d.partial("almost there")
        d.stop()
        mic.error(Errors.RECOGNIZER_BUSY)
        assertTrue("dialog" !in log)
        assertEquals("almost there", sent.single().first)
    }

    @Test fun eachTapMayBeRetriedOnceAgain() {
        val (mic, d) = mic()
        d.down()
        mic.error(Errors.RECOGNIZER_BUSY)
        pass(RecognizerMic.RETRY_MS)
        d.cancel()
        log.clear()
        d.down()
        mic.error(Errors.RECOGNIZER_BUSY)
        assertEquals(listOf("start"), log)
        pass(RecognizerMic.RETRY_MS)
        assertEquals(listOf("start", "start"), log)
        assertIs<State.Recording>(d.state.value)
    }

    @Test fun cancelledWhileBusyNothingStartsLater() {
        val (mic, d) = mic()
        d.down()
        mic.error(Errors.RECOGNIZER_BUSY)
        d.cancel()
        pass(RecognizerMic.RETRY_MS)
        assertEquals(listOf("make", "start", "cancel"), log)
    }

    @Test fun eachTapTriesTheRecognizerFirst() {
        val (mic, d) = mic()
        d.down()
        mic.error(Errors.LANGUAGE_NOT_SUPPORTED)
        mic.dialog("one", failed = false)
        d.down()
        assertEquals(listOf("make", "start", "dialog", "start"), log)
        assertIs<State.Recording>(d.state.value)
    }

    @Test fun closedTheRecognizerGoesAndIsMadeAnew() {
        val (mic, d) = mic()
        d.down()
        d.cancel()
        mic.close()
        assertEquals("destroy", log.last())
        d.down()
        assertEquals(listOf("make", "start"), log.takeLast(2))
    }
}
