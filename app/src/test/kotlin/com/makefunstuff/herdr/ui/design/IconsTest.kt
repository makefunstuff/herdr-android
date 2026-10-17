package com.makefunstuff.herdr.ui.design

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every Lucide icon and every agent mark builds, and the icons keep Lucide's geometry. */
class IconsTest {
    private fun paths(g: VectorGroup): List<VectorPath> = g.flatMap {
        when (it) {
            is VectorPath -> listOf(it)
            is VectorGroup -> paths(it)
        }
    }

    @Test fun everyIconIsALucideStrokeIcon() {
        val icons = HerdrIcons::class.java.declaredMethods
            .filter { it.name.startsWith("get") && it.returnType == ImageVector::class.java && it.parameterCount == 0 }
            .map { it.invoke(HerdrIcons) as ImageVector }
        assertTrue("found ${icons.size} icons", icons.size > 50)
        icons.forEach { icon ->
            assertEquals(icon.name, 24f, icon.viewportWidth)
            val p = paths(icon.root)
            assertTrue(icon.name, p.isNotEmpty())
            p.forEach { path ->
                assertNull("${icon.name} has no fill", path.fill)
                assertEquals("${icon.name} keeps Lucide's 2-unit stroke", 2f, path.strokeLineWidth)
            }
        }
    }

    @Test fun agentsGetTheirMarks() {
        listOf("claude", "codex", "pi", "opencode", "omp", "cursor", "amp", "copilot", "droid", "gemini", "grok").forEach {
            val art = agentMarkArt(it)
            assertNotNull(it, art)
            assertTrue(it, paths(art!!.vector.root).isNotEmpty())
        }
        assertTrue("codex rides the text colour", agentMarkArt("codex")!!.mono)
        assertTrue("claude keeps its brand tile", !agentMarkArt("Claude")!!.mono)
        assertNull("an agent nobody drew gets its initial", agentMarkArt("hermes"))
    }
}
