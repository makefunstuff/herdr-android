// Stand-in for app/…/ui/artifacts/ArtifactView.kt (Android's WebView, which Compose on the desktop can't
// show): the same API, drawing the page's headings, paragraphs and list items in Shepherdr's colours, as
// its frame would at a phone's width. Its scripts don't run.
package com.makefunstuff.herdr.ui.artifacts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.makefunstuff.herdr.core.shepherdr.ArtifactMessage
import com.makefunstuff.herdr.core.shepherdr.ArtifactSendGate

class ArtifactController {
    /** The scripts the screen ran in the page, in order. */
    val ran = ArrayList<String>()

    fun run(script: String) {
        ran += script
    }
}

@Composable
fun ArtifactView(
    page: String,
    dark: Boolean,
    markup: Boolean,
    background: Color,
    font: ByteArray?,
    gate: ArtifactSendGate,
    controller: ArtifactController,
    onMessage: (ArtifactMessage) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Shepherdr's --fg and --fg2 (server/frame.ts).
    val fg = if (dark) Color(0xFFFAFAFA) else Color(0xFF09090B)
    val fg2 = if (dark) Color(0xFFA1A1AA) else Color(0xFF52525B)
    val blocks = remember(page) { blocks(page) }
    Column(modifier.fillMaxSize().background(background).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { (tag, text) ->
            when (tag) {
                "h1" -> Text(text, style = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, color = fg))
                "h2" -> Text(text, style = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, color = fg), modifier = Modifier.padding(top = 6.dp))
                "li" -> Row {
                    Text("•", style = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, color = fg2), modifier = Modifier.width(18.dp))
                    Text(text, style = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, color = fg))
                }
                else -> Text(text, style = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, color = fg2))
            }
        }
    }
}

/** The page's body as (tag, words) for its h1, h2, p and li, in order. */
private fun blocks(page: String): List<Pair<String, String>> {
    val body = page.substringAfter("<body", page).replace(Regex("<script[\\s\\S]*?</script>|<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
    return Regex("<(h1|h2|p|li)(?:\\s[^>]*)?>([\\s\\S]*?)</\\1>", RegexOption.IGNORE_CASE).findAll(body).map { m ->
        m.groupValues[1].lowercase() to m.groupValues[2].replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&middot;", "·").trim()
    }.filter { it.second.isNotEmpty() }.toList()
}
