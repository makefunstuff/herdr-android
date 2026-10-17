package com.makefunstuff.herdr.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.makefunstuff.herdr.core.api.AgentStatus
import com.makefunstuff.herdr.core.board.BoardTone
import org.junit.Assert.assertTrue
import org.junit.Test

/** WCAG contrast of the text/background pairs the app actually uses (AA: 4.5:1 for text, 3:1 for marks). */
class ContrastTest {
    private fun ratio(a: Color, b: Color): Double {
        val x = a.luminance().toDouble() + 0.05; val y = b.luminance().toDouble() + 0.05
        return maxOf(x, y) / minOf(x, y)
    }

    private fun check(name: String, s: ColorScheme, h: HerdrColors) {
        val fails = mutableListOf<String>()
        fun need(label: String, fg: Color, bg: Color, min: Double = 4.5) {
            val r = ratio(fg, bg)
            println("$name $label %.2f".format(r))
            if (r < min) fails += "$name $label = %.2f < $min".format(r)
        }
        // The grounds text sits on: the screen, cards and sheets.
        val grounds = listOf("ground" to h.ground, "card" to h.card)
        for ((g, bg) in grounds) {
            need("text on $g", h.text, bg); need("muted on $g", h.muted, bg); need("faint on $g", h.faint, bg)
            need("accent on $g", h.accent, bg); need("primary on $g", s.primary, bg); need("error on $g", s.error, bg)
            AgentStatus.entries.forEach { need("status ${it.name} on $g", h.status(it), bg) }
            BoardTone.entries.forEach { need("tone ${it.name} on $g", h.tone(it), bg) }
            // A status badge: the colour's tint behind body text.
            BoardTone.entries.forEach { need("text on ${it.name} tint over $g", h.text, h.tint(h.tone(it)).compositeOver(bg)) }
        }
        // Fields and chips: text and placeholders on the field fill.
        need("text on field", h.text, h.field); need("muted on field", h.muted, h.field); need("faint on field", h.faint, h.field)
        // Status dots and icons on tiles: 3:1 is enough for marks.
        BoardTone.entries.forEach { need("tone ${it.name} mark on field", h.tone(it), h.field, 3.0) }
        // Inverse pills (primary buttons, selected chips) and the danger button.
        need("onPrimary on primary", s.onPrimary, s.primary)
        need("onError on error", s.onError, s.error)
        need("inverse toast", s.inverseOnSurface, s.inverseSurface)
        assertTrue(fails.joinToString("\n"), fails.isEmpty())
    }

    @Test fun dark() = check("dark", Dark, DarkHerdr)
    @Test fun light() = check("light", Light, LightHerdr)
}
