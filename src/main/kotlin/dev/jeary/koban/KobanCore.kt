package dev.jeary.koban

import dev.jeary.koban.api.BukkitReportEvents
import dev.jeary.koban.command.CommandRegistry
import dev.jeary.koban.command.KobanCommands
import dev.jeary.koban.command.ReportCommands
import dev.jeary.koban.command.StaffCommands
import dev.jeary.koban.config.ConfigError
import dev.jeary.koban.config.ConfigLoader
import dev.jeary.koban.config.KobanConfig
import dev.jeary.koban.config.ValidationResult
import dev.jeary.koban.domain.abuse.CooldownTracker
import dev.jeary.koban.domain.abuse.ReportGate
import dev.jeary.koban.domain.evidence.ChatHistory
import dev.jeary.koban.discord.DiscordBot
import dev.jeary.koban.domain.link.LinkCodes
import dev.jeary.koban.game.PlayerSessions
import dev.jeary.koban.game.ActionRunner
import dev.jeary.koban.game.ReportFlow
import dev.jeary.koban.game.ReportPresenter
import dev.jeary.koban.game.StaffActions
import dev.jeary.koban.game.StaffNotifier
import dev.jeary.koban.menu.ChatPrompts
import dev.jeary.koban.menu.MenuListener
import dev.jeary.koban.menu.MenuUi
import dev.jeary.koban.menu.StaffMenus
import dev.jeary.koban.platform.MainThreadDispatcher
import dev.jeary.koban.platform.Messenger
import dev.jeary.koban.platform.PluginScope
import dev.jeary.koban.platform.compat.Platform
import dev.jeary.koban.platform.evidence.CaptureListener
import dev.jeary.koban.platform.evidence.ClientBrands
import dev.jeary.koban.platform.evidence.EvidenceCollector
import dev.jeary.koban.platform.evidence.TpsSampler
import dev.jeary.koban.platform.text.TextSink
import dev.jeary.koban.service.AuditService
import dev.jeary.koban.service.LinkService
import dev.jeary.koban.service.PlayerDirectory
import dev.jeary.koban.service.ReportService
import dev.jeary.koban.storage.Database
import kotlinx.coroutines.launch
import org.bukkit.event.HandlerList
import java.time.Clock
import java.util.logging.Logger

/**
 * Everything Koban runs, wired together once storage is open. The config is swapped as a whole on
 * reload; every component reads it through a getter, so nothing holds a stale copy.
 */
class KobanCore(
    val plugin: KobanPlugin,
    initial: KobanConfig,
    val database: Database,
    val platform: Platform,
    val storageDescription: String,
) {
    val logger: Logger = plugin.logger
    val clock: Clock = Clock.systemUTC()
    val main = MainThreadDispatcher(plugin)
    val scope = PluginScope(plugin)

    @Volatile
    var config: KobanConfig = initial
        private set

    /** The database settings in use: a reload can't switch databases, only a restart. */
    private val activeStorage = initial.core.storage

    val sink: TextSink = TextSink.create(platform, logger)
    val messenger = Messenger(initial.messages, sink)
    val menuUi = MenuUi(messenger) { config.menus }
    val menus = MenuListener(plugin)
    val prompts = ChatPrompts(plugin) { messenger }

    val history = ChatHistory(clock, 0, 0, initial.core.evidence.retainAfterQuit).also { applyEvidence(it, initial) }
    val tps = TpsSampler(plugin)
    val brands = ClientBrands(plugin)
    val evidence = EvidenceCollector(history, tps, brands, clock, { config.core.evidence }, { config.core.serverName })

    private val cooldowns = CooldownTracker(clock)
    private val events = BukkitReportEvents(main)
    val reports = ReportService(database, clock, ReportGate(cooldowns), { config.core.reports }, logger, events)
    val links = LinkService(database, clock, LinkCodes(clock))
    val audit = AuditService(database, clock)
    val directory = PlayerDirectory(database, clock)

    val presenter = ReportPresenter(this)
    val staffActions = StaffActions(this)
    val staffMenus = StaffMenus(this)
    val flow = ReportFlow(this)
    val notifier = StaffNotifier(this)
    val actionRunner = ActionRunner(this)
    val discord = DiscordBot(this)
    private val sessions = PlayerSessions(this)
    val commands = CommandRegistry(this)

    fun enable() {
        val pm = plugin.server.pluginManager
        pm.registerEvents(menus, plugin)
        pm.registerEvents(prompts, plugin)
        pm.registerEvents(CaptureListener(history) { config.core.evidence }, plugin)
        pm.registerEvents(sessions, plugin)
        tps.start()
        brands.start()

        // Players already online (a /reload, or Koban enabled late) start with empty buffers.
        plugin.server.onlinePlayers.forEach { history.joined(it.uniqueId, it.name) }

        plugin.server.scheduler.runTaskTimerAsynchronously(plugin, Runnable {
            history.purgeExpired()
            cooldowns.prune(config.core.reports.antiAbuse.cooldown)
            links.codes.purgeExpired()
        }, PURGE_TICKS, PURGE_TICKS)

        reports.addListener(events)
        reports.addListener(notifier)
        reports.addListener(discord.sync)
        discord.start(config.discord)
        // Only touches PlaceholderAPI's classes when it's installed.
        if (pm.getPlugin("PlaceholderAPI") != null) {
            val expansion = dev.jeary.koban.hook.KobanPlaceholders(this).start()
            stopPlaceholders = { expansion.stop() }
            logger.info("PlaceholderAPI found: %koban_...% placeholders registered.")
        }

        commands.enable()
        ReportCommands(this, commands).register()
        StaffCommands(this, commands).register()
        KobanCommands(this, commands).register()
    }

    private var stopPlaceholders: (() -> Unit)? = null

    fun disable() {
        stopPlaceholders?.invoke()
        stopPlaceholders = null
        discord.stop()
        menus.closeAll()
        tps.stop()
        brands.stop()
        scope.shutdown()
        HandlerList.unregisterAll(plugin)
    }

    sealed interface ReloadOutcome {
        data class Refused(val errors: List<ConfigError>) : ReloadOutcome
        data class Applied(val warnings: List<ConfigError>, val restartRequired: List<String>, val discordReconnects: Boolean) : ReloadOutcome
    }

    /** Validates every file first; on any error nothing changes. Main thread. */
    fun reload(): ReloadOutcome {
        val loaded = when (val result = ConfigLoader(plugin).load()) {
            is ValidationResult.Invalid -> return ReloadOutcome.Refused(result.errors)
            is ValidationResult.Valid -> result
        }
        val next = loaded.value
        val restart = buildList {
            if (next.core.storage != activeStorage) add("storage")
        }
        val reconnect = discord.needsReconnect(next.discord)
        config = next
        messenger.messages = next.messages
        applyEvidence(history, next)
        // The bot reconnects only if its own settings changed; otherwise open threads are redrawn.
        scope.launch { discord.apply(next.discord) }
        return ReloadOutcome.Applied(loaded.warnings, restart, reconnect)
    }

    private fun applyEvidence(history: ChatHistory, config: KobanConfig) {
        val evidence = config.core.evidence
        history.chatCapacity = evidence.bufferSize
        history.commandCapacity = if (evidence.commandsEnabled) evidence.commandLines else 0
        history.retainAfterQuit = evidence.retainAfterQuit
    }

    private companion object {
        const val PURGE_TICKS = 20L * 60
    }
}
