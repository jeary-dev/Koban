package dev.jeary.koban.domain.action

import java.text.Normalizer

/**
 * Makes free text (a reason typed into Discord, a category name, a staff name) safe to put
 * inside a console command. The text can only ever be words in its own slot:
 *
 * 1. NFKC normalisation first, so look-alikes such as a full-width at sign (U+FF20) or slash (U+FF0F) become the
 *    characters they imitate and are caught by the rules below.
 * 2. Only letters, digits, spaces and `. , ! ? ' ( ) # : - _` survive. Everything else becomes a
 *    space. That removes newlines and control characters (a second command), `§` and `&` colour
 *    codes, `@` (vanilla selectors such as `@a`), `"` and `\` (Brigadier quoting), `{ } %`
 *    (other plugins' placeholders), `/` and `;`.
 * 3. Leading `-` is removed from every word, so text can't become a flag such as LiteBans' `-s`
 *    (silent) or `-ip`.
 * 4. Runs of whitespace collapse to one space, and the result is capped at a length.
 */
object PlaceholderEscaper {

    private const val EXTRA_ALLOWED = ".,!?'()#:-_"

    fun text(raw: String, maxLength: Int): String {
        require(maxLength > 0) { "maxLength must be positive" }
        val normalised = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        val kept = StringBuilder(normalised.length)
        var i = 0
        while (i < normalised.length) {
            val cp = normalised.codePointAt(i)
            if (isAllowed(cp)) kept.appendCodePoint(cp) else kept.append(' ')
            i += Character.charCount(cp)
        }
        val words = kept.split(' ')
            .map { it.trimStart('-') }
            .filter { it.isNotEmpty() }
        return truncate(words.joinToString(" "), maxLength).trim()
    }

    private fun isAllowed(cp: Int): Boolean =
        Character.isLetterOrDigit(cp) || cp == ' '.code || (cp < 128 && EXTRA_ALLOWED.indexOf(cp.toChar()) >= 0)

    /** Cuts at a code point boundary, so a surrogate pair is never split. */
    private fun truncate(text: String, maxLength: Int): String {
        if (text.length <= maxLength) return text
        var end = maxLength
        if (Character.isLowSurrogate(text[end]) && end > 0 && Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end)
    }
}
