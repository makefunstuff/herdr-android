// What TalkBack offers on an element, drawn over a shot: its custom actions, read from the app's
// real semantics, in a list like TalkBack's own Actions menu (TalkBack is a system service the
// harness can't run), with TalkBack's focus outline around the element.
package preview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object TalkBack {
    /** The element TalkBack is on (its bounds in the window, px) and the actions it offers there. */
    var shown by mutableStateOf<Pair<Rect, List<String>>?>(null)
}

/** The merged element showing [text], as TalkBack focuses it (a card, a row), and its custom actions. */
private fun ComposeUiTest.actionsOf(text: String) =
    onAllNodes(hasText(text, substring = true)).onFirst().fetchSemanticsNode().let { node ->
        node to node.config.getOrNull(SemanticsActions.CustomActions).orEmpty()
    }

/** Opens TalkBack's Actions menu on the element showing [text]; the actions are printed too, for the log. */
fun ComposeUiTest.talkback(text: String) {
    val (node, actions) = actionsOf(text)
    println("TalkBack actions on “$text”: " + actions.joinToString(" | ") { it.label })
    TalkBack.shown = node.boundsInWindow to actions.map { it.label }
    settle()
}

/** Carries out TalkBack's action [label] on the element showing [text], as picking it from that menu does. */
fun ComposeUiTest.talkbackDo(text: String, label: String) {
    val (_, actions) = actionsOf(text)
    val action = actions.firstOrNull { it.label == label } ?: error("No TalkBack action “$label” on “$text”: ${actions.map { it.label }}")
    runOnUiThread { action.action() }
    settle()
}

/** TalkBack's focus outline and its Actions menu, as Android draws them (its own look, not the app's). */
@Composable
fun TalkBackOverlay(dark: Boolean) {
    val (bounds, actions) = TalkBack.shown ?: return
    val d = LocalDensity.current
    val sheet = if (dark) Color(0xFF2B2930) else Color(0xFFF3EDF7)
    val ink = if (dark) Color(0xFFE6E0E9) else Color(0xFF1D1B20)
    val dim = if (dark) Color(0xFFCAC4D0) else Color(0xFF49454F)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)))
        with(d) {
            Box(
                Modifier.offset(bounds.left.toDp() - 2.dp, bounds.top.toDp() - 2.dp)
                    .size(bounds.width.toDp() + 4.dp, bounds.height.toDp() + 4.dp)
                    .border(3.dp, Color(0xFF4CAF50), RoundedCornerShape(6.dp)),
            )
        }
        Column(
            Modifier.align(Alignment.Center).padding(horizontal = 40.dp).widthIn(max = 360.dp).fillMaxWidth()
                .background(sheet, RoundedCornerShape(28.dp)).padding(vertical = 20.dp),
        ) {
            Text("Actions", color = ink, fontSize = 22.sp, modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp))
            actions.forEach {
                Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 24.dp), contentAlignment = Alignment.CenterStart) {
                    Text(it, color = ink, fontSize = 16.sp)
                }
            }
            if (actions.isEmpty()) Text("No actions", color = dim, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 24.dp))
        }
    }
}
