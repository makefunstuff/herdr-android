// Android's Font(resId) for the desktop: the same files, read from the classpath.
@file:Suppress("unused")

package androidx.compose.ui.text.font

fun Font(resId: Int, weight: FontWeight = FontWeight.Normal, style: FontStyle = FontStyle.Normal): Font {
    val name = com.makefunstuff.herdr.R.font.file(resId)
    val bytes = Thread.currentThread().contextClassLoader.getResourceAsStream(name)?.readBytes() ?: error("font $name is missing")
    return androidx.compose.ui.text.platform.Font("$name-${weight.weight}", bytes, weight, style)
}
