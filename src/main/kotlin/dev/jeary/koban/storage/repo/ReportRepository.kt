package dev.jeary.koban.storage.repo

import dev.jeary.koban.domain.actor.StaffRef
import dev.jeary.koban.domain.report.BlockPosition
import dev.jeary.koban.domain.report.Claim
import dev.jeary.koban.domain.report.Closure
import dev.jeary.koban.domain.report.DiscordRef
import dev.jeary.koban.domain.report.FeedbackState
import dev.jeary.koban.domain.report.Outcome
import dev.jeary.koban.domain.report.PlayerRef
import dev.jeary.koban.domain.report.Report
import dev.jeary.koban.domain.report.ReportId
import dev.jeary.koban.domain.report.ReportStatus
import dev.jeary.koban.domain.report.Reporter
import dev.jeary.koban.storage.Row
import dev.jeary.koban.storage.Tx
import java.time.Instant
import java.util.UUID

/** A report about to be stored for the first time. */
data class NewReport(
    val category: String,
    val reported: PlayerRef,
    val server: String,
    val location: BlockPosition?,
    val at: Instant,
)

/** Which reports a list shows, and in what order. */
data class ReportQuery(
    val statuses: Set<ReportStatus>,
    /** Only reports where at least one reporter chose this category. */
    val category: String? = null,
    /** Only reports against this player. */
    val reported: UUID? = null,
    /** Only reports claimed by this Minecraft account. */
    val claimedBy: UUID? = null,
    val order: Order = Order.PRIORITY,
    val limit: Int = 45,
    val offset: Int = 0,
) {
    enum class Order {
        /** Most reporters first, then oldest: what to look at next. */
        PRIORITY,

        /** Most recently changed first: for closed reports and history. */
        RECENT,
    }
}

/** A report paired with one of its reporters, for feedback delivery. */
data class ReporterFeedback(val report: Report, val reporter: Reporter)

class ReportRepository {

    fun insert(tx: Tx, report: NewReport): ReportId = ReportId(
        tx.insert(
            """
            INSERT INTO reports (status, category, reported_uuid, reported_name, server, world, x, y, z,
                                 reporter_count, created_at, last_reported_at, updated_at, discord_dirty, version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, 1, 0)
            """.trimIndent(),
            ReportStatus.OPEN, report.category, report.reported.uuid, report.reported.name, report.server,
            report.location?.world, report.location?.x, report.location?.y, report.location?.z,
            report.at, report.at, report.at,
        ),
    )

    fun find(tx: Tx, id: ReportId): Report? =
        tx.queryOne("SELECT $COLUMNS FROM reports WHERE id = ?", id.value, mapper = ::report)

    /** Open or claimed reports against [reported]: the candidates for merging. */
    fun findActiveAgainst(tx: Tx, reported: UUID): List<Report> =
        tx.query(
            "SELECT $COLUMNS FROM reports WHERE reported_uuid = ? AND status IN ('OPEN', 'CLAIMED') ORDER BY last_reported_at DESC",
            reported, mapper = ::report,
        )

    /**
     * Writes every mutable field of [after] if, and only if, the stored row is still at
     * [expectedVersion]. Returns false when someone else changed the report first.
     */
    fun update(tx: Tx, after: Report, expectedVersion: Int): Boolean =
        tx.update(
            """
            UPDATE reports SET
                status = ?, reporter_count = ?, last_reported_at = ?, updated_at = ?,
                claimed_by_uuid = ?, claimed_by_discord = ?, claimed_by_name = ?, claimed_at = ?,
                outcome = ?, closed_by_uuid = ?, closed_by_discord = ?, closed_by_name = ?, closed_at = ?,
                discord_dirty = ?, version = ?
            WHERE id = ? AND version = ?
            """.trimIndent(),
            after.status, after.reporterCount, after.lastReportedAt, after.updatedAt,
            after.claim?.by?.minecraft, after.claim?.by?.discord, after.claim?.by?.name, after.claim?.at,
            after.closure?.outcome, after.closure?.by?.minecraft, after.closure?.by?.discord, after.closure?.byName,
            after.closure?.at,
            after.discordDirty, after.version,
            after.id.value, expectedVersion,
        ) == 1

    fun insertReporter(tx: Tx, reporter: Reporter) {
        tx.update(
            """
            INSERT INTO report_reporters (report_id, reporter_uuid, reporter_name, category, details, reported_at, feedback)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            reporter.reportId.value, reporter.player.uuid, reporter.player.name, reporter.category,
            reporter.details, reporter.reportedAt, reporter.feedback,
        )
    }

    fun reporters(tx: Tx, id: ReportId): List<Reporter> =
        tx.query(
            "SELECT * FROM report_reporters WHERE report_id = ? ORDER BY reported_at, reporter_uuid",
            id.value, mapper = ::reporter,
        )

    fun reporterUuids(tx: Tx, id: ReportId): Set<UUID> =
        tx.query("SELECT reporter_uuid FROM report_reporters WHERE report_id = ?", id.value) { it.uuid("reporter_uuid") }.toSet()

    /** How many open or claimed reports [reporter] is part of: the anti-abuse limit. */
    fun countActiveByReporter(tx: Tx, reporter: UUID): Int =
        tx.long(
            """
            SELECT COUNT(*) FROM report_reporters rr JOIN reports r ON r.id = rr.report_id
            WHERE rr.reporter_uuid = ? AND r.status IN ('OPEN', 'CLAIMED')
            """.trimIndent(),
            reporter,
        ).toInt()

    fun list(tx: Tx, query: ReportQuery): List<Report> {
        val (where, params) = filter(query)
        val order = when (query.order) {
            ReportQuery.Order.PRIORITY -> "reporter_count DESC, created_at ASC, id ASC"
            ReportQuery.Order.RECENT -> "updated_at DESC, id DESC"
        }
        return tx.query(
            "SELECT $COLUMNS FROM reports WHERE $where ORDER BY $order LIMIT ? OFFSET ?",
            *params.toTypedArray(), query.limit, query.offset, mapper = ::report,
        )
    }

    fun count(tx: Tx, query: ReportQuery): Int {
        val (where, params) = filter(query)
        return tx.long("SELECT COUNT(*) FROM reports WHERE $where", *params.toTypedArray()).toInt()
    }

    private fun filter(query: ReportQuery): Pair<String, List<Any?>> {
        require(query.statuses.isNotEmpty()) { "A report query needs at least one status" }
        val clauses = ArrayList<String>()
        val params = ArrayList<Any?>()
        clauses += "status IN (${query.statuses.joinToString { "?" }})"
        params.addAll(query.statuses)
        query.reported?.let {
            clauses += "reported_uuid = ?"
            params += it
        }
        query.claimedBy?.let {
            clauses += "claimed_by_uuid = ?"
            params += it
        }
        query.category?.let {
            clauses += "EXISTS (SELECT 1 FROM report_reporters rr WHERE rr.report_id = reports.id AND rr.category = ?)"
            params += it
        }
        return clauses.joinToString(" AND ") to params
    }

    fun countByStatus(tx: Tx): Map<ReportStatus, Int> {
        val counts = tx.query("SELECT status, COUNT(*) AS n FROM reports GROUP BY status") {
            it.enum<ReportStatus>("status") to it.int("n")
        }.toMap()
        return ReportStatus.entries.associateWith { counts[it] ?: 0 }
    }

    fun countAgainst(tx: Tx, reported: UUID): Int =
        tx.long("SELECT COUNT(*) FROM reports WHERE reported_uuid = ?", reported).toInt()

    fun countMadeBy(tx: Tx, reporter: UUID): Int =
        tx.long("SELECT COUNT(*) FROM report_reporters WHERE reporter_uuid = ?", reporter).toInt()

    // ----- reporter feedback -----

    /** Every reporter of [id] is due a "your report was handled" message. */
    fun queueFeedback(tx: Tx, id: ReportId) {
        tx.update("UPDATE report_reporters SET feedback = ? WHERE report_id = ?", FeedbackState.PENDING, id.value)
    }

    /** A reopened report's reporters are no longer due a message (they get one when it closes again). */
    fun cancelFeedback(tx: Tx, id: ReportId) {
        tx.update(
            "UPDATE report_reporters SET feedback = ? WHERE report_id = ? AND feedback = ?",
            FeedbackState.NONE, id.value, FeedbackState.PENDING,
        )
    }

    fun pendingFeedback(tx: Tx, reporter: UUID): List<ReporterFeedback> =
        tx.query(
            """
            SELECT ${COLUMNS.split(", ").joinToString { "r.$it" }}, rr.report_id AS rr_report_id, rr.reporter_uuid,
                   rr.reporter_name, rr.category AS rr_category, rr.details, rr.reported_at, rr.feedback
            FROM report_reporters rr JOIN reports r ON r.id = rr.report_id
            WHERE rr.reporter_uuid = ? AND rr.feedback = ? AND r.status IN ('RESOLVED', 'DISMISSED')
            ORDER BY r.closed_at
            """.trimIndent(),
            reporter, FeedbackState.PENDING,
        ) { row -> ReporterFeedback(report(row), reporter(row, categoryColumn = "rr_category", reportColumn = "rr_report_id")) }

    /** @return false when it was already delivered (by another join on a shared database, say). */
    fun markFeedbackDelivered(tx: Tx, id: ReportId, reporter: UUID): Boolean =
        tx.update(
            "UPDATE report_reporters SET feedback = ? WHERE report_id = ? AND reporter_uuid = ? AND feedback = ?",
            FeedbackState.DELIVERED, id.value, reporter, FeedbackState.PENDING,
        ) == 1

    // ----- Discord sync -----

    fun setDiscord(tx: Tx, id: ReportId, ref: DiscordRef) {
        tx.update(
            "UPDATE reports SET discord_thread_id = ?, discord_message_id = ? WHERE id = ?",
            ref.threadId, ref.messageId, id.value,
        )
    }

    /** Clears the dirty flag only if nothing changed since the version that was synced. */
    fun markDiscordSynced(tx: Tx, id: ReportId, syncedVersion: Int): Boolean =
        tx.update("UPDATE reports SET discord_dirty = 0 WHERE id = ? AND version = ?", id.value, syncedVersion) == 1

    fun markDiscordDirty(tx: Tx, id: ReportId) {
        tx.update("UPDATE reports SET discord_dirty = 1 WHERE id = ?", id.value)
    }

    fun dirty(tx: Tx, limit: Int): List<Report> =
        tx.query("SELECT $COLUMNS FROM reports WHERE discord_dirty = 1 ORDER BY id LIMIT ?", limit, mapper = ::report)

    fun findByThread(tx: Tx, threadId: Long): Report? =
        tx.queryOne("SELECT $COLUMNS FROM reports WHERE discord_thread_id = ?", threadId, mapper = ::report)

    companion object {
        const val COLUMNS = "id, status, category, reported_uuid, reported_name, server, world, x, y, z, " +
            "reporter_count, created_at, last_reported_at, updated_at, claimed_by_uuid, claimed_by_discord, " +
            "claimed_by_name, claimed_at, outcome, closed_by_uuid, closed_by_discord, closed_by_name, closed_at, " +
            "discord_thread_id, discord_message_id, discord_dirty, version"

        fun report(row: Row): Report {
            val world = row.stringOrNull("world")
            val location = if (world != null) {
                BlockPosition(world, row.int("x"), row.int("y"), row.int("z"))
            } else {
                null
            }
            val claim = row.instantOrNull("claimed_at")?.let { at ->
                Claim(staff(row, "claimed_by"), at)
            }
            val closure = row.instantOrNull("closed_at")?.let { at ->
                val by = if (row.stringOrNull("closed_by_uuid") != null || row.longOrNull("closed_by_discord") != null) {
                    staff(row, "closed_by")
                } else {
                    null
                }
                Closure(row.enum<Outcome>("outcome"), by, row.string("closed_by_name"), at)
            }
            val thread = row.longOrNull("discord_thread_id")
            val message = row.longOrNull("discord_message_id")
            return Report(
                id = ReportId(row.long("id")),
                status = row.enum("status"),
                category = row.string("category"),
                reported = PlayerRef(row.uuid("reported_uuid"), row.string("reported_name")),
                server = row.string("server"),
                location = location,
                reporterCount = row.int("reporter_count"),
                createdAt = row.instant("created_at"),
                lastReportedAt = row.instant("last_reported_at"),
                updatedAt = row.instant("updated_at"),
                claim = claim,
                closure = closure,
                discord = if (thread != null && message != null) DiscordRef(thread, message) else null,
                discordDirty = row.bool("discord_dirty"),
                version = row.int("version"),
            )
        }

        private fun staff(row: Row, prefix: String) = StaffRef(
            name = row.string("${prefix}_name"),
            minecraft = row.uuidOrNull("${prefix}_uuid"),
            discord = row.longOrNull("${prefix}_discord"),
        )

        fun reporter(row: Row, categoryColumn: String = "category", reportColumn: String = "report_id") = Reporter(
            reportId = ReportId(row.long(reportColumn)),
            player = PlayerRef(row.uuid("reporter_uuid"), row.string("reporter_name")),
            category = row.string(categoryColumn),
            details = row.stringOrNull("details"),
            reportedAt = row.instant("reported_at"),
            feedback = row.enum("feedback"),
        )
    }
}
