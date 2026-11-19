package dev.jeary.koban.discord

import dev.jeary.koban.domain.evidence.EvidenceSnapshot
import dev.jeary.koban.domain.report.Outcome
import dev.jeary.koban.domain.report.PriorityScale
import dev.jeary.koban.domain.report.Report
import dev.jeary.koban.domain.report.ReportStatus
import dev.jeary.koban.domain.report.Reporter
import dev.jeary.koban.domain.report.categoryCounts
import java.time.Instant

/** Discord's own limits. Anything longer is cut, never rejected. */
object DiscordLimits {
    const val THREAD_NAME = 100
    const val EMBED_TITLE = 256
    const val EMBED_DESCRIPTION = 4096
    const val FIELD_NAME = 256
    const val FIELD_VALUE = 1024
    const val FIELDS = 25
    const val EMBED_TOTAL = 6000
    const val FORUM_TAGS = 5
    const val MESSAGE = 2000
}

data class EmbedField(val name: String, val value: String, val inline: Boolean = false)

/** An embed as plain data, turned into a JDA embed by [JdaAdapters]. */
data class EmbedSpec(
    val author: String,
    val title: String,
    val description: String?,
    val color: Int,
    val thumbnail: String?,
    val fields: List<EmbedField>,
    val footer: String,
    val timestamp: Instant,
) {
    /** Characters Discord counts towards its 6000 limit. */
    val length: Int get() = author.length + title.length + (description?.length ?: 0) + footer.length +
        fields.sumOf { it.name.length + it.value.length }
}

/** Escaping and cutting of text going to Discord. */
object DiscordText {
    private val MARKDOWN = Regex("""([\\*_~`|>#\-\[\]()])""")

    /**
     * Escapes Markdown so a player called `__x__` or chat containing `**` shows as typed.
     * Mentions are disabled on every message Koban sends, so `@everyone` in chat pings nobody.
     */
    fun escape(text: String): String = MARKDOWN.replace(text, """\\$1""")

    /** Cuts to [max] characters with "…", never splitting a surrogate pair. */
    fun cut(text: String, max: Int): String {
        if (text.length <= max) return text
        var end = max - 1
        if (end > 0 && Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end) + "…"
    }

    /** Keeps whole lines from the end (the newest chat) while they fit in [max]. */
    fun lastLines(lines: List<String>, max: Int): String {
        val kept = ArrayDeque<String>()
        var size = 0
        for (line in lines.asReversed()) {
            val add = line.length + if (kept.isEmpty()) 0 else 1
            if (size + add > max) break
            kept.addFirst(line)
            size += add
        }
        if (kept.isEmpty() && lines.isNotEmpty()) return cut(lines.last(), max)
        return kept.joinToString("\n")
    }
}

/**
 * Everything that appears on Discord for a report, as plain data: the thread title, the forum
 * tags and the embed. Texts come from messages.yml through [text]; values players typed are
 * escaped before they're inserted.
 */
class ThreadContent(
    private val text: (key: String, values: Map<String, String>) -> String,
    private val categoryName: (String) -> String,
    private val priority: PriorityScale,
    private val avatar: (Report) -> String?,
) {

    /** `#42 · Cheating +1 · Notch`, always under Discord's 100 characters. */
    fun title(report: Report): String {
        val extra = if (report.reporterCount > 1) " +${report.reporterCount - 1}" else ""
        val prefix = "#${report.id.value} · "
        val suffix = " · " + DiscordText.cut(report.reported.name, MAX_NAME)
        val room = DiscordLimits.THREAD_NAME - prefix.length - suffix.length - extra.length
        val category = DiscordText.cut(categoryName(report.category), room.coerceAtLeast(1))
        return DiscordText.cut(prefix + category + extra + suffix, DiscordLimits.THREAD_NAME)
    }

    /**
     * Tag names for a report: its status first, then its categories by reporter count, five at
     * most (Discord's limit). Only names that exist in [available] are used.
     */
    fun tags(report: Report, reporters: List<Reporter>, available: Set<String>): List<String> {
        val known = available.associateBy { it.lowercase() }
        val wanted = listOf(statusTag(report.status)) + categoryCounts(reporters).map { categoryName(it.first) }
        return wanted.mapNotNull { known[it.lowercase()] }.distinct().take(DiscordLimits.FORUM_TAGS)
    }

    fun statusTag(status: ReportStatus): String = text("discord.tag-" + status.name.lowercase(), emptyMap())

    /** Every tag Koban may want on the forum: four statuses and each category. */
    fun allTagNames(categories: List<String>): List<String> =
        ReportStatus.entries.map(::statusTag) + categories.map(categoryName)

    fun color(report: Report): Int = when (report.status) {
        ReportStatus.OPEN, ReportStatus.CLAIMED -> priority.of(report).color
        ReportStatus.RESOLVED -> CLOSED_ACTION
        ReportStatus.DISMISSED -> CLOSED_NONE
    }

    fun status(report: Report): String = when (report.status) {
        ReportStatus.OPEN -> text("discord.status-open", emptyMap())
        ReportStatus.CLAIMED -> text("discord.status-claimed", mapOf("staff" to DiscordText.escape(report.claim?.by?.name ?: "?")))
        ReportStatus.RESOLVED -> text("discord.status-resolved", mapOf("outcome" to outcome(report.closure?.outcome), "staff" to DiscordText.escape(report.closure?.byName ?: "?")))
        ReportStatus.DISMISSED -> text("discord.status-dismissed", mapOf("staff" to DiscordText.escape(report.closure?.byName ?: "?")))
    }

    fun outcome(outcome: Outcome?): String = when (outcome) {
        Outcome.ACTION_TAKEN -> text("discord.outcome-action", emptyMap())
        Outcome.NO_ACTION, null -> text("discord.outcome-none", emptyMap())
    }

    fun embed(report: Report, reporters: List<Reporter>, evidence: List<EvidenceSnapshot>, noteCount: Int): EmbedSpec {
        val fields = ArrayList<EmbedField>()
        fun field(key: String, value: String, inline: Boolean = false) {
            if (value.isBlank()) return
            fields += EmbedField(
                DiscordText.cut(text(key, emptyMap()), DiscordLimits.FIELD_NAME),
                DiscordText.cut(value, DiscordLimits.FIELD_VALUE),
                inline,
            )
        }
        val first = evidence.firstOrNull()
        field("discord.field-status", status(report), inline = true)
        field("discord.field-priority", "${priority.of(report).name} · ${report.reporterCount}", inline = true)
        field(
            "discord.field-where",
            listOfNotNull(
                DiscordText.escape(report.server),
                report.location?.let { "${DiscordText.escape(it.world)} ${it.x}, ${it.y}, ${it.z}" },
            ).joinToString(" · "),
            inline = true,
        )
        field(
            "discord.field-categories",
            categoryCounts(reporters).joinToString("\n") { (key, count) -> "▪ ${DiscordText.escape(categoryName(key))} ×$count" },
        )
        field(
            "discord.field-reporters",
            DiscordText.lastLines(
                reporters.map { r ->
                    val details = r.details?.let { " — " + DiscordText.escape(it) } ?: ""
                    "**${DiscordText.escape(r.player.name)}** (${DiscordText.escape(categoryName(r.category))}) <t:${r.reportedAt.epochSecond}:R>$details"
                },
                DiscordLimits.FIELD_VALUE,
            ),
        )
        first?.reported?.let { p ->
            val state = if (!p.online) {
                text("discord.state-offline", emptyMap())
            } else {
                listOfNotNull(
                    p.gameMode?.lowercase(),
                    p.health?.let { "$it ♥" },
                    p.ping?.let { "${it}ms" },
                    p.clientBrand?.let(DiscordText::escape),
                    first.tps?.let { "TPS $it" },
                ).joinToString(" · ")
            }
            field("discord.field-player", state, inline = false)
        }
        val chat = evidence.flatMap { it.chat }.distinctBy { Triple(it.at, it.uuid, it.text) }.sortedBy { it.at }
        if (chat.isNotEmpty()) {
            val reported = report.reported.uuid.toString()
            val lines = chat.map { line ->
                val name = DiscordText.escape(line.name)
                val who = if (line.uuid == reported) "**$name**" else name
                "<t:${line.at / 1000}:T> $who: ${DiscordText.escape(line.text)}"
            }
            field("discord.field-chat", DiscordText.lastLines(lines, DiscordLimits.FIELD_VALUE))
        }
        val commands = evidence.flatMap { it.commands }.distinctBy { it.at to it.text }.sortedBy { it.at }
        if (commands.isNotEmpty()) {
            field(
                "discord.field-commands",
                DiscordText.lastLines(commands.map { "<t:${it.at / 1000}:T> ${DiscordText.escape(it.text)}" }, DiscordLimits.FIELD_VALUE),
            )
        }
        first?.nearby?.takeIf { it.isNotEmpty() }?.let { nearby ->
            field("discord.field-nearby", nearby.joinToString(", ") { "${DiscordText.escape(it.name)} (${it.distance}m)" })
        }

        val spec = EmbedSpec(
            author = DiscordText.cut(text("discord.embed-author", mapOf("id" to report.id.value.toString())), DiscordLimits.EMBED_TITLE),
            title = DiscordText.cut(report.reported.name, DiscordLimits.EMBED_TITLE),
            description = null,
            color = color(report),
            thumbnail = avatar(report),
            fields = fields.take(DiscordLimits.FIELDS),
            footer = text("discord.embed-footer", mapOf("notes" to noteCount.toString(), "uuid" to report.reported.uuid.toString())),
            timestamp = report.createdAt,
        )
        return fit(spec)
    }

    /** Drops the biggest evidence fields first until the embed is within 6000 characters. */
    private fun fit(spec: EmbedSpec): EmbedSpec {
        var current = spec
        while (current.length > DiscordLimits.EMBED_TOTAL && current.fields.isNotEmpty()) {
            val biggest = current.fields.withIndex().maxBy { it.value.value.length }
            val shrunk = biggest.value.copy(value = DiscordText.cut(biggest.value.value, biggest.value.value.length / 2))
            current = current.copy(fields = current.fields.toMutableList().also { it[biggest.index] = shrunk })
        }
        return current
    }

    private companion object {
        const val MAX_NAME = 32
        const val CLOSED_ACTION = 0xa8c8a0
        const val CLOSED_NONE = 0x8a7a80
    }
}
