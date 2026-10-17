@file:Suppress("unused")

package com.makefunstuff.herdr

/** The app's resources the shared code names, as the preview has them (fonts from app/src/main/res/font). */
object R {
    object font {
        private val files = listOf("pretendard_regular", "pretendard_medium", "pretendard_semibold", "jetbrains_mono_regular")
        const val pretendard_regular = 0
        const val pretendard_medium = 1
        const val pretendard_semibold = 2
        const val jetbrains_mono_regular = 3

        fun file(id: Int): String = files[id] + ".ttf"
    }
}
