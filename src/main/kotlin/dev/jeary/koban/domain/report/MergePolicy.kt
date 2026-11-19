package dev.jeary.koban.domain.report

import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Decides whether a new report joins an existing one.
 *
 * One report per player: a report against a player who already has an open or claimed report,
 * last reported within [window], joins it regardless of category (each reporter keeps their own).
 * Someone reporting the same player twice is told they already have.
 */
class MergePolicy(val window: Duration) {

    sealed interface Decision {
        data object CreateNew : Decision
        data class MergeInto(val report: Report) : Decision
        data class AlreadyReported(val report: Report) : Decision
    }

    /**
     * @param candidates active reports against the same player.
     * @param reportersOf the reporter UUIDs already on a candidate.
     */
    fun decide(
        candidates: List<Report>,
        reporter: UUID,
        now: Instant,
        reportersOf: (Report) -> Set<UUID>,
    ): Decision {
        val target = candidates
            .filter { it.status.isActive && !it.lastReportedAt.plus(window).isBefore(now) }
            .maxByOrNull { it.lastReportedAt }
            ?: return Decision.CreateNew
        return if (reporter in reportersOf(target)) Decision.AlreadyReported(target) else Decision.MergeInto(target)
    }
}

/** Highest priority first: more reporters, then older. */
val REPORT_PRIORITY: Comparator<Report> =
    compareByDescending<Report> { it.reporterCount }.thenBy { it.createdAt }.thenBy { it.id.value }

/**
 * A named priority level with a colour, chosen by reporter count. Configured in config.yml as
 * ascending thresholds; the highest threshold a report reaches wins.
 */
data class PriorityTier(val name: String, val minReporters: Int, val color: Int)

class PriorityScale(tiers: List<PriorityTier>) {
    val tiers: List<PriorityTier> = tiers.sortedBy { it.minReporters }

    init {
        require(this.tiers.isNotEmpty()) { "At least one priority tier is needed" }
        require(this.tiers.first().minReporters <= 1) { "The lowest tier must start at 1 reporter" }
    }

    fun of(report: Report): PriorityTier = of(report.reporterCount)

    fun of(reporters: Int): PriorityTier = tiers.last { it.minReporters <= reporters.coerceAtLeast(1) }

    companion object {
        val DEFAULT = PriorityScale(
            listOf(
                PriorityTier("Normal", 1, 0xE8A5B4),
                PriorityTier("Raised", 2, 0xD9738C),
                PriorityTier("Urgent", 4, 0xB33A55),
            ),
        )
    }
}
