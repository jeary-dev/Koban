package dev.jeary.koban.config

import dev.jeary.koban.platform.compat.Compat
import org.bukkit.Material

/** A report category from reasons.yml. [name] and [description] are MiniMessage. */
data class ReportCategory(
    val key: String,
    val name: String,
    /** The name without formatting, for Discord, commands and console output. */
    val plainName: String,
    val icon: Material,
    val customModelData: Int?,
    val description: List<String>,
    /** Ask the reporter to describe what happened (when `reports.ask-details` is on). */
    val askDetails: Boolean,
)

/** reasons.yml: the categories players choose from, in the order they're listed. */
class Reasons(val categories: List<ReportCategory>) {

    private val byKey = categories.associateBy { it.key }

    operator fun get(key: String): ReportCategory? = byKey[key.lowercase()]

    /** A category that was removed from reasons.yml still shows its key on old reports. */
    fun displayName(key: String): String = byKey[key]?.plainName ?: key

    val keys: List<String> get() = categories.map { it.key }

    companion object {
        private val KEY = Regex("^[a-z0-9_-]{1,32}$")

        fun parse(root: ConfigNode, plain: (String) -> String): Reasons {
            val node = root.node("categories", required = true) ?: return Reasons(emptyList())
            val categories = node.keys().mapNotNull { rawKey ->
                val key = rawKey.lowercase()
                if (!KEY.matches(key)) {
                    node.error(rawKey, "category keys must be 1-32 lowercase letters, digits, - or _")
                    return@mapNotNull null
                }
                val entry = node.node(rawKey) ?: return@mapNotNull null.also { node.error(rawKey, "expected a section") }
                val iconNames = entry.stringList("icon")
                if (iconNames.isEmpty()) entry.error("icon", "missing required item, e.g. icon: DIAMOND_SWORD")
                val icon = Compat.material(iconNames, Material.AIR)
                if (iconNames.isNotEmpty() && icon == Material.AIR) {
                    entry.error("icon", "none of ${iconNames.joinToString()} is an item on this server version")
                }
                val name = entry.string("name", key)
                ReportCategory(
                    key = key,
                    name = name,
                    plainName = plain(name).ifBlank { key },
                    icon = if (icon == Material.AIR) Material.PAPER else icon,
                    customModelData = if (entry.has("custom-model-data")) entry.int("custom-model-data", 0, 0..Int.MAX_VALUE) else null,
                    description = entry.stringList("description"),
                    askDetails = entry.bool("ask-details", true),
                )
            }
            if (categories.isEmpty()) node.error(null, "at least one category is needed")
            if (categories.size > 45) node.error(null, "at most 45 categories fit in the reason menu (found ${categories.size})")
            return Reasons(categories)
        }
    }
}
