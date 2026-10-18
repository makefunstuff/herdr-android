// Stand-in for app/…/ui/preview/PageView.kt (Android's WebView, which Compose on the desktop can't
// show): the same API, drawing a fixed page, the website's install page as its dev server serves it.
package com.makefunstuff.herdr.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File

class PageController {
    var address by mutableStateOf("/install")
        internal set
    var canGoBack by mutableStateOf(false)
        internal set
    var error by mutableStateOf<String?>(null)
        internal set

    fun reload() {}

    fun back() {}

    /** A picture of the page: the same blocks the stand-in draws, at a phone's 2×. */
    fun screenshot(file: File): Boolean {
        val w = 824
        val h = 1400
        val surface = Surface.makeRasterN32Premul(w, h)
        val c = surface.canvas
        c.clear(0xFFFFFFFF.toInt())
        val ink = Paint().apply { color = 0xFF1A1A1A.toInt() }
        val grey = Paint().apply { color = 0xFFEDEDED.toInt() }
        c.drawRect(Rect.makeXYWH(40f, 60f, 360f, 44f), ink)
        c.drawRect(Rect.makeXYWH(40f, 140f, 500f, 24f), grey)
        c.drawRRect(org.jetbrains.skia.RRect.makeXYWH(40f, 200f, 744f, 84f, 16f), ink)
        c.drawRRect(org.jetbrains.skia.RRect.makeXYWH(40f, 320f, 744f, 320f, 16f), grey)
        c.drawRect(Rect.makeXYWH(40f, 680f, 520f, 28f), grey)
        c.drawRect(Rect.makeXYWH(40f, 740f, 660f, 28f), grey)
        val data = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG) ?: return false
        file.writeBytes(data.bytes)
        return true
    }
}

@Composable
fun PageView(url: String, phoneSize: Boolean, controller: PageController, modifier: Modifier = Modifier) {
    val ink = Color(0xFF1A1A1A)
    val grey = Color(0xFFEDEDED)
    val dim = Color(0xFF4A4A4A)
    @Composable
    fun page(scale: Float, wide: Boolean) {
        fun d(v: Int): Dp = (v * scale).dp
        fun s(v: Int): TextUnit = (v * scale).sp
        Column(Modifier.fillMaxSize().background(Color.White).padding(d(20)), verticalArrangement = Arrangement.spacedBy(d(14))) {
            if (wide) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(d(24))) {
                    Text("herdr", style = TextStyle(fontSize = s(20), fontWeight = FontWeight.Bold, color = ink))
                    listOf("Docs", "Install", "Changelog", "GitHub").forEach { Text(it, style = TextStyle(fontSize = s(16), color = dim)) }
                }
            }
            Text("Install herdr", style = TextStyle(fontSize = s(26), fontWeight = FontWeight.SemiBold, color = ink))
            Text("One line on macOS or Linux:", style = TextStyle(fontSize = s(15), color = dim))
            Box(Modifier.fillMaxWidth().background(ink, RoundedCornerShape(d(8))).padding(d(14))) {
                Text("curl -fsSL herdr.dev/install | sh", style = TextStyle(fontSize = s(15), fontFamily = FontFamily.Monospace, color = Color.White))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(d(16))) {
                Box(Modifier.weight(1f).height(d(160)).background(grey, RoundedCornerShape(d(8))))
                if (wide) Box(Modifier.weight(1f).height(d(160)).background(grey, RoundedCornerShape(d(8))))
            }
            Box(Modifier.width(d(260)).height(d(14)).background(grey))
            Box(Modifier.width(d(330)).height(d(14)).background(grey))
        }
    }
    Box(modifier.background(Color.White)) {
        // At desktop size the page lays out 1280 px wide and shows zoomed out, as the WebView does.
        if (phoneSize) page(1f, wide = false) else page(0.45f, wide = true)
    }
}
