package dev.jeary.koban.domain.action

/**
 * Minecraft's player name rules: 3 to 16 characters, `A-Z a-z 0-9 _` only.
 *
 * Bedrock players joining through Floodgate get a configurable prefix (usually `.`), so servers
 * can allow specific prefix characters. A prefix is one character from [allowedPrefixes] and
 * never something that could start a selector or a flag (`@`, `-`, `/`).
 */
object MinecraftName {

    private val JAVA_NAME = Regex("^[A-Za-z0-9_]{3,16}$")
    private val FORBIDDEN_PREFIXES = setOf('@', '-', '/', ' ', '\\', '"', '\'', '{', '}', '%', '§', '&')

    fun isValid(name: String, allowedPrefixes: Set<Char> = emptySet()): Boolean {
        if (JAVA_NAME.matches(name)) return true
        val first = name.firstOrNull() ?: return false
        if (first !in allowedPrefixes || first in FORBIDDEN_PREFIXES) return false
        // Floodgate trims long Bedrock names so the prefixed name still fits 16 characters.
        return name.length in 2..16 && name.drop(1).all { (it.isLetterOrDigit() && it.code < 128) || it == '_' }
    }

    /** Prefix characters that config may allow. */
    fun isAllowablePrefix(c: Char): Boolean = c !in FORBIDDEN_PREFIXES && !c.isLetterOrDigit() && c != '_' && c.code in 33..126
}
