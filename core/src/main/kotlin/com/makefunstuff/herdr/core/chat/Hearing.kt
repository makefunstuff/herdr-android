package com.makefunstuff.herdr.core.chat

import com.makefunstuff.herdr.core.chat.Dictation.Errors

/**
 * Which of the phone's speech recognizers hears a voice message, and in which language.
 *
 * A recognizer asked for a language it doesn't have (the phone's locale as is, `en-EE`, which no
 * recognizer has) refuses to start, so the language is one the recognizer says it has, the closest
 * to the app's language and then the phone's ([language]); with none of them, or when it can't say,
 * none is asked for and the recognizer hears in its own (the one chosen for voice typing, as Gboard
 * does), or, for an on-device one that has none of them, the regular one goes instead ([starts]).
 * What fails is tried again another way ([next]): without the language, then on the phone's regular
 * recognizer instead of the on-device one.
 */
object Hearing {
    /**
     * One way to start hearing: the [onDevice] recognizer or the phone's regular one, asked for a
     * language ([withLanguage], the one [language] picks for that recognizer) or in its own.
     */
    data class Attempt(val onDevice: Boolean, val withLanguage: Boolean = true) {
        /**
         * The last way left: the regular recognizer in its own language, asked as plainly as Gboard
         * does (no language, online allowed). Every other way is told to prefer hearing on the phone.
         */
        val plain: Boolean get() = !onDevice && !withLanguage
        val offline: Boolean get() = !plain
    }

    /**
     * What to try after [attempt] failed with Android's [error], or null: it fails, and says why.
     * [asked]: a language was asked for (a language error without one is the recognizer's own).
     *
     * A language error: the same recognizer once more, in its own language; for the regular one
     * also when it was in its own already, as the [Attempt.plain] request, so the regular recognizer
     * always ends on that. The regular one told to prefer offline that couldn't reach its server:
     * the plain request too, once. The on-device recognizer failing for any other reason than
     * hearing nothing or having no microphone: the regular one, with the language again (it may
     * have one the on-device one hasn't).
     */
    fun next(attempt: Attempt, error: Int, asked: Boolean): Attempt? = when {
        isLanguage(error) && attempt.withLanguage && (asked || !attempt.onDevice) -> attempt.copy(withLanguage = false)
        !attempt.onDevice && !attempt.plain && error in NETWORK -> attempt.copy(withLanguage = false)
        attempt.onDevice && error !in KEEP_ON_DEVICE -> Attempt(onDevice = false)
        else -> null
    }

    /**
     * Whether [attempt] is worth starting with [language], picked from [supported] (see [language]). Not
     * the on-device recognizer when it says it has none of the languages wanted: its own is likely
     * another (the phone's, which it hasn't either), so the regular one goes instead, which hears in
     * the one chosen for voice typing. Skipped, it counts as [Errors.LANGUAGE_UNAVAILABLE].
     */
    fun starts(attempt: Attempt, supported: List<String>?, language: String?): Boolean =
        !(attempt.onDevice && attempt.withLanguage && language == null && !supported.isNullOrEmpty())

    private val NETWORK = setOf(Errors.NETWORK, Errors.NETWORK_TIMEOUT, Errors.SERVER, Errors.SERVER_DISCONNECTED)

    /** Errors the on-device recognizer is still right after: nothing was said, or the microphone isn't allowed. */
    private val KEEP_ON_DEVICE = setOf(Errors.NO_MATCH, Errors.SPEECH_TIMEOUT, Errors.INSUFFICIENT_PERMISSIONS)

    fun isLanguage(error: Int) = error == Errors.LANGUAGE_NOT_SUPPORTED || error == Errors.LANGUAGE_UNAVAILABLE

    /**
     * The language to ask a recognizer for (a tag as it gives it), or null for its own.
     *
     * [app]: the languages chosen for this app in the phone's settings (usually none), [phone]: the
     * phone's, in order. [supported]: the languages the recognizer has, null when it can't say:
     * then only a language chosen for the app is asked for, as is (if the recognizer hasn't it, it
     * is tried again without, see [next]).
     */
    fun language(app: List<String>, phone: List<String>, supported: List<String>?): String? {
        if (supported.isNullOrEmpty()) return app.firstOrNull()
        return (app + phone).firstNotNullOfOrNull { best(it, supported) }
    }

    /**
     * Of [supported], the one closest to [wanted]: the same tag, else the same language (and script)
     * in its own country or without one (`en-EE` → `en-US`, `ru-EE` → `ru-RU`), else in any country.
     */
    fun best(wanted: String, supported: List<String>): String? {
        val w = Tag.of(wanted) ?: return null
        val tags = supported.mapNotNull { s -> Tag.of(s)?.let { s to it } }
        tags.firstOrNull { it.second == w }?.let { return it.first }
        val same = tags.filter { it.second.language == w.language && (w.script == null || it.second.script == null || it.second.script == w.script) }
        val home = HOME[w.language] ?: w.language.uppercase()
        return (same.firstOrNull { it.second.region == null } ?: same.firstOrNull { it.second.region == home } ?: same.firstOrNull())?.first
    }

    /** The country a language is mostly spoken in where it isn't the language's own code (`de` → DE). */
    private val HOME = mapOf(
        "en" to "US", "ar" to "SA", "cs" to "CZ", "da" to "DK", "el" to "GR", "fa" to "IR", "he" to "IL",
        "hi" to "IN", "ja" to "JP", "ko" to "KR", "nb" to "NO", "sv" to "SE", "uk" to "UA", "vi" to "VN", "zh" to "CN",
    )

    /**
     * A language tag's parts, lowercase language and script, uppercase region: `en_us` and `en-US`
     * are the same. What follows them is left out (variants, and extensions such as Android 14's
     * regional preferences, `en-GB-u-mu-celsius`); Chinese without a script gets the one its country
     * writes (`zh-TW` is `zh-Hant-TW`).
     */
    private data class Tag(val language: String, val script: String?, val region: String?) {
        companion object {
            /**
             * Old codes some recognizers still give, as the new ones; Google's recognizer names Mandarin
             * `cmn`, Norwegian `no` (the phone `nb`) and Filipino `tl` (the phone `fil`).
             */
            private val RENAMED = mapOf("iw" to "he", "in" to "id", "ji" to "yi", "cmn" to "zh", "no" to "nb", "tl" to "fil")
            private val TRADITIONAL = setOf("TW", "HK", "MO")

            fun of(tag: String): Tag? {
                val parts = tag.trim().split('-', '_').filter { it.isNotEmpty() }.takeWhile { it.length > 1 }
                val first = parts.firstOrNull()?.lowercase()?.takeIf { it.length in 2..3 && it.all(Char::isLetter) } ?: return null
                val language = RENAMED[first] ?: first
                var rest = parts.drop(1)
                val script = rest.firstOrNull()?.takeIf { it.length == 4 && it.all(Char::isLetter) }?.lowercase()
                if (script != null) rest = rest.drop(1)
                val region = rest.firstOrNull()?.takeIf { (it.length == 2 && it.all(Char::isLetter)) || (it.length == 3 && it.all(Char::isDigit)) }?.uppercase()
                val written = script ?: if (language == "zh" && region != null) (if (region in TRADITIONAL) "hant" else "hans") else null
                return Tag(language, written, region)
            }
        }
    }
}
