@file:Suppress("unused")

package androidx.compose.foundation.layout

import androidx.compose.runtime.Composable

/** No on-screen keyboard on the desktop. */
val WindowInsets.Companion.isImeVisible: Boolean
    @Composable get() = false
