package dev.jeary.koban.game

import dev.jeary.koban.KobanCore
import dev.jeary.koban.command.Permissions
import dev.jeary.koban.domain.actor.Actor
import dev.jeary.koban.domain.audit.AuditAction
import dev.jeary.koban.domain.report.Change
import dev.jeary.koban.domain.report.Outcome
import dev.jeary.koban.domain.report.Rejection
import dev.jeary.koban.domain.report.Report
import dev.jeary.koban.domain.report.ReportId
import dev.jeary.koban.platform.Messenger.Companion.tag
import dev.jeary.koban.service.NoteResult
import dev.jeary.koban.service.TransitionResult
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * What staff can do to a report from in game or the console. The menus and `/reports …`
 * subcommands both come through here, so permissions, messages and audit entries are the same
 * whichever way staff choose. Main thread; storage calls suspend.
 */
class StaffActions(private val core: KobanCore) {

    private val messenger get() = core.messenger
    private val reports get() = core.reports

    /** The acting player (with their linked Discord account, so the audit log has both), or the console. */
    suspend fun actor(sender: CommandSender): Actor = when (sender) {
        is Player -> Actor.player(sender.uniqueId, sender.name, core.links.byMinecraft(sender.uniqueId)?.discord)
        else -> Actor.CONSOLE
    }

    private fun mayOverride(sender: CommandSender) = sender !is Player || sender.hasPermission(Permissions.OVERRIDE)

    /** Claims the report, takes it over (with override), or gives it back if it's already yours. */
    suspend fun toggleClaim(sender: CommandSender, id: ReportId): TransitionResult {
        val actor = actor(sender)
        val current = reports.find(id) ?: return TransitionResult.NotFound.also { notFound(sender, id) }
        val staff = actor.staff
        val mine = staff != null && current.claim?.by?.sameAs(staff) == true
        val result = if (mine) reports.unclaim(id, actor) else reports.claim(id, actor, mayOverride(sender))
        tell(sender, id, result)
        return result
    }

    suspend fun claim(sender: CommandSender, id: ReportId) = reports.claim(id, actor(sender), mayOverride(sender)).also { tell(sender, id, it) }

    suspend fun unclaim(sender: CommandSender, id: ReportId) = reports.unclaim(id, actor(sender), mayOverride(sender)).also { tell(sender, id, it) }

    suspend fun resolve(sender: CommandSender, id: ReportId, outcome: Outcome, note: String?) =
        reports.resolve(id, actor(sender), outcome, note, mayOverride(sender)).also { tell(sender, id, it) }

    suspend fun dismiss(sender: CommandSender, id: ReportId, note: String?) =
        reports.dismiss(id, actor(sender), note, mayOverride(sender)).also { tell(sender, id, it) }

    suspend fun reopen(sender: CommandSender, id: ReportId) = reports.reopen(id, actor(sender)).also { tell(sender, id, it) }

    suspend fun note(sender: CommandSender, id: ReportId, text: String): NoteResult {
        val result = reports.addNote(id, actor(sender), text)
        when (result) {
            is NoteResult.Added -> messenger.send(sender, "staff.note-added", tag("id", id.value))
            NoteResult.Empty -> messenger.send(sender, "staff.note-empty")
            NoteResult.NotFound -> notFound(sender, id)
            NoteResult.Duplicate -> Unit
        }
        return result
    }

    /** Teleports [player] to where the report was made. */
    suspend fun teleport(player: Player, report: Report): Boolean {
        val position = report.location
        val world = position?.let { Bukkit.getWorld(it.world) }
        if (position == null || world == null) {
            messenger.send(player, "staff.no-location", tag("id", report.id.value))
            return false
        }
        val target = Location(world, position.x + 0.5, position.y.toDouble(), position.z + 0.5, player.location.yaw, player.location.pitch)
        if (!player.teleport(target)) return false
        messenger.send(player, "staff.teleported", tag("id", report.id.value), tag("location", position))
        core.audit.record(report.id, AuditAction.TELEPORTED, actor(player), mapOf("location" to position.toString()))
        return true
    }

    fun notFound(sender: CommandSender, id: ReportId) = messenger.send(sender, "staff.not-found", tag("id", id.value))

    /** One message for every way a change can end. */
    fun tell(sender: CommandSender, id: ReportId, result: TransitionResult) {
        val idTag = tag("id", id.value)
        when (result) {
            is TransitionResult.Done -> {
                val player = tag("player", result.report.reported.name)
                when (result.change) {
                    Change.CLAIMED -> messenger.send(sender, "staff.claimed", idTag, player)
                    Change.TAKEN_OVER -> messenger.send(sender, "staff.taken-over", idTag, player)
                    Change.UNCLAIMED -> messenger.send(sender, "staff.unclaimed", idTag, player)
                    Change.RESOLVED -> messenger.send(sender, "staff.resolved", idTag, player, tag("outcome", core.presenter.outcome(result.report)))
                    Change.DISMISSED -> messenger.send(sender, "staff.dismissed", idTag, player)
                    Change.REOPENED -> messenger.send(sender, "staff.reopened", idTag, player)
                }
            }
            is TransitionResult.Rejected -> when (val reason = result.reason) {
                Rejection.NeedsIdentity -> messenger.send(sender, "staff.rejected.needs-identity", idTag)
                Rejection.AlreadyYours -> messenger.send(sender, "staff.rejected.already-yours", idTag)
                is Rejection.ClaimedByOther -> messenger.send(sender, "staff.rejected.claimed-by-other", idTag, tag("staff", reason.name))
                Rejection.NotClaimed -> messenger.send(sender, "staff.rejected.not-claimed", idTag)
                Rejection.AlreadyClosed -> messenger.send(sender, "staff.rejected.already-closed", idTag)
                Rejection.NotClosed -> messenger.send(sender, "staff.rejected.not-closed", idTag)
            }
            TransitionResult.NotFound -> notFound(sender, id)
            TransitionResult.Cancelled -> messenger.send(sender, "staff.cancelled", idTag)
            TransitionResult.Busy -> messenger.send(sender, "command.busy")
        }
    }
}
