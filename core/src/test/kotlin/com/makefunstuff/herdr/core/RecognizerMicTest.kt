package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.chat.Dictation
import com.makefunstuff.herdr.core.chat.Dictation.Errors
import com.makefunstuff.herdr.core.chat.Dictation.State
import com.makefunstuff.herdr.core.chat.RecognizerMic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A voice message on a fake phone with two recognizers, as the app's Android side drives them:
 * which one starts, in which language, and what is tried when one fails.
 */
class RecognizerMicTest {
    private val google = listOf("en-GB", "en-US", "de-DE", "ru-RU")

    /** One of the fake phone's recognizers: what it was told, as `make on-device`, `start en-US`, `start -` (`online`: not told to prefer offline). */
    private inner class FakeRecognizer(val onDevice: Boolean, val canBeAsked: Boolean) : RecognizerMic.Recognizer {
        var answer: ((List<String>?) -> Unit)? = null
        override fun languages(answer: (List<String>?) -> Unit): Boolean {
            if (!canBeAsked) return false
            log += "${name()} asked"
            this.answer = answer
            return true
        }
        override fun start(language: String?, offline: Boolean) { log += "${name()} start ${language ?: "-"}${if (offline) "" else " online"}" }
        override fun stop() { log += "${name()} stop" }
        override fun cancel() { log += "${name()} cancel" }
        override fun destroy() { log += "${name()} destroy" }
        fun name() = if (onDevice) "on-device" else "regular"
    }

    private inner class FakePhone : RecognizerMic.Phone {
        var app = emptyList<String>()
        var languages = listOf("en-EE", "ru-EE")
        var canBeAsked = true
        var fails = false
        val made = mutableListOf<FakeRecognizer>()
        val pending = mutableListOf<Pair<Long, () -> Unit>>()
        override fun allowed() = true
        override fun ask() {}
        override fun settings() {}
        override fun recognizer(onDevice: Boolean): RecognizerMic.Recognizer {
            if (fails) error("no recognizer")
            log += "make ${if (onDevice) "on-device" else "regular"}"
            return FakeRecognizer(onDevice, canBeAsked).also { made += it }
        }
        override fun appLanguages() = app
        override fun phoneLanguages() = languages
        override fun later(ms: Long, run: () -> Unit) { pending += now + ms to run }
        override fun cancelLater() = pending.clear()
        override fun log(line: String) {}
    }

    private val log = mutableListOf<String>()
    private var now = 1_000L
    private val phone = FakePhone()
    private val sent = mutableListOf<String>()

    private fun mic(onDevice: Boolean = true): Pair<RecognizerMic, Dictation> {
        val mic = RecognizerMic(phone, onDevice)
        val d = Dictation(mic, { now }) { text, _ -> sent += text }
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

    private fun last() = phone.made.last()

    @Test fun itStartsInTheClosestLanguageTheRecognizerHas() {
        val (_, d) = mic()
        d.down()
        assertEquals(listOf("make on-device", "on-device asked"), log)
        last().answer!!(google)
        assertEquals("on-device start en-US", log.last())
        // The next stretch isn't asked again.
        d.segment("hello")
        assertEquals(listOf("make on-device", "on-device asked", "on-device start en-US", "on-device start en-US"), log)
    }

    @Test fun theAppsLanguageComesFirst() {
        phone.app = listOf("ru-EE")
        val (_, d) = mic()
        d.down()
        last().answer!!(google)
        assertEquals("on-device start ru-RU", log.last())
    }

    @Test fun aRecognizerThatDoesntAnswerStartsInItsOwnAfterAWhile() {
        val (_, d) = mic(onDevice = false)
        d.down()
        pass(RecognizerMic.CHECK_MS - 1)
        assertEquals("regular asked", log.last())
        pass(1)
        assertEquals("regular start -", log.last())
        // A late answer starts nothing more.
        last().answer!!(google)
        assertEquals("regular start -", log.last())
    }

    /** The reviewer's three ways to the regular recognizer in its own language: each ends on the plain request. */
    @Test fun theRegularRecognizerInItsOwnEndsOnThePlainRequest() {
        fun endsPlain(setUp: () -> Unit, run: (RecognizerMic, Dictation) -> Unit) {
            log.clear()
            phone.made.clear()
            setUp()
            val (mic, d) = mic()
            run(mic, d)
            assertEquals("regular start -", log.last(), "$log")
            mic.error(Errors.LANGUAGE_UNAVAILABLE)
            assertEquals("regular start - online", log.last(), "$log")
            assertIs<State.Recording>(d.state.value)
            d.segment("heard")
            d.stop()
            d.segment("")
            assertEquals("heard", sent.last())
            mic.close()
        }
        // On a Pixel: the on-device recognizer fails, the regular one doesn't answer in time.
        endsPlain({}) { mic, d ->
            d.down()
            last().answer!!(google)
            mic.error(Errors.CLIENT)
            pass(RecognizerMic.CHECK_MS)
        }
        // Android 12 and older: the regular one can't be asked.
        endsPlain({ phone.canBeAsked = false }) { mic, d ->
            d.down()
            mic.error(Errors.CLIENT)
        }
        // A phone in Estonian: no language matches.
        endsPlain({ phone.canBeAsked = true; phone.languages = listOf("et-EE") }) { _, d ->
            d.down()
            last().answer!!(listOf("en-US"))
            last().answer!!(google)
        }
    }

    @Test fun aRecognizerThatCantBeAskedStartsInItsOwnAtOnce() {
        phone.canBeAsked = false
        val (_, d) = mic(onDevice = false)
        d.down()
        assertEquals(listOf("make regular", "regular start -"), log)
    }

    @Test fun cancelledWhileItIsAskedNothingStarts() {
        val (_, d) = mic()
        d.down()
        d.cancel()
        last().answer!!(google)
        assertTrue(log.none { it.contains("start") }, "$log")
        assertIs<State.Idle>(d.state.value)
    }

    @Test fun cancelledAndTappedAgainBeforeTheAnswerStartsOnce() {
        val (_, d) = mic()
        d.down()
        val first = last().answer!!
        d.cancel()
        d.down()
        val second = last().answer!!
        first(google)
        assertTrue(log.none { it.contains("start") }, "$log")
        second(google)
        assertEquals(1, log.count { it.contains("start") }, "$log")
        assertEquals("on-device start en-US", log.last())
        // The first question's time running out starts nothing either.
        pass(RecognizerMic.CHECK_MS)
        assertEquals(1, log.count { it.contains("start") }, "$log")
    }

    @Test fun aLanguageErrorIsTriedOnceInTheRecognizersOwn() {
        val (mic, d) = mic(onDevice = false)
        d.down()
        last().answer!!(google)
        assertEquals("regular start en-US", log.last())
        mic.error(Errors.LANGUAGE_NOT_SUPPORTED)
        // The last way left asks as plainly as Gboard: no language, not offline.
        assertEquals("regular start - online", log.last())
        assertIs<State.Recording>(d.state.value)
        // And again: it fails, with the language's message.
        mic.error(Errors.LANGUAGE_NOT_SUPPORTED)
        assertEquals(State.Failed(Dictation.message(Dictation.Problem.Language)), d.state.value)
        assertEquals(1, phone.made.size)
    }

    @Test fun theOnDeviceRecognizerFailingGivesWayToTheRegularOne() {
        val (mic, d) = mic()
        d.down()
        last().answer!!(google)
        mic.error(Errors.CLIENT)
        assertEquals(listOf("make on-device", "on-device asked", "on-device start en-US", "on-device destroy", "make regular", "regular asked"), log)
        last().answer!!(google)
        assertEquals("regular start en-US", log.last())
        assertIs<State.Recording>(d.state.value)
        // From then on: the regular one.
        d.partial("hello there")
        d.segment("hello there")
        assertEquals("regular start en-US", log.last())
        assertEquals(2, phone.made.size)
    }

    @Test fun anOnDeviceRecognizerWithoutThePhonesLanguageIsSkipped() {
        phone.languages = listOf("ru-EE")
        val (_, d) = mic()
        d.down()
        last().answer!!(listOf("en-US"))
        assertEquals(listOf("make on-device", "on-device asked", "on-device destroy", "make regular", "regular asked"), log)
        last().answer!!(google)
        assertEquals("regular start ru-RU", log.last())
    }

    @Test fun thePixelsBugIsGone() {
        // en-EE as is in no recognizer: both used to refuse. Now each is asked for what it has.
        val (mic, d) = mic()
        d.down()
        last().answer!!(null)
        // It can't say: no language, its own.
        assertEquals("on-device start -", log.last())
        mic.error(Errors.LANGUAGE_UNAVAILABLE)
        last().answer!!(google)
        assertEquals("regular start en-US", log.last())
        d.partial("ship it")
        d.stop()
        d.segment("ship it")
        assertEquals(listOf("ship it"), sent)
    }

    @Test fun everyLanguageTurnedDownEndsAsPlainlyAsAppsThatWorkOnThePhone() {
        val (mic, d) = mic()
        d.down()
        last().answer!!(google)
        mic.error(Errors.LANGUAGE_NOT_SUPPORTED)
        assertEquals("on-device start -", log.last())
        mic.error(Errors.LANGUAGE_UNAVAILABLE)
        last().answer!!(google)
        assertEquals("regular start en-US", log.last())
        mic.error(Errors.LANGUAGE_NOT_SUPPORTED)
        // The regular recognizer, no language, not offline: what Gboard and slopbot's mic ask for.
        assertEquals("regular start - online", log.last())
        d.partial("works now")
        d.stop()
        d.segment("works now")
        assertEquals(listOf("works now"), sent)
    }

    @Test fun failingWhileFinishingSaysSoAndTheOtherWayIsKeptForNextTime() {
        val (mic, d) = mic()
        d.down()
        last().answer!!(google)
        d.partial("almost")
        d.stop()
        mic.error(Errors.CLIENT)
        // Not started again: the words heard go in.
        assertEquals(listOf("almost"), sent)
        assertTrue(log.none { it == "make regular" }, "$log")
        d.down()
        assertEquals(listOf("make regular", "regular asked"), log.takeLast(2))
    }

    @Test fun nothingSaidKeepsTheOnDeviceRecognizerHearing() {
        val (mic, d) = mic()
        d.down()
        last().answer!!(google)
        mic.error(Errors.NO_MATCH)
        assertEquals("on-device start en-US", log.last())
        assertEquals(1, phone.made.size)
        assertIs<State.Recording>(d.state.value)
    }

    @Test fun aBusyRecognizerIsStartedOnceMoreAMomentLater() {
        val (mic, d) = mic(onDevice = false)
        d.down()
        last().answer!!(google)
        mic.error(Errors.RECOGNIZER_BUSY)
        assertEquals("regular start en-US", log.last())
        val starts = log.count { it.contains("start") }
        pass(RecognizerMic.BUSY_MS)
        assertEquals(starts + 1, log.count { it.contains("start") })
        mic.error(Errors.RECOGNIZER_BUSY)
        assertEquals(State.Failed(Dictation.message(Dictation.Problem.Busy)), d.state.value)
    }

    @Test fun noRecognizerSaysItIsUnavailable() {
        phone.fails = true
        val (_, d) = mic()
        d.down()
        assertEquals(State.Failed(Dictation.message(Dictation.Problem.Unavailable)), d.state.value)
    }

    @Test fun closedTheRecognizerGoesAndIsAskedAgainWhenMadeAnew() {
        val (mic, d) = mic()
        d.down()
        last().answer!!(google)
        d.cancel()
        mic.close()
        assertEquals("on-device destroy", log.last())
        d.down()
        assertEquals(listOf("make on-device", "on-device asked"), log.takeLast(2))
    }
}
