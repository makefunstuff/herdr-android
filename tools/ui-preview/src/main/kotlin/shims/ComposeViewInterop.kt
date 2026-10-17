@file:Suppress("unused")

package androidx.compose.ui.viewinterop

import android.content.Context
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext

/** Makes the view once and draws it with its own [View.Content]; [update] runs on every composition, as on Android. */
@Composable
fun <T : View> AndroidView(factory: (Context) -> T, modifier: Modifier = Modifier, update: (T) -> Unit = {}) {
    val context = LocalContext.current
    val view = remember { factory(context) }
    SideEffect { update(view) }
    view.Content(modifier.onSizeChanged { view.setSize(it.width, it.height) })
}
