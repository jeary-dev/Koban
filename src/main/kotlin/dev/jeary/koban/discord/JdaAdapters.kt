package dev.jeary.koban.discord

import dev.jeary.koban.config.ActionTemplate
import dev.jeary.koban.domain.report.Report
import dev.jeary.koban.domain.report.ReportId
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.label.Label
import net.dv8tion.jda.api.components.selections.SelectOption
import net.dv8tion.jda.api.components.selections.StringSelectMenu
import net.dv8tion.jda.api.components.textinput.TextInput
import net.dv8tion.jda.api.components.textinput.TextInputStyle
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.modals.Modal
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder
import net.dv8tion.jda.api.utils.messages.MessageCreateData
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder
import net.dv8tion.jda.api.utils.messages.MessageEditData

/**
 * Component IDs. Everything an interaction needs is in the ID (and re-read from the database),
 * so buttons keep working across restarts with no state held in memory.
 */
object Ids {
    const val PREFIX = "koban"
    const val CLAIM = "claim"
    const val RESOLVE = "resolve"
    const val DISMISS = "dismiss"
    const val REOPEN = "reopen"
    const val PUNISH = "punish"
    const val PUNISH_CONFIRM = "punish-yes"
    const val PUNISH_CANCEL = "punish-no"
    const val MODAL_RESOLVE = "m-resolve"
    const val MODAL_DISMISS = "m-dismiss"
    const val FIELD_OUTCOME = "outcome"
    const val FIELD_NOTE = "note"

    fun of(kind: String, id: ReportId, vararg extra: String) = (listOf(PREFIX, kind, id.value.toString()) + extra).joinToString(":")

    data class Parsed(val kind: String, val id: ReportId, val extra: List<String>)

    fun parse(raw: String): Parsed? {
        val parts = raw.split(':')
        if (parts.size < 3 || parts[0] != PREFIX) return null
        val id = parts[2].toLongOrNull()?.takeIf { it > 0 } ?: return null
        return Parsed(parts[1], ReportId(id), parts.drop(3))
    }
}

/** Turns Koban's plain data into JDA messages, components and modals. */
class JdaAdapters(private val text: (String) -> String) {

    fun embed(spec: EmbedSpec): MessageEmbed {
        val builder = EmbedBuilder()
            .setAuthor(spec.author)
            .setTitle(spec.title)
            .setColor(spec.color)
            .setFooter(spec.footer)
            .setTimestamp(spec.timestamp)
        spec.description?.let(builder::setDescription)
        spec.thumbnail?.let(builder::setThumbnail)
        spec.fields.forEach { builder.addField(it.name, it.value, it.inline) }
        return builder.build()
    }

    /** The buttons under a report: what can be done in its current state. */
    fun rows(report: Report, actions: List<ActionTemplate>): List<ActionRow> {
        val id = report.id
        if (report.status.isClosed) {
            return listOf(ActionRow.of(Button.secondary(Ids.of(Ids.REOPEN, id), text("discord.button-reopen"))))
        }
        val claimLabel = if (report.claim == null) text("discord.button-claim") else text("discord.button-claim-toggle")
        val buttons = ActionRow.of(
            Button.primary(Ids.of(Ids.CLAIM, id), claimLabel),
            Button.success(Ids.of(Ids.RESOLVE, id), text("discord.button-resolve")),
            Button.secondary(Ids.of(Ids.DISMISS, id), text("discord.button-dismiss")),
        )
        if (actions.isEmpty()) return listOf(buttons)
        val menu = StringSelectMenu.create(Ids.of(Ids.PUNISH, id))
            .setPlaceholder(text("discord.punish-placeholder"))
            .addOptions(
                actions.map { action ->
                    val option = SelectOption.of(action.label, action.key)
                        .withDescription(action.description.ifEmpty { null })
                    action.emoji?.let { runCatching { option.withEmoji(Emoji.fromFormatted(it)) }.getOrNull() } ?: option
                },
            )
            .build()
        return listOf(buttons, ActionRow.of(menu))
    }

    fun create(spec: EmbedSpec, rows: List<ActionRow>): MessageCreateData =
        MessageCreateBuilder()
            .setEmbeds(embed(spec))
            .setComponents(rows)
            .setAllowedMentions(emptyList())
            .build()

    fun edit(spec: EmbedSpec, rows: List<ActionRow>): MessageEditData =
        MessageEditBuilder()
            .setEmbeds(embed(spec))
            .setComponents(rows)
            .setAllowedMentions(emptyList())
            .build()

    /** Plain text with every mention switched off. */
    fun plain(content: String): MessageCreateData =
        MessageCreateBuilder()
            .setContent(DiscordText.cut(content, DiscordLimits.MESSAGE))
            .setAllowedMentions(emptyList())
            .build()

    /** Resolve in one step: the outcome as a select menu and an optional note, in one modal. */
    fun resolveModal(id: ReportId): Modal {
        val outcome = StringSelectMenu.create(Ids.FIELD_OUTCOME)
            .addOptions(
                SelectOption.of(text("discord.outcome-action"), "action").withDefault(true),
                SelectOption.of(text("discord.outcome-none"), "none"),
            )
            .setRequiredRange(1, 1)
            .build()
        return Modal.create(Ids.of(Ids.MODAL_RESOLVE, id), DiscordText.cut(text("discord.modal-resolve-title").replace("{id}", id.value.toString()), Modal.MAX_TITLE_LENGTH))
            .addComponents(
                Label.of(text("discord.modal-outcome"), outcome),
                Label.of(text("discord.modal-note"), noteInput()),
            )
            .build()
    }

    fun dismissModal(id: ReportId): Modal =
        Modal.create(Ids.of(Ids.MODAL_DISMISS, id), DiscordText.cut(text("discord.modal-dismiss-title").replace("{id}", id.value.toString()), Modal.MAX_TITLE_LENGTH))
            .addComponents(Label.of(text("discord.modal-reason"), noteInput()))
            .build()

    private fun noteInput(): TextInput =
        TextInput.create(Ids.FIELD_NOTE, TextInputStyle.PARAGRAPH)
            .setRequired(false)
            .setMaxLength(1000)
            .setPlaceholder(DiscordText.cut(text("discord.modal-note-placeholder"), TextInput.MAX_PLACEHOLDER_LENGTH))
            .build()
}
