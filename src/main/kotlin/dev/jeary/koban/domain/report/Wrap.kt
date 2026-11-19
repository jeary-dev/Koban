package dev.jeary.koban.domain.report

/** Word wrapping for item lore, where long lines run off the screen. */
object Wrap {

    /**
     * Splits [text] into lines of at most [width] characters, breaking at spaces. A single word
     * longer than [width] is cut. At most [maxLines] lines; if more were needed the last ends in "…".
     */
    fun lines(text: String, width: Int, maxLines: Int = Int.MAX_VALUE): List<String> {
        require(width > 0 && maxLines > 0)
        val out = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) out += current.toString()
            current.setLength(0)
        }
        for (word in text.split(' ').filter { it.isNotEmpty() }) {
            var rest = word
            while (rest.length > width) {
                flush()
                out += rest.take(width)
                rest = rest.drop(width)
            }
            if (current.isNotEmpty() && current.length + 1 + rest.length > width) flush()
            if (current.isNotEmpty()) current.append(' ')
            current.append(rest)
        }
        flush()
        if (out.size <= maxLines) return out
        val kept = out.take(maxLines).toMutableList()
        val last = kept.last()
        kept[kept.lastIndex] = (if (last.length >= width) last.dropLast(1) else last) + "…"
        return kept
    }
}
