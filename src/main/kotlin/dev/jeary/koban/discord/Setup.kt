package dev.jeary.koban.discord

import dev.jeary.koban.KobanCore
import dev.jeary.koban.domain.permission.StaffAction
import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel
import net.dv8tion.jda.api.requests.GatewayIntent

/**
 * `/koban setup`: checks every piece of the Discord setup in order and says what to fix next,
 * plus a correct invite link. Each line is a key under `setup.` in messages.yml with its values.
 * The token itself is never shown, only whether it's there and well formed.
 */
class Setup(private val core: KobanCore) {

    enum class Level { OK, PROBLEM, INFO }

    data class Line(val level: Level, val key: String, val values: Map<String, String> = emptyMap())

    fun check(): List<Line> {
        val out = ArrayList<Line>()
        fun ok(key: String, vararg v: Pair<String, Any?>) = out.add(Line(Level.OK, key, v.toMap().mapValues { it.value.toString() }))
        fun bad(key: String, vararg v: Pair<String, Any?>) = out.add(Line(Level.PROBLEM, key, v.toMap().mapValues { it.value.toString() }))
        fun info(key: String, vararg v: Pair<String, Any?>) = out.add(Line(Level.INFO, key, v.toMap().mapValues { it.value.toString() }))

        val settings = core.config.discord
        val bot = core.discord
        if (!settings.enabled) {
            info("setup.disabled")
            return out
        }
        val token = settings.token
        if (token.isBlank) {
            bad("setup.token-missing")
            return out
        }
        ok("setup.token-present")
        val appId = bot.ready?.jda?.selfUser?.applicationIdLong ?: DiscordBot.applicationIdFromToken(token.reveal())

        when (val state = bot.state) {
            is DiscordBot.State.Off -> bad("setup.state-off", "reason" to state.reason.replaceFirstChar(Char::uppercaseChar))
            DiscordBot.State.Connecting -> info("setup.state-connecting")
            is DiscordBot.State.Failed -> bad("setup.state-failed", "reason" to state.reason.replaceFirstChar(Char::uppercaseChar))
            is DiscordBot.State.Ready -> {
                ok("setup.connected", "bot" to state.jda.selfUser.name, "guild" to state.guild.name)
                val channel = state.channel
                ok(if (channel is ForumChannel) "setup.channel-forum" else "setup.channel-text", "channel" to channel.name)
                val missing = bot.missingPermissions(state.guild, channel)
                if (missing.isEmpty()) ok("setup.permissions-ok") else bad("setup.permissions-missing", "permissions" to missing.joinToString { it.getName() })
                if (channel is ForumChannel) {
                    val have = channel.availableTags.map { it.name.lowercase() }.toSet()
                    val absent = bot.content.allTagNames(core.config.reasons.keys).filter { it.lowercase() !in have }
                    if (absent.isEmpty()) ok("setup.tags-ok") else bad("setup.tags-missing", "tags" to absent.joinToString())
                }
                val configured = settings.roles.configuredRoles
                if (configured.isEmpty()) {
                    bad("setup.roles-none")
                } else {
                    val unknown = configured.filter { state.guild.getRoleById(it) == null }
                    if (unknown.isEmpty()) {
                        val summary = StaffAction.entries.joinToString(", ") { action ->
                            val roles = configured.filter { settings.roles.allows(listOf(it), action) }
                                .mapNotNull { state.guild.getRoleById(it)?.name }
                            "${action.key}: ${roles.joinToString("/").ifEmpty { "-" }}"
                        }
                        ok("setup.roles-ok", "roles" to summary)
                    } else {
                        bad("setup.roles-unknown", "roles" to unknown.joinToString())
                    }
                }
                if (settings.syncNotes) {
                    if (GatewayIntent.MESSAGE_CONTENT in state.jda.gatewayIntents) ok("setup.notes-on") else bad("setup.notes-intent")
                } else {
                    info("setup.notes-off")
                }
            }
        }
        if (appId != null) info("setup.invite", "url" to DiscordBot.inviteUrl(appId))
        return out
    }
}
