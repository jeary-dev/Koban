package dev.jeary.koban.command

import com.google.gson.JsonParser
import dev.jeary.koban.KobanCore
import dev.jeary.koban.domain.report.PlayerRef
import dev.jeary.koban.platform.Messenger.Companion.tag
import dev.jeary.koban.platform.text.PaperTextSink
import dev.jeary.koban.platform.text.TextSink
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/** `/koban version | reload | setup | link | unlink | debug text`. */
class KobanCommands(private val core: KobanCore, private val registry: CommandRegistry) {

    private val messenger get() = core.messenger
    private val root = registry.manager.commandBuilder("koban")

    fun register() {
        registry.register(root.literal("version").permission(Permissions.ADMIN).handler { ctx -> version(ctx.sender()) })
        registry.register(root.literal("reload").permission(Permissions.ADMIN).handler { ctx -> reload(ctx.sender()) })
        registry.register(root.literal("debug").literal("text").permission(Permissions.ADMIN).handler { ctx -> debugText(ctx.sender()) })
        registry.register(
            root.literal("link").permission(Permissions.LINK).senderType(Player::class.java)
                .handler { ctx -> link(ctx.sender()) },
        )
        registry.register(
            root.literal("unlink").permission(Permissions.LINK).senderType(Player::class.java)
                .handler { ctx -> unlink(ctx.sender()) },
        )
    }

    /** Shows the current link, or a code to type into `/link` on Discord. */
    private fun link(player: Player) = registry.launch(player) {
        val current = core.links.byMinecraft(player.uniqueId)
        if (current != null) {
            val name = core.discord.ready?.jda?.getUserById(current.discord)?.name ?: current.discord.toString()
            messenger.send(player, "koban.link-current", tag("discord", name))
        }
        val code = core.links.issueCode(PlayerRef(player.uniqueId, player.name))
        messenger.send(player, "koban.link-code", tag("code", code))
    }

    private fun unlink(player: Player) = registry.launch(player) {
        val actor = core.staffActions.actor(player)
        if (core.links.unlinkMinecraft(player.uniqueId, actor)) {
            messenger.send(player, "koban.unlinked")
        } else {
            messenger.send(player, "koban.not-linked")
        }
    }

    private fun version(sender: CommandSender) {
        messenger.send(
            sender, "koban.version",
            tag("version", core.plugin.description.version),
            tag("platform", Bukkit.getName()),
            tag("server-version", Bukkit.getBukkitVersion()),
            tag("sink", core.sink.name),
        )
    }

    private fun reload(sender: CommandSender) {
        when (val outcome = core.reload()) {
            is KobanCore.ReloadOutcome.Refused -> {
                messenger.send(sender, "koban.reload-failed", tag("count", outcome.errors.size))
                outcome.errors.take(MAX_SHOWN).forEach { messenger.send(sender, "koban.reload-problem", tag("problem", it)) }
                outcome.errors.forEach { core.logger.warning("Reload refused: $it") }
            }
            is KobanCore.ReloadOutcome.Applied -> {
                val config = core.config
                val summary = "${config.reasons.categories.size} report reasons" +
                    if (outcome.warnings.isEmpty()) "" else ", ${outcome.warnings.size} warning(s)"
                messenger.send(sender, "koban.reloaded", tag("summary", summary))
                outcome.warnings.take(MAX_SHOWN).forEach { messenger.send(sender, "koban.reload-problem", tag("problem", it)) }
                outcome.warnings.forEach { core.logger.warning("Config: $it") }
                outcome.restartRequired.forEach { messenger.send(sender, "koban.reload-restart", tag("setting", it)) }
                if (outcome.discordReconnects) messenger.send(sender, "koban.reload-discord")
            }
        }
    }

    /**
     * Sends a message that uses every kind of formatting, and on Paper proves the native path:
     * Koban's component → JSON → Paper's own Adventure component → Paper's JSON, which must
     * describe the same component.
     */
    private fun debugText(sender: CommandSender) {
        val component = messenger.component("koban.debug-text")
        messenger.send(sender, component)
        messenger.send(sender, "koban.debug-sink", tag("sink", core.sink.name))

        val sink = core.sink as? PaperTextSink ?: run {
            core.logger.info("Text check: sent through ${core.sink.name}.")
            return
        }
        val ours = TextSink.json.serialize(component)
        val theirs = sink.nativeJson(sink.toNative(component))
        val sameJson = JsonParser.parseString(ours) == JsonParser.parseString(theirs)
        val sameComponent = sameJson || TextSink.json.deserialize(theirs) == component
        if (sameComponent) {
            core.logger.info("Text check: sent through ${sink.name}; Paper JSON round trip ${if (sameJson) "identical" else "equivalent"} (${ours.length} chars).")
            messenger.send(sender, "koban.debug-roundtrip-ok", tag("length", ours.length))
        } else {
            core.logger.warning("Text check: Paper JSON round trip differs.\n  Koban: $ours\n  Paper: $theirs")
            messenger.send(sender, "koban.debug-roundtrip-failed")
        }
    }

    private companion object {
        const val MAX_SHOWN = 8
    }
}
