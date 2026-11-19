package dev.jeary.koban.command

import dev.jeary.koban.KobanCore
import org.bukkit.entity.Player
import org.incendo.cloud.parser.standard.StringParser.greedyStringParser
import org.incendo.cloud.parser.standard.StringParser.stringParser
import org.incendo.cloud.suggestion.Suggestion
import org.incendo.cloud.suggestion.SuggestionProvider

/**
 * `/report <player> [reason] [details…]`. With only a player it opens the reason menu; with a
 * reason it skips the menu; with details it skips the details question too.
 */
class ReportCommands(private val core: KobanCore, private val registry: CommandRegistry) {

    fun register() {
        val flow = core.flow
        val players = SuggestionProvider.blocking<org.bukkit.command.CommandSender> { ctx, _ ->
            flow.suggestions(ctx.sender()).map(Suggestion::suggestion)
        }
        val reasons = SuggestionProvider.blocking<org.bukkit.command.CommandSender> { _, _ ->
            flow.categoryKeys().map(Suggestion::suggestion)
        }
        registry.register(
            registry.manager.commandBuilder("report")
                .permission(Permissions.REPORT)
                .senderType(Player::class.java)
                .required("player", stringParser(), players)
                .optional("reason", stringParser(), reasons)
                .optional("details", greedyStringParser())
                .handler { ctx ->
                    flow.start(
                        ctx.sender(),
                        ctx.get("player"),
                        ctx.optional<String>("reason").orElse(null),
                        ctx.optional<String>("details").orElse(null),
                    )
                },
        )
    }
}
