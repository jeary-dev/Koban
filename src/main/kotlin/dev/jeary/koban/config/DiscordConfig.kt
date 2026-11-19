package dev.jeary.koban.config

import dev.jeary.koban.domain.action.CommandTemplate
import dev.jeary.koban.domain.action.MinecraftName
import dev.jeary.koban.domain.permission.RoleGate
import dev.jeary.koban.domain.permission.StaffAction

/**
 * The bot token. Its [toString] never shows the value, so it can't end up in a log line, an
 * error message or a stack trace by accident; only [reveal] hands it over, to JDA alone.
 */
class Secret(private val value: String) {
    val isBlank: Boolean get() = value.isBlank()

    fun reveal(): String = value

    override fun toString(): String = if (value.isBlank()) "<empty>" else "<hidden>"
    override fun equals(other: Any?): Boolean = other is Secret && other.value == value
    override fun hashCode(): Int = value.hashCode()
}

/** config.yml `discord:`. */
data class DiscordSettings(
    val enabled: Boolean,
    val token: Secret,
    val guildId: Long,
    val channelId: Long,
    /** Where the head image in the embed comes from; `<uuid>` and `<name>` are filled in. */
    val avatarUrl: String,
    /** Copy staff messages in report threads into the report as notes (needs Message Content). */
    val syncNotes: Boolean,
    /** Create missing forum tags (status and categories) when the bot connects. */
    val createTags: Boolean,
    val roles: RoleGate,
    /** Raw role grants, kept for equality: [RoleGate] has no structural equals. */
    private val grants: Map<StaffAction, Set<Long>>,
) {
    /** What the connection itself depends on: a change to any of these reconnects the bot. */
    val connection: List<Any> get() = listOf(enabled, token, guildId, channelId, syncNotes)

    override fun equals(other: Any?): Boolean =
        other is DiscordSettings && connection == other.connection && avatarUrl == other.avatarUrl &&
            createTags == other.createTags && grants == other.grants

    override fun hashCode(): Int = connection.hashCode()

    override fun toString(): String = "DiscordSettings(enabled=$enabled, guild=$guildId, channel=$channelId, syncNotes=$syncNotes)"

    fun avatar(uuid: java.util.UUID, name: String): String =
        avatarUrl.replace("<uuid>", uuid.toString()).replace("<uuid-plain>", uuid.toString().replace("-", "")).replace("<name>", name)

    companion object {
        /** Lets a host keep the token out of config.yml entirely. */
        const val TOKEN_ENV = "KOBAN_DISCORD_TOKEN"
        private val TOKEN_SHAPE = Regex("""^[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+$""")

        fun parse(root: ConfigNode, env: (String) -> String? = System::getenv): DiscordSettings {
            val node = root.nodeOrEmpty("discord")
            val enabled = node.bool("enabled", false)
            val fromEnv = env(TOKEN_ENV)?.trim()?.takeIf { it.isNotEmpty() }
            val raw = fromEnv ?: (node.raw("token") as? String)?.trim().orEmpty()
            // Never include the value in a message: only what's wrong with its shape.
            if (enabled && raw.isEmpty()) {
                node.error("token", "is empty. Paste the bot token from the Developer Portal (or set $TOKEN_ENV)")
            } else if (raw.isNotEmpty() && !TOKEN_SHAPE.matches(raw)) {
                node.error("token", "doesn't look like a bot token (expected three parts separated by dots, ${raw.length} characters given)")
            }
            val guild = snowflake(node, "guild-id", enabled)
            val channel = snowflake(node, "channel-id", enabled)
            val avatar = node.string("avatar-url", "https://mc-heads.net/avatar/<uuid>/64").trim()
            if (!avatar.startsWith("https://") || ("<uuid>" !in avatar && "<uuid-plain>" !in avatar && "<name>" !in avatar)) {
                node.error("avatar-url", "expected an https:// address containing <uuid>, <uuid-plain> or <name>")
            }
            val rolesNode = node.nodeOrEmpty("roles")
            val grants = rolesNode.keys().mapNotNull { key ->
                val action = StaffAction.of(key)
                if (action == null) {
                    rolesNode.error(key, "unknown action (expected one of: ${StaffAction.entries.joinToString { it.key }})")
                    return@mapNotNull null
                }
                val ids = (rolesNode.rawList(key) ?: listOfNotNull(rolesNode.raw(key))).mapNotNull { value ->
                    toSnowflake(value) ?: null.also { rolesNode.error(key, "\"$value\" isn't a role ID (right-click the role, then Copy Role ID)") }
                }
                action to ids.toSet()
            }.toMap()
            if (enabled && grants.values.all { it.isEmpty() }) {
                rolesNode.warn(null, "no roles are set, so nobody can use the buttons on Discord yet")
            }
            return DiscordSettings(
                enabled = enabled,
                token = Secret(raw),
                guildId = guild,
                channelId = channel,
                avatarUrl = avatar,
                syncNotes = node.bool("sync-notes", false),
                createTags = node.bool("create-tags", true),
                roles = RoleGate(grants),
                grants = grants,
            )
        }

        private fun snowflake(node: ConfigNode, key: String, required: Boolean): Long {
            val value = node.raw(key)
            val id = toSnowflake(value)
            if (id == null && (required || (value != null && value.toString().isNotBlank()))) {
                node.error(key, "expected a Discord ID (turn on Developer Mode, then right-click it and Copy ID)")
            }
            return id ?: 0
        }

        internal fun toSnowflake(value: Any?): Long? = when (value) {
            is Number -> value.toLong().takeIf { it > 0 }
            is String -> value.trim().toLongOrNull()?.takeIf { it > 0 }
            else -> null
        }
    }
}

/** One punishment in actions.yml. */
data class ActionTemplate(
    val key: String,
    val label: String,
    val description: String,
    val emoji: String?,
    val template: CommandTemplate,
    /** Resolve the report (action taken) once the command has run. */
    val resolves: Boolean,
)

/** actions.yml. */
data class ActionsConfig(
    val templates: List<ActionTemplate>,
    val reasonFallback: String,
    val allowedNamePrefixes: Set<Char>,
) {
    operator fun get(key: String): ActionTemplate? = templates.firstOrNull { it.key == key }

    companion object {
        private val KEY = Regex("^[a-z0-9_-]{1,32}$")

        /** Discord allows 25 options in a select menu. */
        const val MAX_TEMPLATES = 25

        fun parse(root: ConfigNode): ActionsConfig {
            val prefixes = root.stringList("allowed-name-prefixes").mapNotNull { raw ->
                val c = raw.singleOrNull()
                if (c == null || !MinecraftName.isAllowablePrefix(c)) {
                    root.error("allowed-name-prefixes", "\"$raw\" can't be a name prefix (one symbol such as . or *, not - / @ or a letter)")
                    null
                } else {
                    c
                }
            }.toSet()
            val node = root.nodeOrEmpty("templates")
            val templates = node.keys().mapNotNull { key ->
                if (!KEY.matches(key)) {
                    node.error(key, "template keys may only use a-z, 0-9, _ and - (up to 32)")
                    return@mapNotNull null
                }
                val entry = node.node(key) ?: return@mapNotNull null.also { node.error(key, "expected a section") }
                val source = entry.requireString("command") ?: return@mapNotNull null
                val template = when (val parsed = CommandTemplate.parse(source)) {
                    is CommandTemplate.Parsed.Ok -> parsed.template
                    is CommandTemplate.Parsed.Invalid -> {
                        parsed.problems.forEach { entry.error("command", it) }
                        return@mapNotNull null
                    }
                }
                val label = entry.string("label", key).trim().take(100)
                ActionTemplate(
                    key = key,
                    label = label.ifEmpty { key },
                    description = entry.string("description", "").trim().take(100),
                    emoji = entry.string("emoji")?.trim()?.takeIf { it.isNotEmpty() },
                    template = template,
                    resolves = entry.bool("resolve", true),
                )
            }
            if (templates.size > MAX_TEMPLATES) node.error(null, "at most $MAX_TEMPLATES templates fit in Discord's menu, found ${templates.size}")
            return ActionsConfig(
                templates = templates.take(MAX_TEMPLATES),
                reasonFallback = root.string("reason-fallback", "Breaking the server rules"),
                allowedNamePrefixes = prefixes,
            )
        }
    }
}
