package dev.jeary.koban.domain.abuse

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The limits that stop the report button being used as a weapon. */
data class AntiAbuseSettings(
    /** Time a reporter must wait between reports. Zero turns it off. */
    val cooldown: Duration,
    /** How many of a reporter's reports may be open at once. Zero means no limit. */
    val maxOpenPerReporter: Int,
)

sealed interface GateResult {
    data object Allowed : GateResult
    data object SelfReport : GateResult
    data class OnCooldown(val remaining: Duration) : GateResult
    data class TooManyOpen(val limit: Int) : GateResult
}

/**
 * Remembers when each player last reported. In memory on purpose: a restart resets cooldowns,
 * but the open-report limit is counted from the database and survives it.
 */
class CooldownTracker(private val clock: Clock) {
    private val lastReport = ConcurrentHashMap<UUID, Instant>()

    fun record(reporter: UUID) {
        lastReport[reporter] = clock.instant()
    }

    fun remaining(reporter: UUID, cooldown: Duration): Duration {
        val last = lastReport[reporter] ?: return Duration.ZERO
        val left = Duration.between(clock.instant(), last.plus(cooldown))
        return if (left.isNegative) Duration.ZERO else left
    }

    /** Forgets entries older than [cooldown], so the map can't grow without bound. */
    fun prune(cooldown: Duration) {
        val cutoff = clock.instant().minus(cooldown)
        lastReport.entries.removeIf { it.value.isBefore(cutoff) }
    }
}

/**
 * Checks a report before anything is stored, cheapest first. [precheck] covers self-reports and
 * the cooldown from memory; only when it passes does the caller read the reporter's open-report
 * count from the database for [checkOpenLimit].
 */
class ReportGate(private val cooldowns: CooldownTracker) {

    fun precheck(reporter: UUID, reported: UUID, settings: AntiAbuseSettings, bypassCooldown: Boolean): GateResult {
        if (reporter == reported) return GateResult.SelfReport
        if (!bypassCooldown && !settings.cooldown.isZero) {
            val remaining = cooldowns.remaining(reporter, settings.cooldown)
            if (!remaining.isZero) return GateResult.OnCooldown(remaining)
        }
        return GateResult.Allowed
    }

    /** Whether [openCount] needs reading at all. */
    fun limitApplies(settings: AntiAbuseSettings, bypassLimit: Boolean): Boolean =
        !bypassLimit && settings.maxOpenPerReporter > 0

    fun checkOpenLimit(openCount: Int, settings: AntiAbuseSettings): GateResult =
        if (openCount >= settings.maxOpenPerReporter && settings.maxOpenPerReporter > 0) {
            GateResult.TooManyOpen(settings.maxOpenPerReporter)
        } else {
            GateResult.Allowed
        }

    fun recordAccepted(reporter: UUID) = cooldowns.record(reporter)
}
