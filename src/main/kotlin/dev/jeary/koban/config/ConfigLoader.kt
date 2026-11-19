package dev.jeary.koban.config

import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.configuration.InvalidConfigurationException
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import java.io.File
import java.io.IOException
import java.io.InputStreamReader

/** Every config file, parsed and validated together. Swapped as a whole: never half-applied. */
data class KobanConfig(
    val core: CoreSettings,
    val reasons: Reasons,
    val menus: MenuConfig,
    val messages: Messages,
    val discord: DiscordSettings,
    val actions: ActionsConfig,
)

/**
 * Reads config.yml, reasons.yml, actions.yml, menus.yml and messages.yml from the plugin folder (writing the
 * bundled defaults first if a file is missing), validates all of them, and returns either a
 * complete [KobanConfig] or every problem found, each naming its file and setting.
 */
class ConfigLoader(private val plugin: Plugin) {

    fun load(): ValidationResult<KobanConfig> {
        val problems = ArrayList<ConfigError>()

        val configYaml = read(CONFIG, problems)
        val reasonsYaml = read(REASONS, problems)
        val menusYaml = read(MENUS, problems)
        val messagesYaml = read(MESSAGES, problems)
        val actionsYaml = read(ACTIONS, problems)
        // A file that isn't valid YAML can't be checked any further.
        if (listOf(configYaml, reasonsYaml, menusYaml, messagesYaml, actionsYaml).any { it == null }) return ValidationResult.Invalid(problems)

        val core = CoreSettings.parse(ConfigNode(CONFIG, requireNotNull(configYaml), "", problems))
        val discord = DiscordSettings.parse(ConfigNode(CONFIG, configYaml, "", problems))
        val actions = ActionsConfig.parse(ConfigNode(ACTIONS, requireNotNull(actionsYaml), "", problems))
        val reasons = Reasons.parse(ConfigNode(REASONS, requireNotNull(reasonsYaml), "", problems)) { MiniMessage.miniMessage().stripTags(it) }
        val menus = MenuConfig.parse(ConfigNode(MENUS, requireNotNull(menusYaml), "", problems))
        val messagesNode = ConfigNode(MESSAGES, requireNotNull(messagesYaml), "", problems)
        val messages = Messages.parse(messagesNode, messagesYaml, bundledMessages())

        // Every message must at least parse as MiniMessage (a typo would otherwise show on first use).
        for (key in messages.keys) {
            try {
                MiniMessage.miniMessage().deserialize(messages.raw(key).replace("<prefix>", ""))
            } catch (e: RuntimeException) {
                problems += ConfigError(MESSAGES, key, "isn't valid MiniMessage: ${e.message?.lineSequence()?.firstOrNull()}")
            }
        }

        crossCheck(reasons, discord, problems)
        return ValidationResult.of(problems) { KobanConfig(core, reasons, menus, messages, discord, actions) }
    }

    private fun read(name: String, problems: MutableList<ConfigError>): YamlConfiguration? {
        val file = File(plugin.dataFolder, name)
        if (!file.exists()) plugin.saveResource(name, false)
        return try {
            YamlConfiguration().apply { load(file) }
        } catch (e: InvalidConfigurationException) {
            problems += ConfigError(name, "", "isn't valid YAML: ${e.message?.lineSequence()?.take(3)?.joinToString(" ")}")
            null
        } catch (e: IOException) {
            problems += ConfigError(name, "", "couldn't be read: ${e.message}")
            null
        }
    }

    private fun bundledMessages(): Map<String, String> {
        val stream = plugin.getResource(MESSAGES) ?: return emptyMap()
        val yaml = InputStreamReader(stream, Charsets.UTF_8).use { YamlConfiguration.loadConfiguration(it) }
        return Messages.flatten(yaml)
    }

    companion object {
        /** Discord allows 20 forum tags: four statuses plus one per reason. */
        private const val MAX_TAGGED_REASONS = 16

        /** Checks that need more than one file. */
        fun crossCheck(reasons: Reasons, discord: DiscordSettings, problems: MutableList<ConfigError>) {
            if (reasons.categories.isEmpty()) problems += ConfigError(REASONS, "categories", "needs at least one reason")
            if (discord.enabled && discord.createTags && reasons.categories.size > MAX_TAGGED_REASONS) {
                problems += ConfigError(
                    REASONS, "categories",
                    "has ${reasons.categories.size} reasons, but a Discord forum holds 20 tags (4 for status + 16 reasons); " +
                        "the rest won't get a tag",
                    ConfigError.Severity.WARNING,
                )
            }
        }

        const val CONFIG = "config.yml"
        const val REASONS = "reasons.yml"
        const val MENUS = "menus.yml"
        const val MESSAGES = "messages.yml"
        const val ACTIONS = "actions.yml"
    }
}
