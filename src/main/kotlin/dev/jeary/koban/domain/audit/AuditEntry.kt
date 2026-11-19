package dev.jeary.koban.domain.audit

import dev.jeary.koban.domain.actor.Actor
import dev.jeary.koban.domain.report.ReportId
import java.time.Instant

enum class AuditAction {
    REPORT_CREATED,
    REPORT_MERGED,
    CLAIMED,
    TAKEN_OVER,
    UNCLAIMED,
    RESOLVED,
    DISMISSED,
    REOPENED,
    NOTE_ADDED,
    ACTION_EXECUTED,
    ACTION_FAILED,
    TELEPORTED,
    ACCOUNT_LINKED,
    ACCOUNT_UNLINKED,
    CONFIG_RELOADED,
}

/** One line of the audit log: who did what, from where, to which report. */
data class AuditEntry(
    val id: Long,
    val reportId: ReportId?,
    val action: AuditAction,
    val actor: Actor,
    /** Small, flat facts about the action (outcome, command run, note excerpt). Never secrets. */
    val detail: Map<String, String>,
    val at: Instant,
)
