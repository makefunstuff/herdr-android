@file:Suppress("unused", "UNUSED_PARAMETER")

package android.net

import java.net.URLDecoder
import java.net.URLEncoder

class Uri private constructor(private val text: String) {
    val lastPathSegment: String? get() = text.substringAfterLast('/').ifEmpty { null }
    override fun toString() = text

    companion object {
        fun parse(text: String) = Uri(text)

        /** Like Android's: everything but letters, digits and `_-!.~'()*` is percent-encoded. */
        fun encode(s: String): String = s.let {
            URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20").replace("%21", "!").replace("%27", "'")
                .replace("%28", "(").replace("%29", ")").replace("%7E", "~")
        }

        fun decode(s: String): String = s.let { URLDecoder.decode(it.replace("+", "%2B"), Charsets.UTF_8) }
    }
}
