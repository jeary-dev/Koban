package dev.jeary.koban.game

import dev.jeary.koban.KobanCore
import dev.jeary.koban.domain.evidence.ChatLine
import dev.jeary.koban.domain.evidence.PlayerState
import dev.jeary.koban.domain.report.Outcome
import dev.jeary.koban.domain.report.Report
import dev.jeary.koban.domain.report.ReportStatus
import dev.jeary.koban.domain.report.Wrap
import dev.jeary.koban.domain.report.categoryCounts
import dev.jeary.koban.domain.time.DurationText
import dev.jeary.koban.platform.Messenger.Companion.component
import dev.jeary.koban.platform.Messenger.Companion.tag
import dev.jeary.koban.service.ReportDetails
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Turns reports into the placeholders menus.yml and messages.yml use. Every value a player typed
 * is inserted as plain text; list placeholders (`<categories>`, `<chat>` …) expand to several
 * lore lines, each drawn from its own template in messages.yml.
 */
class ReportPresenter(private val core: KobanCore) {

    private val messenger get() = core.messenger

    fun status(report: Report): Component = when (report.status) {
        ReportStatus.OPEN -> messenger.component("status.open")
        ReportStatus.CLAIMED -> messenger.component("status.claimed", tag("staff", report.claim?.by?.name ?: "?"))
        ReportStatus.RESOLVED -> messenger.component("status.resolved", tag("outcome", outcome(report)))
        ReportStatus.DISMISSED -> messenger.component("status.dismissed")
    }

    fun outcome(report: Report): String = when (report.closure?.outcome) {
        Outcome.ACTION_TAKEN -> messenger.plain("outcome.action-taken")
        Outcome.NO_ACTION -> messenger.plain("outcome.no-action")
        null -> ""
    }

    fun age(at: Instant): String = DurationText.short(Duration.between(at, core.clock.instant()))

    fun time(at: Instant): String = TIME.format(at)

    /** Placeholders for a report in a list. */
    fun summary(report: Report): List<TagResolver> {
        val tier = core.config.core.priority.of(report)
        return listOf(
            tag("id", report.id.value),
            tag("player", report.reported.name),
            component("status", status(report)),
            tag("category", core.config.reasons.displayName(report.category)),
            tag("reporters", report.reporterCount),
            tag("extra", if (report.reporterCount > 1) "+${report.reporterCount - 1}" else ""),
            tag("priority", tier.name),
            tag("age", age(report.createdAt)),
            tag("last", age(report.lastReportedAt)),
            tag("created", time(report.createdAt)),
            tag("server", report.server),
            tag("location", report.location?.toString() ?: messenger.plain("view.unknown")),
            tag("claimed-by", report.claim?.by?.name ?: messenger.plain("view.nobody")),
            tag("outcome", outcome(report)),
            tag("closed-by", report.closure?.byName ?: messenger.plain("view.nobody")),
        )
    }

    /** Placeholders for the full report view: the summary plus evidence, reporters and notes. */
    fun details(details: ReportDetails): List<TagResolver> {
        val reasons = core.config.reasons
        val categories = categoryCounts(details.reporters).map { (key, count) ->
            messenger.component("view.category-line", tag("category", reasons.displayName(key)), tag("count", count))
        }
        val reporters = details.reporters.flatMap { r ->
            listOf(
                messenger.component(
                    "view.reporter-line",
                    tag("reporter", r.player.name), tag("category", reasons.displayName(r.category)), tag("age", age(r.reportedAt)),
                ),
            ) + r.details?.let { wrapped("view.details-line", it) }.orEmpty()
        }
        val snapshots = details.evidence
        val chat = snapshots.flatMap { it.chat }
            .distinctBy { Triple(it.at, it.uuid, it.text) }
            .sortedBy { it.at }
            .takeLast(core.config.core.evidence.chatLines * 2)
            .flatMap { chatLine(it, details.report.reported.uuid.toString()) }
        val commands = snapshots.flatMap { it.commands }.distinctBy { it.at to it.text }.sortedBy { it.at }
            .map { messenger.component("view.command-line", tag("time", time(Instant.ofEpochMilli(it.at))), tag("text", it.text)) }
        val first = snapshots.firstOrNull()
        val nearby = first?.nearby.orEmpty().map {
            messenger.component("view.nearby-line", tag("name", it.name), tag("distance", it.distance))
        }
        val notes = details.notes.takeLast(MAX_NOTES).flatMap { n ->
            listOf(
                messenger.component(
                    "view.note-line",
                    tag("author", n.authorName), tag("age", age(n.createdAt)), tag("source", n.source.name.lowercase()),
                ),
            ) + wrapped("view.details-line", n.body)
        }
        val reported = first?.reported
        return summary(details.report) + listOf(
            component("categories", lines(categories)),
            component("reporter-list", lines(reporters)),
            component("chat", lines(chat)),
            component("commands", lines(commands)),
            component("nearby", lines(nearby)),
            component("notes", lines(notes)),
            component("state", reported?.let(::state) ?: messenger.component("view.none")),
            tag("tps", first?.tps?.toString() ?: "?"),
            tag("note-count", details.notes.size),
        )
    }

    private fun state(p: PlayerState): Component =
        if (!p.online) {
            messenger.component("view.state-offline")
        } else {
            messenger.component(
                "view.state",
                tag("gamemode", p.gameMode?.lowercase() ?: "?"),
                tag("health", p.health ?: "?"),
                tag("ping", p.ping ?: "?"),
                tag("brand", p.clientBrand ?: "?"),
                tag("world", p.world ?: "?"),
            )
        }

    private fun chatLine(line: ChatLine, reportedUuid: String): List<Component> {
        val key = if (line.uuid == reportedUuid) "view.chat-line-reported" else "view.chat-line"
        val parts = Wrap.lines(line.text, WRAP, MAX_WRAPPED)
        val head = messenger.component(key, tag("time", time(Instant.ofEpochMilli(line.at))), tag("name", line.name), tag("text", parts.firstOrNull() ?: ""))
        return listOf(head) + parts.drop(1).map { messenger.component("view.wrap-line", tag("text", it)) }
    }

    private fun wrapped(key: String, text: String): List<Component> =
        Wrap.lines(text, WRAP, MAX_WRAPPED).map { messenger.component(key, tag("text", it)) }

    private fun lines(items: List<Component>): Component =
        if (items.isEmpty()) messenger.component("view.none") else Component.join(JoinConfiguration.newlines(), items)

    private companion object {
        const val WRAP = 40
        const val MAX_WRAPPED = 4
        const val MAX_NOTES = 5
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    }
}
