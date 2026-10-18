package com.makefunstuff.herdr.ui.design

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The chats' text size keeps the phone's own font curve, and only multiplies what it gives. */
class TextScaleTest {
    /** A phone at font scale 2 with Android 14's non-linear curve (from its table): 16sp is 28dp, 24sp is 36dp. */
    private object Phone : Density {
        private val curve = mapOf(16f to 28f, 24f to 36f)
        override val density = 2.75f
        override val fontScale = 2f
        override fun TextUnit.toDp(): Dp = (curve[value] ?: (value * fontScale)).dp
        override fun Dp.toSp(): TextUnit = (curve.entries.firstOrNull { it.value == value }?.key ?: (value / fontScale)).sp
    }

    @Test fun atOneItIsThePhonesOwn() {
        assertSame(Phone, Phone.withTextScale(1f))
        // Not linear: 16sp is 28dp, not 32.
        assertEquals(28f, with(Phone.withTextScale(1f)) { 16.sp.toDp().value }, 0.001f)
    }

    @Test fun aLargerChatScalesWhatThePhoneGives() {
        val chat = Phone.withTextScale(1.3f)
        assertEquals(28f * 1.3f, with(chat) { 16.sp.toDp().value }, 0.001f)
        assertEquals(36f * 1.3f, with(chat) { 24.sp.toDp().value }, 0.001f)
        // In pixels, through the same curve; and back from dp to sp.
        assertEquals(36f * 1.3f * 2.75f, with(chat) { 24.sp.toPx() }, 0.01f)
        assertEquals(24f, with(chat) { (36f * 1.3f).dp.toSp().value }, 0.001f)
        // Lengths in dp stay as they were.
        assertEquals(2.75f * 10f, with(chat) { 10.dp.toPx() }, 0.001f)
    }
}
