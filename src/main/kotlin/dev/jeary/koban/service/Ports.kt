package dev.jeary.koban.service

import dev.jeary.koban.domain.actor.Actor
import dev.jeary.koban.domain.evidence.EvidenceSnapshot
import dev.jeary.koban.domain.report.BlockPosition
import dev.jeary.koban.domain.report.Note
import dev.jeary.koban.domain.report.PlayerRef
import dev.jeary.koban.domain.report.Report
import dev.jeary.koban.domain.report.Reporter
import dev.jeary.koban.domain.report.Transition

/** A report as a player files it, before any checks. */
data class ReportDraft(
    val reporter: PlayerRef,
    val reported: PlayerRef,
    val category: String,
    val details: String?,
    val location: BlockPosition?,
    val evidence: EvidenceSnapshot?,
    val bypassCooldown: Boolean = false,
    val bypassLimit: Boolean = false,
)

/**
 * Lets other plugins veto a change before it's stored. The Bukkit implementation fires
 * `ReportCreateEvent`, `ReportClaimEvent` and `ReportResolveEvent` on the main thread.
 * Each method returns false when the change was cancelled.
 */
interface ReportVeto {
    suspend fun allowCreate(draft: ReportDraft, mergeInto: Report?): Boolean
    suspend fun allowTransition(transition: Transition.Applied, actor: Actor): Boolean

    object AllowAll : ReportVeto {
        override suspend fun allowCreate(draft: ReportDraft, mergeInto: Report?) = true
        override suspend fun allowTransition(transition: Transition.Applied, actor: Actor) = true
    }
}

/** Something that happened to a report, after it was stored. */
sealed interface ReportChange {
    val report: Report

    data class Created(override val report: Report, val reporter: Reporter) : ReportChange
    data class Merged(override val report: Report, val reporter: Reporter) : ReportChange
    data class Transitioned(override val report: Report, val transition: Transition.Applied, val actor: Actor, val note: Note?) : ReportChange
    data class NoteAdded(override val report: Report, val note: Note) : ReportChange
}

/**
 * Hears about every stored change: staff alerts, reporter feedback, the Discord thread, the
 * `ReportUpdateEvent`. Called off the main thread; implementations hop to it themselves.
 * A listener that throws is logged and never undoes the change.
 */
fun interface ReportListener {
    suspend fun onChange(change: ReportChange)
}
