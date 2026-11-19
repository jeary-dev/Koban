package dev.jeary.koban.domain.report

import dev.jeary.koban.domain.actor.Actor
import dev.jeary.koban.domain.actor.Source
import java.time.Instant

/** Something staff can do to a report. [override] lets them act on a report someone else claimed. */
sealed interface ReportCommand {
    val actor: Actor
    val override: Boolean

    data class Claim(override val actor: Actor, override val override: Boolean = false) : ReportCommand
    data class Unclaim(override val actor: Actor, override val override: Boolean = false) : ReportCommand
    data class Resolve(override val actor: Actor, val outcome: Outcome, override val override: Boolean = false) : ReportCommand
    data class Dismiss(override val actor: Actor, override val override: Boolean = false) : ReportCommand
    data class Reopen(override val actor: Actor, override val override: Boolean = false) : ReportCommand
}

/** What a successful transition did, for audit entries, alerts and the Discord embed. */
enum class Change { CLAIMED, TAKEN_OVER, UNCLAIMED, RESOLVED, DISMISSED, REOPENED }

/** Why a command was refused. Each maps to one message in messages.yml and on Discord. */
sealed interface Rejection {
    /** The console (or an API caller) has no identity to claim with. */
    data object NeedsIdentity : Rejection
    data object AlreadyYours : Rejection
    data class ClaimedByOther(val name: String) : Rejection
    data object NotClaimed : Rejection
    data object AlreadyClosed : Rejection
    data object NotClosed : Rejection
}

sealed interface Transition {
    data class Applied(val before: Report, val after: Report, val change: Change) : Transition
    data class Rejected(val reason: Rejection) : Transition
}

/**
 * The report state machine:
 *
 * ```
 *  OPEN ⇄ CLAIMED          claim / unclaim (taking over someone else's claim needs override)
 *  OPEN | CLAIMED → RESOLVED(outcome) | DISMISSED
 *  RESOLVED | DISMISSED → OPEN                    reopen (claim and closure are cleared)
 * ```
 *
 * Pure: it never touches storage. The caller writes [Transition.Applied.after] with an
 * optimistic-lock check on [Report.version], and re-runs the command against fresh state if
 * another change got there first.
 */
object ReportLifecycle {

    fun apply(report: Report, command: ReportCommand, now: Instant): Transition = when (command) {
        is ReportCommand.Claim -> claim(report, command, now)
        is ReportCommand.Unclaim -> unclaim(report, command, now)
        is ReportCommand.Resolve -> close(report, command, ReportStatus.RESOLVED, command.outcome, Change.RESOLVED, now)
        is ReportCommand.Dismiss -> close(report, command, ReportStatus.DISMISSED, Outcome.NO_ACTION, Change.DISMISSED, now)
        is ReportCommand.Reopen -> reopen(report, now)
    }

    private fun claim(report: Report, command: ReportCommand.Claim, now: Instant): Transition {
        val staff = command.actor.staff ?: return Transition.Rejected(Rejection.NeedsIdentity)
        return when (report.status) {
            ReportStatus.OPEN -> applied(report, report.copy(status = ReportStatus.CLAIMED, claim = Claim(staff, now)), Change.CLAIMED, now)
            ReportStatus.CLAIMED -> {
                val current = report.claim ?: return Transition.Rejected(Rejection.NotClaimed)
                when {
                    current.by.sameAs(staff) -> Transition.Rejected(Rejection.AlreadyYours)
                    command.override -> applied(report, report.copy(claim = Claim(staff, now)), Change.TAKEN_OVER, now)
                    else -> Transition.Rejected(Rejection.ClaimedByOther(current.by.name))
                }
            }
            ReportStatus.RESOLVED, ReportStatus.DISMISSED -> Transition.Rejected(Rejection.AlreadyClosed)
        }
    }

    private fun unclaim(report: Report, command: ReportCommand.Unclaim, now: Instant): Transition {
        val current = report.claim
        return when {
            report.status.isClosed -> Transition.Rejected(Rejection.AlreadyClosed)
            current == null -> Transition.Rejected(Rejection.NotClaimed)
            !command.mayActOn(current) -> Transition.Rejected(Rejection.ClaimedByOther(current.by.name))
            else -> applied(report, report.copy(status = ReportStatus.OPEN, claim = null), Change.UNCLAIMED, now)
        }
    }

    private fun close(
        report: Report,
        command: ReportCommand,
        status: ReportStatus,
        outcome: Outcome,
        change: Change,
        now: Instant,
    ): Transition {
        if (report.status.isClosed) return Transition.Rejected(Rejection.AlreadyClosed)
        val claim = report.claim
        if (claim != null && !command.mayActOn(claim)) return Transition.Rejected(Rejection.ClaimedByOther(claim.by.name))
        val closure = Closure(outcome, command.actor.staff, command.actor.displayName, now)
        return applied(report, report.copy(status = status, claim = null, closure = closure), change, now)
    }

    private fun reopen(report: Report, now: Instant): Transition =
        if (report.status.isActive) {
            Transition.Rejected(Rejection.NotClosed)
        } else {
            applied(report, report.copy(status = ReportStatus.OPEN, claim = null, closure = null), Change.REOPENED, now)
        }

    /** The claimant themselves, anyone with override, or the console (which outranks every claim). */
    private fun ReportCommand.mayActOn(claim: Claim): Boolean {
        val staff = actor.staff ?: return actor.source == Source.CONSOLE
        return override || claim.by.sameAs(staff)
    }

    private fun applied(before: Report, after: Report, change: Change, now: Instant) =
        Transition.Applied(before, after.copy(updatedAt = now, discordDirty = true, version = before.version + 1), change)
}
