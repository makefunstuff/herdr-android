@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.core.content.res

import android.content.Context
import android.graphics.Typeface

object ResourcesCompat {
    fun getFont(context: Context, id: Int): Typeface? = Typeface(com.makefunstuff.herdr.R.font.file(id))
}
