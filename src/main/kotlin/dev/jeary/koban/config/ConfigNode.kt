package dev.jeary.koban.config

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.MemoryConfiguration
import java.time.Duration

/**
 * A typed, path-aware view over a YAML section that *collects* problems instead of throwing.
 *
 * Every accessor validates type and range and, on failure, records a [ConfigError] carrying the
 * full path (`storage.mysql.port`) and returns the supplied default. A loader can
 * therefore read a whole file and report every problem at once.
 */
class ConfigNode(
    val file: String,
    private val section: ConfigurationSection,
    val path: String,
    private val sink: MutableList<ConfigError>,
) {

    fun pathOf(key: String): String = if (path.isEmpty()) key else "$path.$key"

    fun has(key: String): Boolean = section.contains(key, true)

    fun keys(): Set<String> = section.getKeys(false)

    fun error(key: String?, message: String) {
        sink += ConfigError(file, key?.let(::pathOf) ?: path, message)
    }

    fun warn(key: String?, message: String) {
        sink += ConfigError(file, key?.let(::pathOf) ?: path, message, ConfigError.Severity.WARNING)
    }

    /** A child node, or null (with an error if [required]). */
    fun node(key: String, required: Boolean = false): ConfigNode? {
        val child = section.getConfigurationSection(key)
        if (child == null) {
            if (required) error(key, if (has(key)) "expected a section" else "missing required section")
            return null
        }
        return ConfigNode(file, child, pathOf(key), sink)
    }

    /** A child node, or an empty one when absent (every read then falls back to defaults). */
    fun nodeOrEmpty(key: String): ConfigNode =
        node(key) ?: ConfigNode(file, MemoryConfiguration(), pathOf(key), sink)

    /** A child node that reports into its own [sink], so one bad entry can be skipped alone. */
    fun isolated(key: String, sink: MutableList<ConfigError>): ConfigNode? =
        section.getConfigurationSection(key)?.let { ConfigNode(file, it, pathOf(key), sink) }

    fun string(key: String): String? {
        val value = section.get(key) ?: return null
        return when (value) {
            is String -> value
            is Number, is Boolean -> value.toString()
            else -> null.also { error(key, "expected text, got ${describe(value)}") }
        }
    }

    fun requireString(key: String): String? =
        string(key) ?: null.also { if (!has(key)) error(key, "missing required value") }

    fun string(key: String, default: String): String = string(key) ?: default

    fun stringList(key: String): List<String> {
        val value = section.get(key) ?: return emptyList()
        return when (value) {
            is List<*> -> value.mapNotNull { it?.toString() }
            is String -> listOf(value)
            else -> emptyList<String>().also { error(key, "expected a list of text") }
        }
    }

    /** A flat `key: value` map (e.g. JDBC properties). */
    fun stringMap(key: String): Map<String, String> {
        val child = section.getConfigurationSection(key) ?: return emptyMap()
        return child.getKeys(false).associateWith { child.get(it)?.toString() ?: "" }
    }

    fun bool(key: String, default: Boolean): Boolean {
        val value = section.get(key) ?: return default
        return when (value) {
            is Boolean -> value
            is String -> when (value.lowercase()) {
                "true", "yes", "on" -> true
                "false", "no", "off" -> false
                else -> default.also { error(key, "expected true or false, got \"$value\"") }
            }
            else -> default.also { error(key, "expected true or false, got ${describe(value)}") }
        }
    }

    fun int(key: String, default: Int, range: IntRange = Int.MIN_VALUE..Int.MAX_VALUE): Int {
        val value = section.get(key) ?: return default
        val number = when (value) {
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull()
            else -> null
        }
        if (number == null || number % 1.0 != 0.0) {
            error(key, "expected a whole number${rangeHint(range)}, got ${describe(value)}")
            return default
        }
        val int = number.toInt()
        if (int !in range) {
            error(key, "expected a whole number${rangeHint(range)}, got $int")
            return int.coerceIn(range)
        }
        return int
    }

    fun double(
        key: String,
        default: Double,
        range: ClosedFloatingPointRange<Double> = -Double.MAX_VALUE..Double.MAX_VALUE,
    ): Double {
        val value = section.get(key) ?: return default
        val number = when (value) {
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull()
            else -> null
        }
        if (number == null || number.isNaN() || number.isInfinite()) {
            error(key, "expected a number${rangeHint(range)}, got ${describe(value)}")
            return default
        }
        if (number !in range) {
            error(key, "expected a number${rangeHint(range)}, got $number")
            return number.coerceIn(range)
        }
        return number
    }

    /** A duration such as `30s`, `15m`, `12h`, `7d`, `1d12h`, or a bare number of seconds. */
    fun duration(key: String, default: Duration, min: Duration = Duration.ZERO, max: Duration? = null): Duration {
        val raw = section.get(key) ?: return default
        val parsed = parseDuration(raw.toString())
        if (parsed == null) {
            error(key, "expected a duration like 30s, 15m, 12h or 7d, got ${describe(raw)}")
            return default
        }
        if (parsed < min || (max != null && parsed > max)) {
            error(key, "must be between ${formatDuration(min)} and ${max?.let(::formatDuration) ?: "∞"}, got ${formatDuration(parsed)}")
            return default
        }
        return parsed
    }

    /** Looks up [key] with [lookup]; unknown values are reported with the [options] list. */
    fun <T : Any> choice(key: String, default: T, options: Collection<String>, lookup: (String) -> T?): T {
        val raw = string(key) ?: return default
        return lookup(raw) ?: default.also {
            error(key, "unknown value \"$raw\" (expected one of: ${options.joinToString()})")
        }
    }

    inline fun <reified E : Enum<E>> enum(key: String, default: E): E {
        val options = enumValues<E>().map { it.name.lowercase().replace('_', '-') }
        return choice(key, default, options) { raw ->
            enumValues<E>().firstOrNull { it.name.equals(raw.trim().replace('-', '_'), ignoreCase = true) }
        }
    }

    fun rawList(key: String): List<*>? = section.getList(key)

    fun raw(key: String): Any? = section.get(key)

    private fun rangeHint(range: IntRange) = when {
        range.first == Int.MIN_VALUE && range.last == Int.MAX_VALUE -> ""
        range.last == Int.MAX_VALUE -> " ≥ ${range.first}"
        else -> " between ${range.first} and ${range.last}"
    }

    private fun rangeHint(range: ClosedFloatingPointRange<Double>) = when {
        range.start == -Double.MAX_VALUE && range.endInclusive == Double.MAX_VALUE -> ""
        range.endInclusive == Double.MAX_VALUE -> " ≥ ${fmt(range.start)}"
        else -> " between ${fmt(range.start)} and ${fmt(range.endInclusive)}"
    }

    companion object {
        private val DURATION_PART = Regex("""(\d+)\s*([smhdw])""", RegexOption.IGNORE_CASE)

        fun parseDuration(text: String): Duration? {
            val trimmed = text.trim()
            trimmed.toLongOrNull()?.let { return if (it >= 0) Duration.ofSeconds(it) else null }
            if (trimmed.isEmpty() || DURATION_PART.replace(trimmed, "").isNotBlank()) return null
            var total = Duration.ZERO
            for (match in DURATION_PART.findAll(trimmed)) {
                val amount = match.groupValues[1].toLong()
                total += when (match.groupValues[2].lowercase()) {
                    "s" -> Duration.ofSeconds(amount)
                    "m" -> Duration.ofMinutes(amount)
                    "h" -> Duration.ofHours(amount)
                    "d" -> Duration.ofDays(amount)
                    else -> Duration.ofDays(amount * 7)
                }
            }
            return total
        }

        fun formatDuration(duration: Duration): String {
            if (duration.isZero) return "0s"
            val parts = ArrayList<String>()
            duration.toDaysPart().takeIf { it > 0 }?.let { parts += "${it}d" }
            duration.toHoursPart().takeIf { it > 0 }?.let { parts += "${it}h" }
            duration.toMinutesPart().takeIf { it > 0 }?.let { parts += "${it}m" }
            duration.toSecondsPart().takeIf { it > 0 }?.let { parts += "${it}s" }
            return parts.joinToString("")
        }

        private fun fmt(d: Double) = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()

        fun describe(value: Any?): String = when (value) {
            null -> "nothing"
            is String -> "\"$value\""
            is ConfigurationSection -> "a section"
            is List<*> -> "a list"
            is Map<*, *> -> "a section"
            else -> value.toString()
        }
    }
}
