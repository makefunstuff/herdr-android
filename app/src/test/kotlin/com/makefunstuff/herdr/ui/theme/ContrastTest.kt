package com.makefunstuff.herdr.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.makefunstuff.herdr.core.api.AgentStatus
import org.junit.Assert.assertTrue
import org.junit.Test

/** WCAG contrast of the text/background pairs the app actually uses (AA: 4.5:1 for text). */
class ContrastTest {
    private fun ratio(a: Color, b: Color): Double {
        val x = a.luminance().toDouble() + 0.05; val y = b.luminance().toDouble() + 0.05
        return maxOf(x, y) / minOf(x, y)
    }

    private fun check(name: String, s: ColorScheme) {
        val bgs = listOf(s.surface, s.background, s.surfaceContainerLow, s.surfaceContainer, s.surfaceContainerHigh)
        val fails = mutableListOf<String>()
        for ((i, bg) in bgs.withIndex()) {
            fun need(label: String, fg: Color, min: Double = 4.5) {
                val r = ratio(fg, bg)
                println("$name bg$i $label %.2f".format(r))
                if (r < min) fails += "$name $label on bg$i = %.2f < $min".format(r)
            }
            need("onSurface", s.onSurface); need("onSurfaceVariant", s.onSurfaceVariant)
            need("primary", s.primary); need("error", s.error)
            AgentStatus.entries.forEach { need("status ${it.name}", com.makefunstuff.herdr.ui.design.readable(it.color(), it.color().copy(alpha = 0.16f).compositeOver(bg), s.onSurface)) }
        }
        listOf("primary" to (s.onPrimary to s.primary), "primaryContainer" to (s.onPrimaryContainer to s.primaryContainer),
            "secondaryContainer" to (s.onSecondaryContainer to s.secondaryContainer), "errorContainer" to (s.onErrorContainer to s.errorContainer))
            .forEach { (n, p) -> val r = ratio(p.first, p.second); println("$name $n %.2f".format(r)); if (r < 4.5) fails += "$name $n = %.2f".format(r) }
        assertTrue(fails.joinToString("\n"), fails.isEmpty())
    }

    @Test fun dark() = check("dark", Dark)
    @Test fun light() = check("light", Light)
}
