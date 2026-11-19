package dev.jeary.koban.discord

import dev.jeary.koban.KobanCore
import dev.jeary.koban.domain.actor.Actor
import dev.jeary.koban.domain.permission.StaffAction
import dev.jeary.koban.domain.report.Change
import dev.jeary.koban.domain.report.Outcome
import dev.jeary.koban.domain.report.PlayerRef
import dev.jeary.koban.domain.report.Rejection
import dev.jeary.koban.domain.report.ReportId
import dev.jeary.koban.domain.report.ReportStatus
import dev.jeary.koban.game.ActionRunner
import dev.jeary.koban.platform.Messenger
import dev.jeary.koban.service.LinkResult
import dev.jeary.koban.service.NoteResult
import dev.jeary.koban.service.TransitionResult
import dev.jeary.koban.storage.repo.ReportQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import java.util.logging.Level

/**
 * Everything staff click or type on Discord. Permissions are checked on every interaction from
 * the roles Discord sends with it, and every action re-reads the report from the database, so a
 * stale button can never do something the current state doesn't allow.
 *
 * Runs on JDA's threads and Koban's coroutines; never touches Bukkit directly.
 */
class Interactions(private val core: KobanCore, private val bot: DiscordBot) : ListenerAdapter() {

    private val runner get() = core.actionRunner
    private val settings get() = bot.settings

    private fun text(key: String, vararg values: Pair<String, Any?>): String =
        core.messenger.plain(key, *values.map { (k, v) -> Messenger.tag(k, v) }.toTypedArray())

    private fun roles(member: Member?): List<Long> = member?.roles?.map { it.idLong }.orEmpty()

    private fun allows(member: Member?, action: StaffAction) = settings.roles.allows(roles(member), action)

    private suspend fun actor(member: Member): Actor {
        val link = core.links.byDiscord(member.idLong)
        return Actor.discord(member.idLong, member.effectiveName, link?.minecraft?.uuid, link?.minecraft?.name)
    }

    /** Slash commands, registered in the configured server only. */
    fun commandData(): List<CommandData> = listOf(
        Commands.slash("reports", text("discord.cmd-reports"))
            .addSubcommands(
                SubcommandData("open", text("discord.cmd-open")),
                SubcommandData("lookup", text("discord.cmd-lookup"))
                    .addOption(OptionType.STRING, "player", text("discord.cmd-lookup-player"), true),
            ),
        Commands.slash("link", text("discord.cmd-link"))
            .addOption(OptionType.STRING, "code", text("discord.cmd-link-code"), true),
    )

    /** Checks the server and the role, answering the member if either is wrong. */
    private fun permitted(event: IReplyCallback, action: StaffAction): Boolean {
        if (event.guild?.idLong != settings.guildId) {
            event.reply(text("discord.reply-wrong-server")).setEphemeral(true).queue()
            return false
        }
        if (!allows(event.member, action)) {
            event.reply(text("discord.reply-no-permission", "action" to action.key)).setEphemeral(true).queue()
            return false
        }
        return true
    }

    /** Runs [block] after deferring an ephemeral reply; whatever it returns is the answer. */
    private fun deferred(event: IReplyCallback, block: suspend (InteractionHook, Member) -> String) {
        val member = event.member ?: return
        event.deferReply(true).queue()
        launch(event.hook) { hook -> hook.sendMessage(block(hook, member)).setEphemeral(true).submit().await() }
    }

    private fun launch(hook: InteractionHook, block: suspend (InteractionHook) -> Unit) {
        core.scope.launch(Dispatchers.IO) {
            try {
                block(hook)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                core.logger.log(Level.WARNING, "Discord: an interaction failed", e)
                runCatching { hook.sendMessage(text("discord.reply-error")).setEphemeral(true).queue() }
            }
        }
    }

    // ------------------------------------------------------------------ buttons

    override fun onButtonInteraction(event: ButtonInteractionEvent) {
        val parsed = Ids.parse(event.componentId) ?: return
        val id = parsed.id
        when (parsed.kind) {
            Ids.CLAIM -> if (permitted(event, StaffAction.CLAIM)) {
                deferred(event) { _, member ->
                    val actor = actor(member)
                    val current = core.reports.find(id) ?: return@deferred text("discord.reply-not-found", "id" to id.value)
                    val staff = actor.staff
                    val mine = staff != null && current.claim?.by?.sameAs(staff) == true
                    val override = allows(member, StaffAction.OVERRIDE)
                    describe(id, if (mine) core.reports.unclaim(id, actor, override) else core.reports.claim(id, actor, override))
                }
            }
            Ids.RESOLVE -> if (permitted(event, StaffAction.RESOLVE)) event.replyModal(bot.adapters.resolveModal(id)).queue()
            Ids.DISMISS -> if (permitted(event, StaffAction.RESOLVE)) event.replyModal(bot.adapters.dismissModal(id)).queue()
            Ids.REOPEN -> if (permitted(event, StaffAction.RESOLVE)) {
                deferred(event) { _, member -> describe(id, core.reports.reopen(id, actor(member))) }
            }
            Ids.PUNISH_CONFIRM -> confirmPunish(event, id, parsed.extra)
            Ids.PUNISH_CANCEL -> {
                if (parsed.extra.firstOrNull() != event.user.id) return
                event.editMessage(text("discord.punish-cancelled")).setComponents().queue()
            }
        }
    }

    private fun confirmPunish(event: ButtonInteractionEvent, id: ReportId, extra: List<String>) {
        val key = extra.getOrNull(0) ?: return
        // Only the member who chose the action may confirm it.
        if (extra.getOrNull(1) != event.user.id) {
            event.reply(text("discord.punish-not-yours")).setEphemeral(true).queue()
            return
        }
        if (!permitted(event, StaffAction.PUNISH)) return
        val member = event.member ?: return
        event.deferEdit().queue()
        launch(event.hook) { hook ->
            val actor = actor(member)
            val answer = when (val result = runner.run(id, key, actor, allows(member, StaffAction.OVERRIDE))) {
                is ActionRunner.RunResult.Ran -> {
                    val thread = core.reports.find(id)?.discord?.threadId
                    if (thread != null) {
                        bot.ready?.guild?.getThreadChannelById(thread)?.sendMessage(
                            bot.adapters.plain(
                                text("discord.log-action", "staff" to DiscordText.escape(actor.displayName), "action" to DiscordText.escape(result.action.label), "command" to result.command),
                            ),
                        )?.queue()
                    }
                    text("discord.punish-ran", "command" to result.command) +
                        (result.resolved?.let { "\n" + describe(id, it) } ?: "")
                }
                is ActionRunner.RunResult.Failed -> text("discord.punish-failed", "command" to result.command, "reason" to result.reason)
                is ActionRunner.RunResult.NotRun -> previewProblem(id, result.preview)
            }
            hook.editOriginal(answer).setComponents().submit().await()
        }
    }

    // ------------------------------------------------------------------ the punishment menu

    override fun onStringSelectInteraction(event: StringSelectInteractionEvent) {
        val parsed = Ids.parse(event.componentId) ?: return
        if (parsed.kind != Ids.PUNISH || !permitted(event, StaffAction.PUNISH)) return
        val key = event.values.firstOrNull() ?: return
        val member = event.member ?: return
        event.deferReply(true).queue()
        launch(event.hook) { hook ->
            when (val preview = runner.preview(parsed.id, key, actor(member), allows(member, StaffAction.OVERRIDE))) {
                is ActionRunner.Preview.Ready -> hook.sendMessage(
                    text("discord.punish-preview", "action" to DiscordText.escape(preview.action.label), "player" to DiscordText.escape(preview.report.reported.name), "command" to preview.command),
                ).setEphemeral(true).setComponents(
                    ActionRow.of(
                        Button.danger(Ids.of(Ids.PUNISH_CONFIRM, parsed.id, key, event.user.id), text("discord.button-run")),
                        Button.secondary(Ids.of(Ids.PUNISH_CANCEL, parsed.id, event.user.id), text("discord.button-cancel")),
                    ),
                ).submit().await()
                else -> hook.sendMessage(previewProblem(parsed.id, preview)).setEphemeral(true).submit().await()
            }
        }
    }

    private fun previewProblem(id: ReportId, preview: ActionRunner.Preview): String = when (preview) {
        is ActionRunner.Preview.Ready -> ""
        is ActionRunner.Preview.Refused -> text("discord.punish-refused", "reason" to preview.reason)
        ActionRunner.Preview.NotFound -> text("discord.reply-not-found", "id" to id.value)
        ActionRunner.Preview.UnknownAction -> text("discord.punish-unknown")
        ActionRunner.Preview.Closed -> text("discord.reply-rejected-already-closed", "id" to id.value)
        is ActionRunner.Preview.ClaimedByOther -> text("discord.reply-rejected-claimed-by-other", "id" to id.value, "staff" to DiscordText.escape(preview.staff))
    }

    // ------------------------------------------------------------------ modals

    override fun onModalInteraction(event: ModalInteractionEvent) {
        val parsed = Ids.parse(event.modalId) ?: return
        val note = event.getValue(Ids.FIELD_NOTE)?.asString?.trim()?.takeIf { it.isNotEmpty() }
        when (parsed.kind) {
            Ids.MODAL_RESOLVE -> {
                if (!permitted(event, StaffAction.RESOLVE)) return
                val outcome = when (event.getValue(Ids.FIELD_OUTCOME)?.asStringList?.firstOrNull()) {
                    "none" -> Outcome.NO_ACTION
                    else -> Outcome.ACTION_TAKEN
                }
                deferred(event) { _, member ->
                    describe(parsed.id, core.reports.resolve(parsed.id, actor(member), outcome, note, allows(member, StaffAction.OVERRIDE)))
                }
            }
            Ids.MODAL_DISMISS -> {
                if (!permitted(event, StaffAction.RESOLVE)) return
                deferred(event) { _, member ->
                    describe(parsed.id, core.reports.dismiss(parsed.id, actor(member), note, allows(member, StaffAction.OVERRIDE)))
                }
            }
        }
    }

    // ------------------------------------------------------------------ slash commands

    override fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {
        when (event.name) {
            "link" -> {
                val code = event.getOption("code")?.asString.orEmpty()
                event.deferReply(true).queue()
                val user = event.user
                launch(event.hook) { hook ->
                    val answer = when (val result = core.links.redeem(code, user.idLong, user.name)) {
                        is LinkResult.Linked -> text("discord.link-ok", "player" to DiscordText.escape(result.link.minecraft.name))
                        LinkResult.InvalidCode -> text("discord.link-invalid")
                    }
                    hook.sendMessage(answer).setEphemeral(true).submit().await()
                }
            }
            "reports" -> {
                if (!permitted(event, StaffAction.VIEW)) return
                when (event.subcommandName) {
                    "open" -> deferred(event) { _, _ -> openList() }
                    "lookup" -> {
                        val name = event.getOption("player")?.asString.orEmpty().trim()
                        deferred(event) { _, _ -> lookup(name) }
                    }
                }
            }
        }
    }

    private suspend fun openList(): String {
        val reports = core.reports.list(ReportQuery(ReportStatus.ACTIVE, limit = LIST_LIMIT))
        if (reports.isEmpty()) return text("discord.open-empty")
        val total = core.reports.count(ReportQuery(ReportStatus.ACTIVE))
        val lines = reports.map { r ->
            text(
                "discord.open-line",
                "id" to r.id.value,
                "thread" to (r.discord?.let { "<#${it.threadId}>" } ?: DiscordText.escape(r.reported.name)),
                "category" to DiscordText.escape(core.config.reasons.displayName(r.category)),
                "count" to r.reporterCount,
                "since" to "<t:${r.createdAt.epochSecond}:R>",
                "status" to bot.content.status(r),
            )
        }
        return DiscordText.cut(text("discord.open-title", "count" to total) + "\n" + lines.joinToString("\n"), DiscordLimits.MESSAGE)
    }

    private suspend fun lookup(name: String): String {
        val player: PlayerRef = core.directory.byName(name) ?: return text("discord.lookup-unknown", "player" to DiscordText.escape(name))
        val reports = core.reports.history(player.uuid, limit = LIST_LIMIT)
        if (reports.isEmpty()) return text("discord.lookup-empty", "player" to DiscordText.escape(player.name))
        val total = core.reports.countAgainst(player.uuid)
        val lines = reports.map { r ->
            text(
                "discord.lookup-line",
                "id" to r.id.value,
                "thread" to (r.discord?.let { "<#${it.threadId}>" } ?: "#${r.id.value}"),
                "category" to DiscordText.escape(core.config.reasons.displayName(r.category)),
                "count" to r.reporterCount,
                "since" to "<t:${r.createdAt.epochSecond}:R>",
                "status" to bot.content.status(r),
            )
        }
        return DiscordText.cut(
            text("discord.lookup-title", "player" to DiscordText.escape(player.name), "count" to total) + "\n" + lines.joinToString("\n"),
            DiscordLimits.MESSAGE,
        )
    }

    // ------------------------------------------------------------------ notes typed in threads

    override fun onMessageReceived(event: MessageReceivedEvent) {
        if (!settings.syncNotes || !event.isFromGuild || !event.channelType.isThread) return
        if (event.author.isBot || event.isWebhookMessage) return
        val member = event.member ?: return
        if (event.guild.idLong != settings.guildId || !allows(member, StaffAction.NOTE)) return
        val body = event.message.contentDisplay.trim()
        if (body.isEmpty()) return
        val threadId = event.channel.idLong
        val messageId = event.messageIdLong
        core.scope.launch(Dispatchers.IO) {
            try {
                val report = core.reports.findByThread(threadId) ?: return@launch
                when (core.reports.addNote(report.id, actor(member), body, messageId)) {
                    is NoteResult.Added -> event.message.addReaction(Emoji.fromUnicode(NOTE_EMOJI)).queue({}, {})
                    else -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                core.logger.log(Level.WARNING, "Discord: couldn't save a note from a thread", e)
            }
        }
    }

    // ------------------------------------------------------------------ answers

    private fun describe(id: ReportId, result: TransitionResult): String {
        val idPair = "id" to id.value
        return when (result) {
            is TransitionResult.Done -> when (result.change) {
                Change.CLAIMED -> text("discord.reply-claimed", idPair)
                Change.TAKEN_OVER -> text("discord.reply-taken-over", idPair)
                Change.UNCLAIMED -> text("discord.reply-unclaimed", idPair)
                Change.RESOLVED -> text("discord.reply-resolved", idPair, "outcome" to bot.content.outcome(result.report.closure?.outcome))
                Change.DISMISSED -> text("discord.reply-dismissed", idPair)
                Change.REOPENED -> text("discord.reply-reopened", idPair)
            }
            is TransitionResult.Rejected -> when (val reason = result.reason) {
                Rejection.NeedsIdentity -> text("discord.reply-rejected-needs-identity", idPair)
                Rejection.AlreadyYours -> text("discord.reply-rejected-already-yours", idPair)
                is Rejection.ClaimedByOther -> text("discord.reply-rejected-claimed-by-other", idPair, "staff" to DiscordText.escape(reason.name))
                Rejection.NotClaimed -> text("discord.reply-rejected-not-claimed", idPair)
                Rejection.AlreadyClosed -> text("discord.reply-rejected-already-closed", idPair)
                Rejection.NotClosed -> text("discord.reply-rejected-not-closed", idPair)
            }
            TransitionResult.NotFound -> text("discord.reply-not-found", idPair)
            TransitionResult.Cancelled -> text("discord.reply-cancelled", idPair)
            TransitionResult.Busy -> text("discord.reply-busy", idPair)
        }
    }

    private companion object {
        const val LIST_LIMIT = 15
        const val NOTE_EMOJI = "📝"
    }
}
