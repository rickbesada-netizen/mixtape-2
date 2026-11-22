package app.mixtape.core.text

import android.os.Build

/**
 * Writes names from other alphabets (Arabic, Russian, Greek, Hebrew, Hindi, Japanese, Korean...)
 * in English letters, so they're easy to read and to search, e.g. "مكسرات" -> "mksrat".
 *
 * Uses Android's built-in transliterator (Android 10+). Older phones get a simple Arabic table.
 * Arabic writes most vowels as marks that are usually left out, so results are a close guide to
 * the sound rather than an exact spelling; rename the song by hand to perfect it.
 */
object Latinize {
    private val NON_LATIN = Regex("[\\p{L}&&[^\\p{IsLatin}]]")

    /** True if the text has letters from a non-English alphabet. */
    fun needs(text: String): Boolean = NON_LATIN.containsMatchIn(text)

    /** The text in English letters, or null if it's already in them. */
    fun of(text: String): String? {
        if (!needs(text)) return null
        val out = if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                android.icu.text.Transliterator.getInstance("Any-Latin; Latin-ASCII").transliterate(text)
            }.getOrNull() ?: fallback(text)
        } else fallback(text)
        return out
            .replace(Regex("[ʿʾ'`]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .split(" ")
            .joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercaseChar() } }
            .takeIf { it.isNotBlank() && it != text }
    }

    private val ARABIC = mapOf(
        'ا' to "a", 'أ' to "a", 'إ' to "i", 'آ' to "aa", 'ب' to "b", 'ت' to "t", 'ث' to "th", 'ج' to "j",
        'ح' to "h", 'خ' to "kh", 'د' to "d", 'ذ' to "dh", 'ر' to "r", 'ز' to "z", 'س' to "s", 'ش' to "sh",
        'ص' to "s", 'ض' to "d", 'ط' to "t", 'ظ' to "z", 'ع' to "a", 'غ' to "gh", 'ف' to "f", 'ق' to "q",
        'ك' to "k", 'ل' to "l", 'م' to "m", 'ن' to "n", 'ه' to "h", 'و' to "w", 'ي' to "y", 'ى' to "a",
        'ة' to "a", 'ء' to "", 'ؤ' to "u", 'ئ' to "i", 'پ' to "p", 'چ' to "ch", 'ڤ' to "v", 'گ' to "g",
        '،' to ",", '؟' to "?", 'ـ' to "",
    )

    private fun fallback(text: String): String = buildString {
        for (c in text) {
            when {
                c in ARABIC -> append(ARABIC[c])
                c in 'ً'..'ْ' -> Unit // vowel marks
                else -> append(c)
            }
        }
    }
}
