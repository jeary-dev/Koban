package dev.jeary.koban.domain.time

import java.time.Duration

/** Short human durations for messages: `45s`, `2m 5s`, `1h 30m`, `3d 4h`. */
object DurationText {

    /**
     * The two largest non-zero units, rounding the remainder up so a cooldown never reads as
     * shorter than it is (59.2 seconds shows as `1m`, not `59s`). Anything under a second is `1s`.
     */
    fun short(duration: Duration): String {
        if (duration.isNegative || duration.isZero) return "0s"
        var seconds = (duration.toMillis() + 999) / 1000
        val units = listOf("d" to 86_400L, "h" to 3_600L, "m" to 60L, "s" to 1L)
        val first = units.indexOfFirst { seconds >= it.second }
        val (bigName, bigSize) = units[first]
        if (first == units.lastIndex) return "${seconds}s"
        val (smallName, smallSize) = units[first + 1]
        // Round up to the smaller unit, then split.
        seconds = (seconds + smallSize - 1) / smallSize * smallSize
        val big = seconds / bigSize
        val small = (seconds % bigSize) / smallSize
        return if (small == 0L) "$big$bigName" else "$big$bigName $small$smallName"
    }
}
