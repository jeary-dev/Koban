package dev.jeary.koban.config

import org.bukkit.configuration.ConfigurationSection

/**
 * `messages.yml` flattened to dotted keys (`report.sent`). Every value is MiniMessage;
 * `<prefix>` expands to the configured prefix. Keys missing from the user's file fall back to the
 * bundled defaults, so upgrading the plugin never shows blank messages.
 */
class Messages(
    private val entries: Map<String, String>,
    private val defaults: Map<String, String>,
) {
    val prefix: String get() = raw("prefix")

    fun raw(key: String): String = entries[key] ?: defaults[key] ?: "<red>Missing message: $key"

    fun has(key: String): Boolean = key in entries || key in defaults

    val keys: Set<String> get() = entries.keys + defaults.keys

    companion object {
        fun parse(root: ConfigNode, section: ConfigurationSection, defaults: Map<String, String>): Messages {
            val entries = HashMap<String, String>()
            for (key in section.getKeys(true)) {
                if (section.isConfigurationSection(key)) continue
                val value = section.get(key)
                when (value) {
                    is String -> entries[key] = value
                    is List<*> -> entries[key] = value.joinToString("\n") { it.toString() }
                    else -> root.error(key, "expected MiniMessage text, got ${ConfigNode.describe(value)}")
                }
            }
            defaults.keys.filterNot { it in entries }.takeIf { it.isNotEmpty() }?.let { missing ->
                root.warn(null, "${missing.size} message(s) missing, using defaults: ${missing.take(5).joinToString()}${if (missing.size > 5) ", …" else ""}")
            }
            return Messages(entries, defaults)
        }

        fun flatten(section: ConfigurationSection): Map<String, String> =
            section.getKeys(true).filterNot(section::isConfigurationSection).associateWith { key ->
                when (val value = section.get(key)) {
                    is List<*> -> value.joinToString("\n") { it.toString() }
                    else -> value.toString()
                }
            }
    }
}
