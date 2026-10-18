// Stand-in for app/…/ui/common/CodeScanner.kt, which opens Google Play services' code scanner (its own
// screen, which the harness can't draw). It "reads" the code a scene puts in PreviewScanner.code.
package com.makefunstuff.herdr.ui.common

import android.content.Context

class ScannerUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

suspend fun scanCode(context: Context): String? = preview.PreviewScanner.code
