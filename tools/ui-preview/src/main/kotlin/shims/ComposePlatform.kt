// Android-only parts of androidx.compose.ui.platform and .viewinterop, for the desktop preview.
@file:Suppress("unused")

package androidx.compose.ui.platform

import android.content.Context
import android.view.View
import androidx.compose.runtime.staticCompositionLocalOf

val LocalContext = staticCompositionLocalOf<Context> { error("The preview provides LocalContext") }

/** Stands for the Android view the composition lives in (only haptics are asked of it). */
val LocalView = staticCompositionLocalOf<View> { error("The preview provides LocalView") }

class Configuration(val screenWidthDp: Int, val screenHeightDp: Int)

val LocalConfiguration = staticCompositionLocalOf { Configuration(412, 892) }
