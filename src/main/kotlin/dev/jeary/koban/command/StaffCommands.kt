package dev.jeary.koban.command

import dev.jeary.koban.KobanCore
import dev.jeary.koban.domain.report.Outcome
import dev.jeary.koban.domain.report.PlayerRef
import dev.jeary.koban.domain.report.Report
import dev.jeary.koban.domain.report.ReportId
import dev.jeary.koban.platform.Messenger.Companion.tag
import kotlinx.coroutines.future.future
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.incendo.cloud.parser.standard.LongParser.longParser
import org.incendo.cloud.parser.standard.StringParser.greedyStringParser
import org.incendo.cloud.parser.standard.StringParser.stringParser
import org.incendo.cloud.suggestion.Suggestion
import org.incendo.cloud.suggestion.SuggestionProvider

/**
 * `/reports` (the staff menu) and its subcommands. Every action also works from the console, so
 * reports can be handled from a panel or scripted.
 */
class StaffCommands(private val core: KobanCore, private val registry: CommandRegistry) {

    private val messenger get() = core.messenger
    private val actions get() = core.staffActions
    private val root = registry.manager.commandBuilder("reports")

    fun register() {
        val knownPlayers = SuggestionProvider<CommandSender> { _, input ->
            val prefix = input.peekString()
            core.scope.future {
                val online = Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(prefix, ignoreCase = true) }
                val stored = if (prefix.isEmpty()) emptyList() else core.directory.search(prefix, SUGGESTIONS)
                (online + stored).distinct().take(SUGGESTIONS).map(Suggestion::suggestion)
            }
        }
        val outcomes = SuggestionProvider.blocking<CommandSender> { _, _ -> OUTCOMES.keys.map(Suggestion::suggestion) }

        registry.register(
            root.permission(Permissions.STAFF).senderType(Player::class.java)
                .handler { ctx -> core.staffMenus.openList(ctx.sender()) },
        )
        registry.register(
            root.literal("view").permission(Permissions.STAFF)
                .required("id", longParser(1))
                .handler { ctx ->
                    val id = ReportId(ctx.get("id"))
                    when (val sender = ctx.sender()) {
                        is Player -> core.staffMenus.openReport(sender, id)
                        else -> summary(sender, id)
                    }
                },
        )
        registry.register(
            root.literal("history").permission(Permissions.HISTORY)
                .required("player", stringParser(), knownPlayers)
                .handler { ctx -> history(ctx.sender(), ctx.get("player")) },
        )
        registry.register(
            root.literal("claim").permission(Permissions.CLAIM).required("id", longParser(1))
                .handler { ctx -> registry.launch(ctx.sender()) { actions.claim(ctx.sender(), ReportId(ctx.get("id"))) } },
        )
        registry.register(
            root.literal("unclaim").permission(Permissions.CLAIM).required("id", longParser(1))
                .handler { ctx -> registry.launch(ctx.sender()) { actions.unclaim(ctx.sender(), ReportId(ctx.get("id"))) } },
        )
        registry.register(
            root.literal("resolve").permission(Permissions.RESOLVE)
                .required("id", longParser(1))
                .required("outcome", stringParser(), outcomes)
                .optional("note", greedyStringParser())
                .handler { ctx ->
                    val raw = ctx.get<String>("outcome")
                    val outcome = OUTCOMES[raw.lowercase()]
                        ?: return@handler messenger.send(ctx.sender(), "staff.unknown-outcome", tag("outcome", raw))
                    registry.launch(ctx.sender()) {
                        actions.resolve(ctx.sender(), ReportId(ctx.get("id")), outcome, ctx.optional<String>("note").orElse(null))
                    }
                },
        )
        registry.register(
            root.literal("dismiss").permission(Permissions.RESOLVE)
                .required("id", longParser(1))
                .optional("note", greedyStringParser())
                .handler { ctx ->
                    registry.launch(ctx.sender()) { actions.dismiss(ctx.sender(), ReportId(ctx.get("id")), ctx.optional<String>("note").orElse(null)) }
                },
        )
        registry.register(
            root.literal("reopen").permission(Permissions.RESOLVE).required("id", longParser(1))
                .handler { ctx -> registry.launch(ctx.sender()) { actions.reopen(ctx.sender(), ReportId(ctx.get("id"))) } },
        )
        registry.register(
            root.literal("note").permission(Permissions.NOTE)
                .required("id", longParser(1))
                .required("text", greedyStringParser())
                .handler { ctx -> registry.launch(ctx.sender()) { actions.note(ctx.sender(), ReportId(ctx.get("id")), ctx.get("text")) } },
        )
        registry.register(
            root.literal("tp").permission(Permissions.TELEPORT).senderType(Player::class.java)
                .required("id", longParser(1))
                .handler { ctx ->
                    registry.launch(ctx.sender()) {
                        val id = ReportId(ctx.get("id"))
                        val report = core.reports.find(id) ?: return@launch actions.notFound(ctx.sender(), id)
                        actions.teleport(ctx.sender(), report)
                    }
                },
        )
    }

    private fun history(sender: CommandSender, name: String) = registry.launch(sender) {
        val target = Bukkit.getPlayerExact(name)?.let { PlayerRef(it.uniqueId, it.name) } ?: core.directory.byName(name)
            ?: return@launch messenger.send(sender, "staff.unknown-player", tag("player", name))
        if (sender is Player) {
            core.staffMenus.openHistory(sender, target)
            return@launch
        }
        val reports = core.reports.history(target.uuid, limit = CONSOLE_LINES)
        if (reports.isEmpty()) return@launch messenger.send(sender, "staff.history-empty", tag("player", target.name))
        messenger.send(sender, "staff.history-header", tag("player", target.name), tag("count", core.reports.countAgainst(target.uuid)))
        reports.forEach { line(sender, it) }
    }

    /** The console's view of a report: one line per fact. */
    private fun summary(sender: CommandSender, id: ReportId) = registry.launch(sender) {
        val details = core.reports.details(id) ?: return@launch actions.notFound(sender, id)
        line(sender, details.report)
        details.reporters.forEach {
            messenger.send(sender, "staff.console-reporter", tag("reporter", it.player.name), tag("category", core.config.reasons.displayName(it.category)), tag("details", it.details ?: "-"))
        }
        details.notes.forEach { messenger.send(sender, "staff.console-note", tag("author", it.authorName), tag("body", it.body)) }
    }

    private fun line(sender: CommandSender, report: Report) {
        messenger.send(sender, "staff.console-line", *core.presenter.summary(report).toTypedArray())
    }

    private companion object {
        const val SUGGESTIONS = 20
        const val CONSOLE_LINES = 15
        val OUTCOMES = mapOf("action" to Outcome.ACTION_TAKEN, "no-action" to Outcome.NO_ACTION)
    }
}
