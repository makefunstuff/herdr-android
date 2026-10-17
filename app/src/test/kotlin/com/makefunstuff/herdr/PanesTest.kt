package com.makefunstuff.herdr

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PanesTest {
    @Test fun narrowestWideWindowLeavesTheMiddleRoom() {
        val (left, right) = Panes.widths(WIDE, board = true)
        assertEquals(256.dp, left)
        assertEquals(280.dp, right)
        // The chat or terminal keeps more than a third of the window.
        assert(WIDE - left - right > WIDE / 3) { "middle is ${WIDE - left - right}" }
    }

    @Test fun bigWindowsGiveTheRestToTheMiddle() {
        val (left, right) = Panes.widths(1920.dp, board = true)
        assertEquals(360.dp, left)
        assertEquals(420.dp, right)
    }

    @Test fun noBoardNoRightPane() {
        assertEquals(0.dp, Panes.widths(1280.dp, board = false).second)
    }

    @Test fun boardRoutes() {
        assertEquals("m1" to null, Routes.boardOf("board/m1"))
        assertNull(Routes.boardOf("agent/m1/w1:p1"))
        assertNull(Routes.boardOf(Routes.AGENTS))
    }
}
