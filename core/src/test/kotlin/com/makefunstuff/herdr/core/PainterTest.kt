package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.terminal.TerminalCanvas
import com.makefunstuff.herdr.core.terminal.TerminalEmulator
import com.makefunstuff.herdr.core.terminal.TerminalPainter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PainterTest {
    private class Recorder : TerminalCanvas {
        val rects = mutableListOf<List<Float>>()
        val texts = mutableListOf<Pair<String, Float>>()
        override fun fillRect(left: Float, top: Float, right: Float, bottom: Float, argb: Int) {
            rects += listOf(left, top, right, bottom)
        }
        override fun drawText(text: String, x: Float, baseline: Float, maxWidth: Float, argb: Int, bold: Boolean, italic: Boolean) {
            texts += text to maxWidth
        }
    }

    private fun paint(text: String, cols: Int = 4): Recorder {
        val emu = TerminalEmulator(cols, 2)
        emu.feed(text.toByteArray())
        return Recorder().also { TerminalPainter().paint(emu, it, 10f, 20f, 15f, showCursor = false) }
    }

    @Test
    fun boxLinesAreShapesThatSpanTheirCells() {
        val r = paint("──│█")
        assertTrue(r.texts.isEmpty(), "box and block characters are drawn as shapes, not text: ${r.texts}")
        // Two horizontal lines join edge to edge across cells 0..2.
        assertTrue(r.rects.any { it[0] == 0f && it[2] >= 5f }, "${r.rects}")
        assertTrue(r.rects.any { it[0] <= 15f && it[2] == 20f }, "${r.rects}")
        // The vertical line in cell 2 runs the full cell height; the full block fills cell 3.
        assertTrue(r.rects.any { it[1] == 0f && it[3] == 20f && it[0] in 20f..30f })
        assertTrue(r.rects.contains(listOf(30f, 0f, 40f, 20f)))
    }

    @Test
    fun wideGlyphsGetTwoCellsAndTextGetsOne() {
        val r = paint("a界")
        assertEquals(listOf("a" to 10f, "界" to 20f), r.texts)
    }
}
