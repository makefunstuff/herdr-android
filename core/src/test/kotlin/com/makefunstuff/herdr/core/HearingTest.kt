package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.chat.Dictation.Errors
import com.makefunstuff.herdr.core.chat.Hearing
import com.makefunstuff.herdr.core.chat.Hearing.Attempt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The language a recognizer is asked for, and what is tried when one fails, against fake phones. */
class HearingTest {
    private val google = listOf("en-AU", "en-GB", "en-IN", "en-US", "de-DE", "ru-RU", "uk-UA", "pt-BR", "pt-PT", "zh-Hant-TW", "zh-Hans-CN")

    @Test fun theSameTagWins() {
        assertEquals("en-GB", Hearing.best("en-GB", google))
        assertEquals("en-GB", Hearing.best("en_gb", google))
        assertEquals("pt-PT", Hearing.best("pt-PT", google))
    }

    @Test fun aCountryNoRecognizerHasGetsTheLanguageInItsHomeCountry() {
        assertEquals("en-US", Hearing.best("en-EE", google))
        assertEquals("ru-RU", Hearing.best("ru-EE", google))
        assertEquals("uk-UA", Hearing.best("uk-EE", google))
        assertEquals("de-DE", Hearing.best("de-AT", google))
        assertEquals("de-DE", Hearing.best("de", google))
    }

    @Test fun elseTheLanguageWithoutACountryThenInAnyCountry() {
        assertEquals("en", Hearing.best("en-EE", listOf("en-GB", "en")))
        assertEquals("fr-CA", Hearing.best("fr-BE", listOf("en-US", "fr-CA")))
        assertEquals("he-IL", Hearing.best("iw-IL", listOf("he-IL")))
    }

    @Test fun aScriptIsKept() {
        assertEquals("zh-Hant-TW", Hearing.best("zh-Hant-HK", google))
        assertEquals("zh-Hans-CN", Hearing.best("zh-Hans-SG", google))
    }

    @Test fun chineseWithoutAScriptIsWrittenAsItsCountryWritesIt() {
        assertEquals("zh-Hant-TW", Hearing.best("zh-TW", google))
        assertEquals("zh-Hant-TW", Hearing.best("zh-HK", google))
        assertEquals("zh-Hans-CN", Hearing.best("zh-CN", google))
        // Google's recognizer names Mandarin cmn.
        assertEquals("cmn-Hant-TW", Hearing.best("zh-Hant-TW", listOf("cmn-Hans-CN", "cmn-Hant-TW", "yue-Hant-HK")))
    }

    @Test fun regionalPreferencesAndVariantsAreLeftOut() {
        assertEquals("en-GB", Hearing.best("en-GB-u-nu-latn", google))
        assertEquals("en-GB", Hearing.best("en-GB-u-mu-celsius", google))
        assertEquals("en-GB", Hearing.best("en-GB-u-fw-mon-mu-celsius", google))
        assertEquals("de-DE", Hearing.best("de-DE-1996", google))
        assertEquals("en-US", Hearing.best("en-EE-x-test", google))
    }

    @Test fun aLanguageNoRecognizerHasIsNone() {
        assertNull(Hearing.best("et-EE", google))
        assertNull(Hearing.best("", google))
    }

    @Test fun thePhonesLanguagesInOrderThenNone() {
        assertEquals("en-US", Hearing.language(app = emptyList(), phone = listOf("en-EE", "ru-EE"), supported = google))
        assertEquals("ru-RU", Hearing.language(app = emptyList(), phone = listOf("et-EE", "ru-EE"), supported = google))
        assertNull(Hearing.language(app = emptyList(), phone = listOf("et-EE"), supported = google))
    }

    @Test fun theAppsLanguageComesFirst() {
        assertEquals("ru-RU", Hearing.language(app = listOf("ru-EE"), phone = listOf("en-EE"), supported = google))
        // Chosen for the app but not there: the phone's.
        assertEquals("en-US", Hearing.language(app = listOf("et-EE"), phone = listOf("en-EE"), supported = google))
    }

    @Test fun aRecognizerThatCantSayIsAskedOnlyForTheAppsLanguage() {
        assertNull(Hearing.language(app = emptyList(), phone = listOf("en-EE"), supported = null))
        assertNull(Hearing.language(app = emptyList(), phone = listOf("en-EE"), supported = emptyList()))
        assertEquals("ru-EE", Hearing.language(app = listOf("ru-EE"), phone = listOf("en-EE"), supported = null))
    }

    /**
     * A phone's two recognizers: each with the languages it has, null when it can't say; [onDeviceFails]
     * with that error whatever it is asked; asked for no language, the on-device one hears in its own,
     * the regular one when [regularDefault] (the one chosen for voice typing).
     */
    private class FakePhone(
        val onDevice: List<String>?,
        val regular: List<String>?,
        val onDeviceFails: Int? = null,
        val regularDefault: Boolean = true,
        val hasOnDevice: Boolean = true,
    )

    /** Starts hearing [phone] in [phoneLanguages] as the app does: each attempt started, as `on-device:en-US` or `regular:-`. */
    private fun hear(phone: FakePhone, phoneLanguages: List<String>, app: List<String> = emptyList()): List<String> {
        val tried = mutableListOf<String>()
        var attempt: Attempt? = Attempt(onDevice = phone.hasOnDevice)
        while (attempt != null) {
            val supported = if (attempt.onDevice) phone.onDevice else phone.regular
            val language = if (attempt.withLanguage) Hearing.language(app, phoneLanguages, supported) else null
            if (!Hearing.starts(attempt, supported, language)) {
                attempt = Hearing.next(attempt, Errors.LANGUAGE_UNAVAILABLE, asked = false)
                continue
            }
            tried += "${if (attempt.onDevice) "on-device" else "regular"}:${language ?: "-"}"
            val error = when {
                attempt.onDevice && phone.onDeviceFails != null -> phone.onDeviceFails
                language == null -> if (attempt.onDevice || phone.regularDefault) null else Errors.LANGUAGE_UNAVAILABLE
                supported != null && language in supported -> null
                else -> Errors.LANGUAGE_NOT_SUPPORTED
            } ?: return tried + "heard"
            attempt = Hearing.next(attempt, error, asked = language != null)
        }
        return tried + "failed"
    }

    @Test fun aPixelInEstoniaHearsInEnglish() {
        val pixel = FakePhone(onDevice = listOf("en-US"), regular = google)
        assertEquals(listOf("on-device:en-US", "heard"), hear(pixel, listOf("en-EE")))
    }

    @Test fun anOnDeviceRecognizerWithoutThePhonesLanguageIsSkipped() {
        val pixel = FakePhone(onDevice = listOf("en-US"), regular = google)
        assertEquals(listOf("regular:ru-RU", "heard"), hear(pixel, listOf("ru-EE")))
        // No recognizer has it: the regular one, in the language chosen for voice typing.
        assertEquals(listOf("regular:-", "heard"), hear(pixel, listOf("et-EE")))
        // One that can't say is tried.
        assertEquals(listOf("on-device:-", "heard"), hear(FakePhone(onDevice = null, regular = google), listOf("et-EE")))
    }

    @Test fun aLanguageErrorTriesOnceWithoutTheLanguage() {
        // It said it had ru-EE but hasn't: once more in its own.
        val liar = FakePhone(onDevice = null, regular = null, hasOnDevice = false)
        assertEquals(listOf("regular:ru-EE", "regular:-", "heard"), hear(liar, listOf("en-EE"), app = listOf("ru-EE")))
        // Not even its own: it fails, once.
        val none = FakePhone(onDevice = null, regular = null, hasOnDevice = false, regularDefault = false)
        assertEquals(listOf("regular:ru-EE", "regular:-", "failed"), hear(none, listOf("en-EE"), app = listOf("ru-EE")))
        assertEquals(listOf("regular:-", "failed"), hear(none, listOf("en-EE")))
    }

    @Test fun anOnDeviceRecognizerThatFailsGivesWayToTheRegularOne() {
        val broken = FakePhone(onDevice = listOf("en-US"), regular = google, onDeviceFails = Errors.CLIENT)
        assertEquals(listOf("on-device:en-US", "regular:en-US", "heard"), hear(broken, listOf("en-EE")))
        val gone = FakePhone(onDevice = listOf("en-US"), regular = google, onDeviceFails = Errors.SERVER_DISCONNECTED)
        assertEquals(listOf("on-device:en-US", "regular:en-US", "heard"), hear(gone, listOf("en-EE")))
        // Without the language downloaded: the language, then without, then the regular one.
        val notDownloaded = FakePhone(onDevice = null, regular = google, onDeviceFails = Errors.LANGUAGE_UNAVAILABLE)
        assertEquals(listOf("on-device:-", "regular:en-US", "heard"), hear(notDownloaded, listOf("en-EE")))
        val claimed = FakePhone(onDevice = null, regular = google, onDeviceFails = Errors.LANGUAGE_NOT_SUPPORTED)
        assertEquals(listOf("on-device:ru-EE", "on-device:-", "regular:ru-RU", "heard"), hear(claimed, listOf("en-EE"), app = listOf("ru-EE")))
    }

    @Test fun skippedCountsAsTheLanguageMissing() {
        assertEquals(false, Hearing.starts(Attempt(onDevice = true), listOf("en-US"), null))
        assertEquals(true, Hearing.starts(Attempt(onDevice = true), listOf("en-US"), "en-US"))
        assertEquals(true, Hearing.starts(Attempt(onDevice = true), null, null))
        assertEquals(true, Hearing.starts(Attempt(onDevice = true, withLanguage = false), listOf("en-US"), null))
        assertEquals(true, Hearing.starts(Attempt(onDevice = false), listOf("en-US"), null))
    }

    @Test fun nothingSaidOrNoMicrophoneKeepsTheOnDeviceRecognizer() {
        val onDevice = Attempt(onDevice = true)
        assertNull(Hearing.next(onDevice, Errors.NO_MATCH, asked = true))
        assertNull(Hearing.next(onDevice, Errors.SPEECH_TIMEOUT, asked = true))
        assertNull(Hearing.next(onDevice, Errors.INSUFFICIENT_PERMISSIONS, asked = true))
    }

    @Test fun theRegularRecognizerFailingForAnyOtherReasonFails() {
        assertNull(Hearing.next(Attempt(onDevice = false), Errors.NETWORK, asked = true))
        assertNull(Hearing.next(Attempt(onDevice = false, withLanguage = false), Errors.LANGUAGE_NOT_SUPPORTED, asked = false))
        assertNull(Hearing.next(Attempt(onDevice = false), Errors.LANGUAGE_NOT_SUPPORTED, asked = false))
    }
}
