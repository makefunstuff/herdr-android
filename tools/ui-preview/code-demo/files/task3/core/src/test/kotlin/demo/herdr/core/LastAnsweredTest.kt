package demo.herdr.core

import demo.herdr.core.host.LastAnswered
import kotlin.test.Test
import kotlin.test.assertEquals

class LastAnsweredTest {
    @Test fun saysJustNowUnderAMinute() {
        assertEquals("just now", LastAnswered.words(0, 30_000))
        assertEquals("2 min ago", LastAnswered.words(0, 120_000))
        assertEquals("never answered", LastAnswered.words(null, 0))
    }
}
