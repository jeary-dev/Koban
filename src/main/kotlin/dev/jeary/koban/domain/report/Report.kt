package dev.jeary.koban.domain.report

import dev.jeary.koban.domain.actor.Source
import dev.jeary.koban.domain.actor.StaffRef
import java.time.Instant
import java.util.UUID

@JvmInline
value class ReportId(val value: Long) {
    override fun toString(): String = "#$value"
}

enum class ReportStatus {
    OPEN,
    CLAIMED,
    RESOLVED,
    DISMISSED,
    ;

    /** Open or claimed: still waiting for a decision. */
    val isActive: Boolean get() = this == OPEN || this == CLAIMED

    val isClosed: Boolean get() = !isActive

    companion object {
        val ACTIVE: Set<ReportStatus> = setOf(OPEN, CLAIMED)
        val CLOSED: Set<ReportStatus> = setOf(RESOLVED, DISMISSED)
    }
}

/** What a closed report's reporters are told. Never the punishment itself. */
enum class Outcome { ACTION_TAKEN, NO_ACTION }

/** A player as a report refers to them: the UUID is the identity, the name is as it was then. */
data class PlayerRef(val uuid: UUID, val name: String)

/** A block position, stored with the report so staff can teleport there. */
data class BlockPosition(val world: String, val x: Int, val y: Int, val z: Int) {
    override fun toString(): String = "$world $x, $y, $z"
}

data class Claim(val by: StaffRef, val at: Instant)

/** How and by whom a report was closed. [by] is null when the console closed it. */
data class Closure(val outcome: Outcome, val by: StaffRef?, val byName: String, val at: Instant)

/** Where the report lives on Discord, once it has been posted. */
data class DiscordRef(val threadId: Long, val messageId: Long)

/**
 * One report against one player. Several reporters can share it: duplicate reports within the
 * merge window join the existing report instead of creating new ones (see [MergePolicy]).
 *
 * [version] is an optimistic lock. Every change is written with `WHERE version = ?`, so two staff
 * acting at the same moment can never both succeed (see `ReportService`).
 */
data class Report(
    val id: ReportId,
    val status: ReportStatus,
    /** The category of the first report. Each reporter keeps their own in [Reporter.category]. */
    val category: String,
    val reported: PlayerRef,
    val server: String,
    val location: BlockPosition?,
    val reporterCount: Int,
    val createdAt: Instant,
    val lastReportedAt: Instant,
    val updatedAt: Instant,
    val claim: Claim?,
    val closure: Closure?,
    val discord: DiscordRef?,
    val discordDirty: Boolean,
    val version: Int,
) {
    init {
        require(reporterCount >= 1) { "A report has at least one reporter" }
        require((status == ReportStatus.CLAIMED) == (claim != null)) { "Only claimed reports carry a claim ($status)" }
        require(status.isClosed == (closure != null)) { "Only closed reports carry a closure ($status)" }
    }
}

enum class FeedbackState { NONE, PENDING, DELIVERED }

/** Someone who reported the player, with what they chose and wrote. */
data class Reporter(
    val reportId: ReportId,
    val player: PlayerRef,
    val category: String,
    val details: String?,
    val reportedAt: Instant,
    val feedback: FeedbackState,
)

/** A staff note on a report, written in game or in the Discord thread. */
data class Note(
    val id: Long,
    val reportId: ReportId,
    val source: Source,
    val author: StaffRef?,
    val authorName: String,
    val body: String,
    val discordMessageId: Long?,
    val createdAt: Instant,
)

/** How many reporters chose each category, most popular first (ties: first chosen first). */
fun categoryCounts(reporters: List<Reporter>): List<Pair<String, Int>> {
    val firstSeen = HashMap<String, Instant>()
    val counts = LinkedHashMap<String, Int>()
    for (reporter in reporters.sortedBy { it.reportedAt }) {
        firstSeen.putIfAbsent(reporter.category, reporter.reportedAt)
        counts.merge(reporter.category, 1, Int::plus)
    }
    return counts.entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { firstSeen.getValue(it.key) })
        .map { it.key to it.value }
}
