package dev.jeary.koban.game

import dev.jeary.koban.KobanCore
import dev.jeary.koban.command.Permissions
import dev.jeary.koban.domain.report.Change
import dev.jeary.koban.domain.report.Outcome
import dev.jeary.koban.domain.report.ReportStatus
import dev.jeary.koban.platform.Messenger.Companion.component
import dev.jeary.koban.platform.Messenger.Companion.tag
import dev.jeary.koban.platform.compat.Compat
import dev.jeary.koban.service.ReportChange
import dev.jeary.koban.service.ReportListener
import dev.jeary.koban.storage.repo.ReportQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.logging.Level

/**
 * Tells people in game what happened to reports:
 * - staff with `koban.notify` get a clickable alert for every new or merged report;
 * - reporters get "action was taken" / "no action needed" when their report closes (now if
 *   they're online, otherwise the next time they join), never the punishment itself;
 * - staff joining see how many reports are waiting.
 */
class StaffNotifier(private val core: KobanCore) : ReportListener {

    private val messenger get() = core.messenger

    override suspend fun onChange(change: ReportChange) {
        when (change) {
            is ReportChange.Created -> alert("staff.alert-new", change)
            is ReportChange.Merged -> alert("staff.alert-merged", change)
            is ReportChange.Transitioned -> if (change.transition.change in CLOSING) deliverToOnlineReporters(change)
            is ReportChange.NoteAdded -> Unit
        }
    }

    private suspend fun alert(key: String, change: ReportChange) {
        val reporter = when (change) {
            is ReportChange.Created -> change.reporter
            is ReportChange.Merged -> change.reporter
            else -> return
        }
        val report = change.report
        val category = core.config.reasons.displayName(reporter.category)
        core.logger.info(
            "Report #${report.id.value}: ${reporter.player.name} reported ${report.reported.name} for $category" +
                (if (report.reporterCount > 1) " (${report.reporterCount} reporters)" else ""),
        )
        withContext(core.main) {
            val tags = arrayOf(
                tag("id", report.id.value),
                tag("reporter", reporter.player.name),
                tag("player", report.reported.name),
                tag("category", category),
                tag("count", report.reporterCount),
                tag("details", reporter.details ?: messenger.plain("staff.no-details")),
            )
            val sound = core.config.core.notifications.alertSound
            for (staff in Bukkit.getOnlinePlayers()) {
                if (!staff.hasPermission(Permissions.NOTIFY)) continue
                messenger.send(staff, key, *tags)
                Compat.playSound(staff, sound, 0.6f, 1.4f)
            }
        }
    }

    private suspend fun deliverToOnlineReporters(change: ReportChange.Transitioned) {
        val uuids = core.reports.reporters(change.report.id).map { it.player.uuid }
        val online = withContext(core.main) { uuids.mapNotNull(Bukkit::getPlayer) }
        online.forEach { deliverFeedback(it) }
    }

    /** Hands [player] every closed-report message they're owed, exactly once. */
    suspend fun deliverFeedback(player: Player) {
        val owed = core.reports.takePendingFeedback(player.uniqueId)
        if (owed.isEmpty()) return
        withContext(core.main) {
            if (!player.isOnline) return@withContext
            for (item in owed) {
                val key = when (item.report.closure?.outcome) {
                    Outcome.ACTION_TAKEN -> "feedback.action-taken"
                    else -> "feedback.no-action"
                }
                messenger.send(player, key, tag("player", item.report.reported.name), tag("id", item.report.id.value))
            }
        }
    }

    /** The "N open reports" line for staff joining. */
    suspend fun joinSummary(player: Player) {
        val counts = core.reports.countByStatus()
        val open = (counts[ReportStatus.OPEN] ?: 0) + (counts[ReportStatus.CLAIMED] ?: 0)
        if (open == 0) return
        val mine = core.reports.count(ReportQuery(statuses = setOf(ReportStatus.CLAIMED), claimedBy = player.uniqueId))
        withContext(core.main) {
            if (!player.isOnline) return@withContext
            val claimed = if (mine > 0) messenger.component("staff.join-summary-claimed", tag("count", mine)) else Component.empty()
            messenger.send(player, "staff.join-summary", tag("open", open), component("claimed", claimed))
        }
    }

    /** Runs [block] in the plugin scope, logging failures (a summary never breaks a join). */
    fun launch(what: String, block: suspend () -> Unit) {
        core.scope.launch(core.main) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                core.logger.log(Level.WARNING, "Could not $what", e)
            }
        }
    }

    private companion object {
        val CLOSING = setOf(Change.RESOLVED, Change.DISMISSED)
    }
}
