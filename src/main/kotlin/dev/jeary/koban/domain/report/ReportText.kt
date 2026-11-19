package dev.jeary.koban.domain.report

/**
 * Cleans text people type (report details, notes) before it's stored. It keeps what they wrote,
 * minus control characters, `§` colour codes (sign and code letter) and runs of whitespace, and
 * caps the length.
 * Rendering escapes it again for each place it's shown (MiniMessage in game, Markdown on Discord).
 */
object ReportText {

    const val MAX_DETAILS = 256
    const val MAX_NOTE = 1000

    fun clean(raw: String?, maxLength: Int): String? {
        if (raw == null) return null
        val kept = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            if (cp == '§'.code) {
                // A colour code is two characters: drop the code letter with the sign.
                i += 1
                if (i < raw.length) i += Character.charCount(raw.codePointAt(i))
                continue
            }
            when {
                Character.isWhitespace(cp) || Character.isISOControl(cp) -> kept.append(' ')
                Character.getType(cp) == Character.FORMAT.toInt() -> Unit // zero-width and bidi controls
                else -> kept.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        val collapsed = kept.toString().replace(SPACES, " ").trim()
        if (collapsed.isEmpty()) return null
        if (collapsed.length <= maxLength) return collapsed
        var end = maxLength - 1
        if (end > 0 && Character.isHighSurrogate(collapsed[end - 1])) end--
        return collapsed.substring(0, end).trimEnd() + "…"
    }

    private val SPACES = Regex(" {2,}")
}
