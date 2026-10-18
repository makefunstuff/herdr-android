package com.makefunstuff.herdr.core

import com.makefunstuff.herdr.core.terminal.BarKey
import com.makefunstuff.herdr.core.terminal.CursorDrag
import com.makefunstuff.herdr.core.terminal.Hold
import com.makefunstuff.herdr.core.terminal.KeyBar
import com.makefunstuff.herdr.core.terminal.KeyCodes
import com.makefunstuff.herdr.core.terminal.Mods
import com.makefunstuff.herdr.core.terminal.Sticky
import com.makefunstuff.herdr.core.terminal.TerminalQuote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The terminal's key bar: its keys as kept, what they send, sticky modifiers, rows, quoting. */
class KeyBarTest {
    private fun send(spec: String, held: Mods = Mods.NONE) = (BarKey.parse(spec) as BarKey.Send).bytes(held)

    @Test
    fun readsAndWritesKeys() {
        for (spec in KeyBar.DEFAULT + KeyBar.CATALOG.flatMap { it.second } + listOf("C-M-x", "C-Left", "S-F5", "F12")) {
            val key = BarKey.parse(spec)
            assertTrue(key != null, spec)
            assertEquals(key, BarKey.parse(key.spec), spec)
        }
        assertEquals(BarKey.Send("r", Mods(ctrl = true)), BarKey.parse("C-r"))
        assertEquals(BarKey.Send("-", Mods(ctrl = true)), BarKey.parse("C--"))
        assertEquals(BarKey.Send("-"), BarKey.parse("-"))
        assertEquals(BarKey.Send("Up"), BarKey.parse("up"))
        // Shift and a letter is the capital letter.
        assertEquals(BarKey.Send("A", Mods(alt = true)), BarKey.parse("M-S-a"))
        assertEquals(BarKey.Modifier(BarKey.Mod.Ctrl), BarKey.parse("@ctrl"))
        assertNull(BarKey.parse("@nope"))
        assertNull(BarKey.parse("C-Nope"))
        assertNull(BarKey.parse(""))
        assertNull(BarKey.parse(" "))
        assertEquals(15, KeyBar.keys(KeyBar.DEFAULT + "Esc" + "bogus").size, "unknown and repeated keys are left out")
    }

    @Test
    fun keysSendWhatXtermSends() {
        assertEquals("\u0003", send("C-c"))
        assertEquals("\u0012", send("C-r"))
        assertEquals("\u0017", send("C-w"))
        assertEquals("\u001bb", send("M-b"))
        assertEquals("\u001b.", send("M-."))
        assertEquals("\u001b[Z", send("S-Tab"))
        assertEquals("\u001b", send("Esc"))
        assertEquals("\t", send("Tab"))
        assertEquals("\u001b[A", send("Up"))
        assertEquals("\u001b[1;5D", send("C-Left"))
        assertEquals("\u001b[1;3C", send("M-Right"))
        assertEquals("\u001b[1;2H", send("S-Home"))
        assertEquals("\u001bOP", send("F1"))
        assertEquals("\u001b[1;2R", send("S-F3"))
        assertEquals("\u001b[15~", send("F5"))
        assertEquals("\u001b[24;5~", send("C-F12"))
        assertEquals("\u001b[5~", send("PgUp"))
        assertEquals("\u001b[3;5~", send("C-Del"))
        assertEquals("|", send("|"))
        assertEquals(" ", send("Space"))
        assertEquals("\u0000", send("C-Space"))
        // Sticky modifiers add to the key's own.
        assertEquals("\u001b[1;5A", send("Up", Mods(ctrl = true)))
        assertEquals("\u001b\u0012", send("C-r", Mods(alt = true)))
        assertEquals("A", send("a", Mods(shift = true)))
        assertEquals("\u001b[1;6B", KeyCodes.encode("Down", Mods(ctrl = true, shift = true)))
    }

    @Test
    fun stickyModifiersArmThenLock() {
        var s = Sticky()
        s = s.tapped(BarKey.Mod.Ctrl)
        assertEquals(Hold.Once, s.ctrl)
        assertEquals(Mods(ctrl = true), s.mods)
        assertEquals(Sticky(), s.used(), "an armed one is used by one key")
        s = s.tapped(BarKey.Mod.Ctrl).tapped(BarKey.Mod.Alt)
        assertEquals(Hold.Locked, s.ctrl)
        s = s.used()
        assertEquals(Sticky(ctrl = Hold.Locked), s, "a kept one stays")
        assertEquals(Sticky(), s.tapped(BarKey.Mod.Ctrl), "a third tap lets go")
    }

    @Test
    fun keysFillRows() {
        val keys = KeyBar.keys(KeyBar.DEFAULT)
        val rows = KeyBar.rows(keys, KeyBar.perRow(412f - 8f))
        assertEquals(listOf(8, 8), rows.map { it.size }, "two rows on a phone, the edit key last")
        assertNull(rows.last().last())
        assertEquals(8, KeyBar.perRow(360f - 8f), "a smaller phone still has eight a row")
        assertEquals(5, KeyBar.perRow(200f))
        assertEquals(17, KeyBar.perRow(840f - 8f), "a tablet: the whole bar in a row")
        assertEquals(listOf(8, 7), KeyBar.rows(KeyBar.F_PAGE, 8, edit = false).map { it.size })
        assertEquals("^R", KeyBar.label(BarKey.parse("C-r")!!))
        assertEquals("M-b", KeyBar.label(BarKey.parse("M-b")!!))
        assertEquals("⇧tab", KeyBar.label(BarKey.parse("S-Tab")!!))
        assertEquals("^←", KeyBar.label(BarKey.parse("C-Left")!!))
        assertEquals("Ctrl+R", KeyBar.name(BarKey.parse("C-r")!!))
        assertEquals("Up arrow", KeyBar.name(BarKey.parse("Up")!!))
        assertEquals("Pipe", KeyBar.name(BarKey.parse("|")!!))
        assertEquals("Search the shell's history", KeyBar.purpose(BarKey.parse("C-r")!!))
    }

    @Test
    fun spaceDragMovesTheCursor() {
        val drag = CursorDrag(stepX = 10f, stepY = 20f)
        assertEquals("", drag.moved(4f, 0f))
        assertEquals("\u001b[C", drag.moved(7f, 0f), "a cell once the finger has gone a step")
        assertEquals("\u001b[D\u001b[D", drag.moved(-21f, 0f))
        assertEquals("\u001b[B", drag.moved(0f, 25f))
        assertEquals("\u001b[A\u001b[A", drag.moved(0f, -45f))
    }

    @Test
    fun quotesSelectedText() {
        assertEquals(
            "From the terminal (shell on devbox):\n```\n FAIL  test/jobs.test.ts\n   expected 200, got 504\n```",
            TerminalQuote.of("\n FAIL  test/jobs.test.ts   \n   expected 200, got 504\n\n", "shell on devbox"),
        )
        assertEquals("````\nuse ``` here\n````", TerminalQuote.of("use ``` here"), "a longer fence than any inside")
        assertEquals("", TerminalQuote.of(" \n "))
    }
}
