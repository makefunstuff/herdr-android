package com.makefunstuff.herdr.data

import com.makefunstuff.herdr.core.code.ReviewNote
import com.makefunstuff.herdr.ui.code.CodeRow
import com.makefunstuff.herdr.ui.code.choose
import com.makefunstuff.herdr.ui.code.lineSpan
import com.makefunstuff.herdr.ui.code.linesLabel
import com.makefunstuff.herdr.ui.code.quote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class CodeStoreTest {
    private fun note(id: String) = ReviewNote(id, "devbox", "/src/app", path = "a.kt", start = 1, end = 2, quote = "x", text = "Fix it")

    @Test fun keepsCommentsAndTheViewAcrossARestart() {
        val file = Files.createTempDirectory("code").resolve("code.json").toFile()
        val store = CodeStore(file, now = { 42 })
        store.add(note("1"))
        store.add(note("2"))
        store.update(note("2").copy(text = "Better"))
        store.remove(listOf("1"))
        store.setView(CodeView(wrap = true, textSize = 99))

        val again = CodeStore(file)
        assertEquals(listOf("2"), again.notes.value.map { it.id })
        assertEquals("Better", again.notes.value.single().text)
        assertEquals(42L, again.notes.value.single().at)
        assertEquals(CodeView(wrap = true, textSize = CodeView.LARGEST), again.view.value)
    }

    @Test fun theCacheKeepsTheNewestAndForgetsACheckout() {
        val cache = CodeCache(size = 2)
        cache.put("files:a", 1)
        cache.put("files:b", 2)
        cache.put("status:b", 3)
        assertNull(cache.get<Int>("files:a"))
        cache.drop("files:")
        assertNull(cache.get<Int>("files:b"))
        assertEquals(3, cache.get<Int>("status:b"))
    }

    @Test fun choosingLines() {
        // A tap chooses a line, the next the lines between, the same single line again lets go.
        val (one, anchor) = choose(null, null, 5)
        assertEquals(5..5, one)
        assertEquals(2..5, choose(one, anchor, 2).first)
        assertNull(choose(5..5, 5, 5).first)

        val diff = listOf(
            CodeRow("h", "Line 3", emptyList(), CodeRow.Kind.Hunk),
            CodeRow("a", "keep", listOf(3, 3)),
            CodeRow("b", "old", listOf(4, null), CodeRow.Kind.Removed),
            CodeRow("c", "new", listOf(null, 4), CodeRow.Kind.Added),
        )
        assertEquals("Lines 3–4", linesLabel(diff, 0..3))
        assertEquals(" keep\n-old\n+new", quote(diff, 0..3))
        assertEquals(Triple(4, 4, true), lineSpan(diff, 2..2))
        assertEquals("Old line 4", linesLabel(diff, 2..2))
    }
}
