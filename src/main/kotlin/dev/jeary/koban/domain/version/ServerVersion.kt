package dev.jeary.koban.domain.version

/**
 * A Minecraft or plugin version as plain numbers, comparable across both numbering schemes:
 * `1.20.6`, `1.21.11` and the year-based `26.1`, `26.1.2`, `26.3`. Nothing assumes a `1.`
 * prefix. Missing parts count as zero, so `26.1` equals `26.1.0`.
 *
 * [parse] accepts what servers and release pages actually print: `1.21.4-R0.1-SNAPSHOT`
 * (Bukkit), `26.1.2.build.74-stable` (Paper), `v1.0.0` (a Git tag), `26.3-rc-3`.
 */
class ServerVersion private constructor(val parts: List<Int>, val qualifier: String?) : Comparable<ServerVersion> {

    val major: Int get() = parts[0]
    val minor: Int get() = parts.getOrElse(1) { 0 }
    val patch: Int get() = parts.getOrElse(2) { 0 }

    /** A pre-release such as `-rc-3` or `-pre2`, which sorts before the release itself. */
    val isPreRelease: Boolean get() = qualifier != null

    override fun compareTo(other: ServerVersion): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val cmp = parts.getOrElse(i) { 0 }.compareTo(other.parts.getOrElse(i) { 0 })
            if (cmp != 0) return cmp
        }
        return when {
            qualifier == other.qualifier -> 0
            qualifier == null -> 1
            other.qualifier == null -> -1
            else -> qualifier.compareTo(other.qualifier)
        }
    }

    fun isAtLeast(other: ServerVersion): Boolean = this >= other

    override fun equals(other: Any?): Boolean = other is ServerVersion && compareTo(other) == 0

    override fun hashCode(): Int = parts.dropLastWhile { it == 0 }.hashCode() * 31 + (qualifier?.hashCode() ?: 0)

    override fun toString(): String = parts.joinToString(".") + (qualifier?.let { "-$it" } ?: "")

    companion object {
        private val NUMBERS = Regex("""^v?(\d+(?:\.\d+)*)""", RegexOption.IGNORE_CASE)
        private val PRE_RELEASE = Regex("""^-?((?:rc|pre|beta|alpha|snapshot)[-.]?\d*)""", RegexOption.IGNORE_CASE)

        /** Bukkit's `-R0.1-SNAPSHOT` is an API revision marker, not a pre-release. */
        private val BUKKIT_SUFFIX = Regex("""-R\d+(\.\d+)*(-SNAPSHOT)?$""", RegexOption.IGNORE_CASE)

        fun parse(text: String): ServerVersion? {
            val trimmed = text.trim().replace(BUKKIT_SUFFIX, "")
            val match = NUMBERS.find(trimmed) ?: return null
            val parts = match.groupValues[1].split('.').map { it.toIntOrNull() ?: return null }
            // Paper's `26.1.2.build.74-stable`: the build number isn't part of the version.
            val rest = trimmed.substring(match.range.last + 1)
            val qualifier = PRE_RELEASE.find(rest)?.groupValues?.get(1)?.lowercase()
            return ServerVersion(parts.take(4), qualifier)
        }

        fun of(vararg parts: Int): ServerVersion {
            require(parts.isNotEmpty())
            return ServerVersion(parts.toList(), null)
        }

        /** The oldest version Koban supports. */
        val MINIMUM: ServerVersion = of(1, 20, 6)
    }
}
