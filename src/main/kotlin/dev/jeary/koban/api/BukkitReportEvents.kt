package dev.jeary.koban.api

import dev.jeary.koban.api.event.ReportClaimEvent
import dev.jeary.koban.api.event.ReportCreateEvent
import dev.jeary.koban.api.event.ReportResolveEvent
import dev.jeary.koban.api.event.ReportUpdateEvent
import dev.jeary.koban.domain.actor.Actor
import dev.jeary.koban.domain.report.Change
import dev.jeary.koban.domain.report.Report
import dev.jeary.koban.domain.report.Transition
import dev.jeary.koban.service.ReportChange
import dev.jeary.koban.service.ReportDraft
import dev.jeary.koban.service.ReportListener
import dev.jeary.koban.service.ReportVeto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.bukkit.Bukkit

/**
 * Bridges [dev.jeary.koban.service.ReportService] to Bukkit's event system. Wherever a change
 * came from (game, Discord, API), the events are fired on the main thread.
 */
class BukkitReportEvents(private val main: CoroutineDispatcher) : ReportVeto, ReportListener {

    override suspend fun allowCreate(draft: ReportDraft, mergeInto: Report?): Boolean = withContext(main) {
        val event = ReportCreateEvent(draft.reporter, draft.reported, draft.category, draft.details, mergeInto)
        Bukkit.getPluginManager().callEvent(event)
        !event.isCancelled
    }

    override suspend fun allowTransition(transition: Transition.Applied, actor: Actor): Boolean {
        val event = when (transition.change) {
            Change.CLAIMED, Change.TAKEN_OVER ->
                ReportClaimEvent(transition.before, actor, transition.change == Change.TAKEN_OVER)
            Change.RESOLVED, Change.DISMISSED -> {
                val outcome = transition.after.closure?.outcome ?: return true
                ReportResolveEvent(transition.before, actor, outcome, transition.change == Change.DISMISSED)
            }
            Change.UNCLAIMED, Change.REOPENED -> return true
        }
        return withContext(main) {
            Bukkit.getPluginManager().callEvent(event)
            !event.isCancelled
        }
    }

    override suspend fun onChange(change: ReportChange) {
        val event = when (change) {
            is ReportChange.Created -> ReportUpdateEvent(change.report, ReportUpdateEvent.Kind.CREATED, null)
            is ReportChange.Merged -> ReportUpdateEvent(change.report, ReportUpdateEvent.Kind.MERGED, null)
            is ReportChange.Transitioned ->
                ReportUpdateEvent(change.report, ReportUpdateEvent.kindOf(change.transition.change), change.actor)
            is ReportChange.NoteAdded -> ReportUpdateEvent(change.report, ReportUpdateEvent.Kind.NOTE_ADDED, null)
        }
        withContext(main) { Bukkit.getPluginManager().callEvent(event) }
    }
}
