package dev.jeary.koban.discord

import dev.jeary.koban.KobanCore
import dev.jeary.koban.config.DiscordSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.OnlineStatus
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.entities.channel.forums.BaseForumTag
import net.dv8tion.jda.api.entities.channel.forums.ForumTagData
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.events.session.ShutdownEvent
import net.dv8tion.jda.api.exceptions.InvalidTokenException
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.CloseCode
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.cache.CacheFlag
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.logging.Level

/**
 * The Discord bot inside the plugin. It only ever connects *out* to Discord (no ports, no web
 * server), so it runs on any host that can reach the internet. If it's off, misconfigured or
 * can't connect, everything in game keeps working and the console says why, once.
 *
 * JDA calls back on its own threads. Nothing here touches Bukkit: anything that needs the server
 * goes through [KobanCore.main].
 */
class DiscordBot(private val core: KobanCore) {

    sealed interface State {
        data class Off(val reason: String) : State
        data object Connecting : State
        /** [channel] is a [ForumChannel] (a post per report) or a [TextChannel] (a thread per report). */
        data class Ready(val jda: JDA, val guild: Guild, val channel: GuildChannel) : State {
            val isForum: Boolean get() = channel is ForumChannel
        }
        data class Failed(val reason: String) : State
    }

    @Volatile
    var state: State = State.Off("not started")
        private set

    @Volatile
    private var jda: JDA? = null

    @Volatile
    var settings: DiscordSettings = core.config.discord
        private set

    val adapters = JdaAdapters { key -> core.messenger.plain(key) }
    val content: ThreadContent
        get() = ThreadContent(
            text = { key, values -> core.messenger.plain(key, *values.map { (k, v) -> dev.jeary.koban.platform.Messenger.tag(k, v) }.toTypedArray()) },
            categoryName = core.config.reasons::displayName,
            priority = core.config.core.priority,
            avatar = { report -> settings.avatar(report.reported.uuid, report.reported.name) },
        )
    val sync = ThreadSync(core, this)
    private val interactions = Interactions(core, this)

    val ready: State.Ready? get() = state as? State.Ready

    fun start(next: DiscordSettings) {
        settings = next
        if (!next.enabled) {
            state = State.Off("discord.enabled is false")
            core.logger.info("Discord bot is off (discord.enabled: false in config.yml). Everything works in game.")
            return
        }
        state = State.Connecting
        val intents = buildList {
            add(GatewayIntent.GUILD_MESSAGES)
            if (next.syncNotes) add(GatewayIntent.MESSAGE_CONTENT)
        }
        val built = try {
            JDABuilder.createLight(next.token.reveal(), intents)
                .enableCache(CacheFlag.FORUM_TAGS)
                .setStatus(OnlineStatus.ONLINE)
                .addEventListeners(Lifecycle(), interactions)
                .build()
        } catch (e: InvalidTokenException) {
            fail("the bot token was rejected. Copy a fresh one from the Developer Portal (Bot > Reset Token).")
            return
        } catch (e: IllegalArgumentException) {
            fail("the bot couldn't start: ${e.message}")
            return
        }
        jda = built
        core.logger.info("Discord: connecting...")
        core.scope.launch {
            delay(CONNECT_WARNING_MS)
            if (state == State.Connecting && jda === built) {
                core.logger.warning("Discord: still not connected after ${CONNECT_WARNING_MS / 1000}s. Is the internet reachable from this host? Retrying in the background.")
            }
        }
    }

    /** Stops the bot and waits briefly for it to close (called on disable and before a reconnect). */
    fun stop() {
        sync.stop()
        val current = jda ?: return
        jda = null
        state = State.Off("stopped")
        current.shutdown()
        try {
            if (!current.awaitShutdown(SHUTDOWN_SECONDS, TimeUnit.SECONDS)) current.shutdownNow()
        } catch (_: InterruptedException) {
            current.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Whether applying [next] reconnects: when the connection settings changed, or when the bot
     * isn't connected now (so a reload retries after you invite the bot or fix a permission).
     */
    fun needsReconnect(next: DiscordSettings): Boolean =
        next.connection != settings.connection || (next.enabled && state !is State.Ready && state != State.Connecting)

    /** Applies new settings: reconnects only if needed (see [needsReconnect]). */
    suspend fun apply(next: DiscordSettings) {
        val reconnect = needsReconnect(next)
        if (!reconnect) {
            settings = next
            // Labels, colours and buttons may have changed: redraw what's open.
            if (ready != null) sync.enqueueActive()
            return
        }
        withContext(Dispatchers.IO) { stop() }
        start(next)
    }

    private fun fail(reason: String) {
        state = State.Failed(reason)
        core.logger.warning("Discord bot disabled: $reason Reports keep working in game; fix config.yml and run /koban reload.")
    }

    private inner class Lifecycle : ListenerAdapter() {
        override fun onReady(event: ReadyEvent) {
            core.scope.launch(Dispatchers.IO) {
                try {
                    becomeReady(event.jda)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    core.logger.log(Level.WARNING, "Discord: setup after connecting failed", e)
                    fail("setup after connecting failed (${e.message}).")
                }
            }
        }

        override fun onShutdown(event: ShutdownEvent) {
            if (event.jda !== jda) return
            val reason = when (event.closeCode) {
                CloseCode.AUTHENTICATION_FAILED -> "the bot token was rejected. Copy a fresh one from the Developer Portal (Bot > Reset Token)."
                CloseCode.DISALLOWED_INTENTS ->
                    "discord.sync-notes needs the Message Content intent. Turn it on in the Developer Portal (Bot > Privileged Gateway Intents) or set sync-notes: false."
                null -> return
                else -> "Discord closed the connection (${event.closeCode})."
            }
            jda = null
            fail(reason)
        }
    }

    private suspend fun becomeReady(api: JDA) {
        val s = settings
        val guild = api.getGuildById(s.guildId)
            ?: return fail("the bot isn't in the server with guild-id ${s.guildId}. Invite it with: ${inviteUrl(api.selfUser.applicationIdLong)}")
        val channel: GuildChannel = guild.getGuildChannelById(s.channelId)
            ?: return fail("there's no channel ${s.channelId} in ${guild.name} the bot can see.")
        if (channel !is ForumChannel && channel !is TextChannel) {
            return fail("channel ${s.channelId} is a ${channel.type.name.lowercase()}; use a forum channel (best) or a text channel.")
        }
        val usable = channel
        val missing = missingPermissions(guild, usable)
        if (missing.isNotEmpty()) {
            core.logger.warning("Discord: the bot is missing ${missing.joinToString { it.getName() }} in #${usable.name}. Run /koban setup for an invite link with the right permissions.")
        }
        if (usable is ForumChannel && s.createTags) ensureTags(usable)
        registerCommands(guild)
        state = State.Ready(api, guild, usable)
        core.logger.info("Discord: connected as ${api.selfUser.name} to ${guild.name} / #${usable.name} (${if (usable is ForumChannel) "forum" else "text channel with threads"}).")
        sync.start()
        sync.enqueueDirty()
    }

    fun missingPermissions(guild: Guild, channel: GuildChannel): List<Permission> {
        val self = guild.selfMember
        val needed = REQUIRED + if (settings.createTags && channel is ForumChannel) listOf(Permission.MANAGE_CHANNEL) else emptyList()
        return needed.filterNot { self.hasPermission(channel, it) }
    }

    private suspend fun ensureTags(forum: ForumChannel) {
        val existing = forum.availableTags
        val have = existing.map { it.name.lowercase() }.toSet()
        val wanted = content.allTagNames(core.config.reasons.keys).distinct()
        val toAdd = wanted.filter { it.lowercase() !in have }
        if (toAdd.isEmpty()) return
        val room = MAX_FORUM_TAGS - existing.size
        if (room <= 0) {
            core.logger.warning("Discord: the forum already has ${existing.size} tags (the most Discord allows); Koban's tags weren't added.")
            return
        }
        val tags: List<BaseForumTag> = existing + toAdd.take(room).map { ForumTagData(it) }
        try {
            forum.manager.setAvailableTags(tags).submit().await()
            core.logger.info("Discord: added forum tags ${toAdd.take(room).joinToString()}.")
        } catch (e: Exception) {
            core.logger.warning("Discord: couldn't add forum tags (${e.message}). Give the bot Manage Channels, or create them yourself: ${toAdd.joinToString()}.")
        }
    }

    private suspend fun registerCommands(guild: Guild) {
        try {
            guild.updateCommands().addCommands(interactions.commandData()).submit().await()
        } catch (e: Exception) {
            core.logger.warning("Discord: couldn't register slash commands (${e.message}). Re-invite the bot with the applications.commands scope (/koban setup).")
        }
    }

    companion object {
        private const val CONNECT_WARNING_MS = 30_000L
        private const val SHUTDOWN_SECONDS = 5L

        /** Discord allows 20 tags per forum. */
        private const val MAX_FORUM_TAGS = 20

        val REQUIRED: List<Permission> = listOf(
            Permission.VIEW_CHANNEL,
            Permission.MESSAGE_SEND,
            Permission.MESSAGE_SEND_IN_THREADS,
            Permission.CREATE_PUBLIC_THREADS,
            Permission.MANAGE_THREADS,
            Permission.MESSAGE_EMBED_LINKS,
            Permission.MESSAGE_HISTORY,
            Permission.MESSAGE_ADD_REACTION,
        )

        /** Everything the bot asks for in its invite link (MANAGE_CHANNEL only for creating forum tags). */
        val INVITE: List<Permission> = REQUIRED + Permission.MANAGE_CHANNEL

        fun inviteUrl(applicationId: Long): String =
            "https://discord.com/oauth2/authorize?client_id=$applicationId&scope=bot%20applications.commands&permissions=${Permission.getRaw(INVITE)}"

        /**
         * A bot token starts with the bot's ID in base64, which is also its application ID: lets
         * `/koban setup` print the invite link even when the bot can't log in. Never logs the token.
         */
        fun applicationIdFromToken(token: String): Long? = try {
            val first = token.substringBefore('.')
            String(Base64.getUrlDecoder().decode(first.padEnd((first.length + 3) / 4 * 4, '=')), Charsets.US_ASCII).toLongOrNull()
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
