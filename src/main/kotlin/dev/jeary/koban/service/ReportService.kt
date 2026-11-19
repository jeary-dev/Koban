package dev.jeary.koban.service

import dev.jeary.koban.domain.abuse.AntiAbuseSettings
import dev.jeary.koban.domain.abuse.GateResult
import dev.jeary.koban.domain.abuse.ReportGate
import dev.jeary.koban.domain.actor.Actor
import dev.jeary.koban.domain.audit.AuditAction
import dev.jeary.koban.domain.audit.AuditEntry
import dev.jeary.koban.domain.evidence.EvidenceSnapshot
import dev.jeary.koban.domain.report.Change
import dev.jeary.koban.domain.report.DiscordRef
import dev.jeary.koban.domain.report.FeedbackState
import dev.jeary.koban.domain.report.MergePolicy
import dev.jeary.koban.domain.report.Note
import dev.jeary.koban.domain.report.Outcome
import dev.jeary.koban.domain.report.Rejection
import dev.jeary.koban.domain.report.Report
import dev.jeary.koban.domain.report.ReportCommand
import dev.jeary.koban.domain.report.ReportId
import dev.jeary.koban.domain.report.ReportLifecycle
import dev.jeary.koban.domain.report.ReportStatus
import dev.jeary.koban.domain.report.ReportText
import dev.jeary.koban.domain.report.Reporter
import dev.jeary.koban.domain.report.Transition
import dev.jeary.koban.storage.Database
import dev.jeary.koban.storage.TransactionAborted
import dev.jeary.koban.storage.Tx
import dev.jeary.koban.storage.repo.AuditRepository
import dev.jeary.koban.storage.repo.EvidenceRepository
import dev.jeary.koban.storage.repo.NewReport
import dev.jeary.koban.storage.repo.NoteRepository
import dev.jeary.koban.storage.repo.ReportQuery
import dev.jeary.koban.storage.repo.ReportRepository
import dev.jeary.koban.storage.repo.ReporterFeedback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Level
import java.util.logging.Logger

/** The parts of config.yml that report handling reads. Swapped as a whole on reload. */
data class ReportSettings(
    val serverName: String,
    val antiAbuse: AntiAbuseSettings,
    val mergeWindow: Duration,
)

sealed interface CreateResult {
    data class Created(val report: Report) : CreateResult
    data class Merged(val report: Report) : CreateResult
    data class AlreadyReported(val report: Report) : CreateResult
    data class Denied(val reason: GateResult) : CreateResult
    data object Cancelled : CreateResult
}

sealed interface TransitionResult {
    data class Done(val report: Report, val change: Change) : TransitionResult
    data class Rejected(val reason: Rejection) : TransitionResult
    data object NotFound : TransitionResult
    data object Cancelled : TransitionResult

    /** The report kept changing under us (extremely unlikely); the caller can simply try again. */
    data object Busy : TransitionResult
}

sealed interface NoteResult {
    data class Added(val note: Note) : NoteResult
    data object Empty : NoteResult
    data object NotFound : NoteResult
    data object Duplicate : NoteResult
}

/** Everything known about one report, for the report view and the Discord embed. */
data class ReportDetails(
    val report: Report,
    val reporters: List<Reporter>,
    val evidence: List<EvidenceSnapshot>,
    val notes: List<Note>,
    val audit: List<AuditEntry>,
)

/**
 * Files, merges and moves reports through their lifecycle. All storage is suspending and runs
 * off the main thread. Bukkit and Discord are reached only through [ReportVeto] and
 * [ReportListener], so this class is tested against a real database with no server.
 *
 * Concurrency:
 * - Reports against the same player are filed one at a time ([playerLocks]), so two people
 *   reporting at the same moment merge into one report instead of creating two.
 * - Every change is written with an optimistic lock on [Report.version]. If two staff act on the
 *   same report at once, one write wins and the other re-runs its command against the new state,
 *   where the state machine refuses it with a reason ("already claimed by Steve").
 */
class ReportService(
    private val database: Database,
    private val clock: Clock,
    private val gate: ReportGate,
    private val settings: () -> ReportSettings,
    private val logger: Logger,
    private val veto: ReportVeto = ReportVeto.AllowAll,
) {
    private val reports = ReportRepository()
    private val evidence = EvidenceRepository()
    private val notes = NoteRepository()
    private val audit = AuditRepository()

    private val listeners = CopyOnWriteArrayList<ReportListener>()
    private val playerLocks = Array(LOCK_STRIPES) { Mutex() }

    fun addListener(listener: ReportListener) {
        listeners += listener
    }

    fun removeListener(listener: ReportListener) {
        listeners -= listener
    }

    // ------------------------------------------------------------------ filing

    suspend fun create(draft: ReportDraft): CreateResult {
        val config = settings()
        val reporterId = draft.reporter.uuid
        gate.precheck(reporterId, draft.reported.uuid, config.antiAbuse, draft.bypassCooldown)
            .takeUnless { it == GateResult.Allowed }
            ?.let { return CreateResult.Denied(it) }
        if (gate.limitApplies(config.antiAbuse, draft.bypassLimit)) {
            val open = database.read { reports.countActiveByReporter(it, reporterId) }
            gate.checkOpenLimit(open, config.antiAbuse)
                .takeUnless { it == GateResult.Allowed }
                ?.let { return CreateResult.Denied(it) }
        }

        val details = ReportText.clean(draft.details, ReportText.MAX_DETAILS)
        val merge = MergePolicy(config.mergeWindow)

        val filed: FiledReport = lockFor(draft.reported.uuid).withLock {
            val now = clock.instant()
            val (candidates, reportersByReport) = database.read { tx ->
                val active = reports.findActiveAgainst(tx, draft.reported.uuid)
                active to active.associate { it.id to reports.reporterUuids(tx, it.id) }
            }
            when (val decision = merge.decide(candidates, reporterId, now) { reportersByReport[it.id].orEmpty() }) {
                is MergePolicy.Decision.AlreadyReported -> return CreateResult.AlreadyReported(decision.report)
                is MergePolicy.Decision.MergeInto -> {
                    if (!veto.allowCreate(draft, decision.report)) return CreateResult.Cancelled
                    mergeInto(decision.report.id, draft, details, config) ?: run {
                        // It was closed while we looked: file a fresh report instead.
                        insertNew(draft, details, config)
                    }
                }
                MergePolicy.Decision.CreateNew -> {
                    if (!veto.allowCreate(draft, null)) return CreateResult.Cancelled
                    insertNew(draft, details, config)
                }
            }
        }

        gate.recordAccepted(reporterId)
        return when (filed) {
            is FiledReport.New -> {
                notify(ReportChange.Created(filed.report, filed.reporter))
                CreateResult.Created(filed.report)
            }
            is FiledReport.Joined -> {
                notify(ReportChange.Merged(filed.report, filed.reporter))
                CreateResult.Merged(filed.report)
            }
        }
    }

    /** Internal result of filing, before listeners run. */
    private sealed interface FiledReport {
        val report: Report
        val reporter: Reporter

        data class New(override val report: Report, override val reporter: Reporter) : FiledReport
        data class Joined(override val report: Report, override val reporter: Reporter) : FiledReport
    }

    private suspend fun insertNew(draft: ReportDraft, details: String?, config: ReportSettings): FiledReport.New =
        database.write { tx ->
            val now = clock.instant()
            val id = reports.insert(
                tx,
                NewReport(draft.category, draft.reported, config.serverName, draft.location, now),
            )
            val reporter = Reporter(id, draft.reporter, draft.category, details, now, FeedbackState.NONE)
            reports.insertReporter(tx, reporter)
            draft.evidence?.let { evidence.insert(tx, id, draft.reporter.uuid, it) }
            audit.insert(tx, id, AuditAction.REPORT_CREATED, reporterActor(draft), mapOf("category" to draft.category), now)
            val stored = reports.find(tx, id) ?: error("Report $id vanished inside its own transaction")
            FiledReport.New(stored, reporter)
        }

    /** @return null when the target is no longer open (closed between the read and this write). */
    private suspend fun mergeInto(id: ReportId, draft: ReportDraft, details: String?, config: ReportSettings): FiledReport.Joined? {
        repeat(MAX_ATTEMPTS) {
            val joined = try {
                database.write { tx ->
                    val now = clock.instant()
                    val current = reports.find(tx, id)
                    if (current == null || !current.status.isActive) return@write null
                    val updated = current.copy(
                        reporterCount = current.reporterCount + 1,
                        lastReportedAt = now,
                        updatedAt = now,
                        discordDirty = true,
                        version = current.version + 1,
                    )
                    if (!reports.update(tx, updated, current.version)) throw VersionConflict()
                    val reporter = Reporter(id, draft.reporter, draft.category, details, now, FeedbackState.NONE)
                    reports.insertReporter(tx, reporter)
                    draft.evidence?.let { evidence.insert(tx, id, draft.reporter.uuid, it) }
                    audit.insert(
                        tx, id, AuditAction.REPORT_MERGED, reporterActor(draft),
                        mapOf("category" to draft.category, "reporters" to updated.reporterCount.toString(), "server" to config.serverName),
                        now,
                    )
                    FiledReport.Joined(updated, reporter)
                }
            } catch (_: VersionConflict) {
                return@repeat
            }
            return joined
        }
        error("Report $id kept changing while a report was merged into it")
    }

    private fun reporterActor(draft: ReportDraft) = Actor.player(draft.reporter.uuid, draft.reporter.name)

    // ------------------------------------------------------------------ lifecycle

    suspend fun claim(id: ReportId, actor: Actor, override: Boolean = false) =
        transition(id, ReportCommand.Claim(actor, override))

    suspend fun unclaim(id: ReportId, actor: Actor, override: Boolean = false) =
        transition(id, ReportCommand.Unclaim(actor, override))

    suspend fun resolve(id: ReportId, actor: Actor, outcome: Outcome, note: String? = null, override: Boolean = false) =
        transition(id, ReportCommand.Resolve(actor, outcome, override), note)

    suspend fun dismiss(id: ReportId, actor: Actor, note: String? = null, override: Boolean = false) =
        transition(id, ReportCommand.Dismiss(actor, override), note)

    suspend fun reopen(id: ReportId, actor: Actor) = transition(id, ReportCommand.Reopen(actor))

    /**
     * Runs [command] against the latest state, retrying on a lost optimistic-lock race. A note
     * (the resolve or dismiss reason) is stored in the same transaction as the change.
     */
    suspend fun transition(id: ReportId, command: ReportCommand, note: String? = null): TransitionResult {
        val noteBody = ReportText.clean(note, ReportText.MAX_NOTE)
        repeat(MAX_ATTEMPTS) {
            val current = database.read { reports.find(it, id) } ?: return TransitionResult.NotFound
            val transition = when (val t = ReportLifecycle.apply(current, command, clock.instant())) {
                is Transition.Rejected -> return TransitionResult.Rejected(t.reason)
                is Transition.Applied -> t
            }
            if (!veto.allowTransition(transition, command.actor)) return TransitionResult.Cancelled

            val written = database.write { tx ->
                if (!reports.update(tx, transition.after, current.version)) return@write null
                when (transition.change) {
                    Change.RESOLVED, Change.DISMISSED -> reports.queueFeedback(tx, id)
                    Change.REOPENED -> reports.cancelFeedback(tx, id)
                    Change.CLAIMED, Change.TAKEN_OVER, Change.UNCLAIMED -> Unit
                }
                val savedNote = noteBody?.let { body -> insertNote(tx, id, command.actor, body, null) }
                audit.insert(tx, id, auditAction(transition.change), command.actor, transitionDetail(transition, noteBody), transition.after.updatedAt)
                Written(savedNote)
            } ?: return@repeat // lost the race: re-read and decide again

            notify(ReportChange.Transitioned(transition.after, transition, command.actor, written.note))
            return TransitionResult.Done(transition.after, transition.change)
        }
        logger.warning("Report $id changed $MAX_ATTEMPTS times while ${command::class.simpleName} was being applied; giving up.")
        return TransitionResult.Busy
    }

    private fun auditAction(change: Change) = when (change) {
        Change.CLAIMED -> AuditAction.CLAIMED
        Change.TAKEN_OVER -> AuditAction.TAKEN_OVER
        Change.UNCLAIMED -> AuditAction.UNCLAIMED
        Change.RESOLVED -> AuditAction.RESOLVED
        Change.DISMISSED -> AuditAction.DISMISSED
        Change.REOPENED -> AuditAction.REOPENED
    }

    private fun transitionDetail(t: Transition.Applied, note: String?): Map<String, String> = buildMap {
        put("from", t.before.status.name)
        put("to", t.after.status.name)
        t.after.closure?.let { put("outcome", it.outcome.name) }
        t.before.claim?.takeIf { t.change == Change.TAKEN_OVER }?.let { put("previous_claim", it.by.name) }
        note?.let { put("note", it.take(AUDIT_NOTE_EXCERPT)) }
    }

    // ------------------------------------------------------------------ notes

    suspend fun addNote(id: ReportId, actor: Actor, body: String, discordMessageId: Long? = null): NoteResult {
        val text = ReportText.clean(body, ReportText.MAX_NOTE) ?: return NoteResult.Empty
        val (report, note) = database.write { tx ->
            val report = reports.find(tx, id) ?: return@write null
            val note = insertNote(tx, id, actor, text, discordMessageId) ?: return@write report to null
            audit.insert(tx, id, AuditAction.NOTE_ADDED, actor, mapOf("note" to text.take(AUDIT_NOTE_EXCERPT)), note.createdAt)
            report to note
        } ?: return NoteResult.NotFound
        if (note == null) return NoteResult.Duplicate
        notify(ReportChange.NoteAdded(report, note))
        return NoteResult.Added(note)
    }

    private fun insertNote(tx: Tx, id: ReportId, actor: Actor, body: String, discordMessageId: Long?): Note? {
        val draft = Note(0, id, actor.source, actor.staff, actor.displayName, body, discordMessageId, clock.instant())
        val noteId = notes.insert(tx, draft) ?: return null
        return draft.copy(id = noteId)
    }

    // ------------------------------------------------------------------ reading

    suspend fun find(id: ReportId): Report? = database.read { reports.find(it, id) }

    suspend fun findByThread(threadId: Long): Report? = database.read { reports.findByThread(it, threadId) }

    suspend fun details(id: ReportId): ReportDetails? = database.read { tx ->
        val report = reports.find(tx, id) ?: return@read null
        ReportDetails(
            report = report,
            reporters = reports.reporters(tx, id),
            evidence = evidence.forReport(tx, id),
            notes = notes.forReport(tx, id),
            audit = audit.forReport(tx, id),
        )
    }

    suspend fun list(query: ReportQuery): List<Report> = database.read { reports.list(it, query) }

    suspend fun count(query: ReportQuery): Int = database.read { reports.count(it, query) }

    suspend fun history(reported: UUID, limit: Int = 45, offset: Int = 0): List<Report> =
        list(ReportQuery(ReportStatus.entries.toSet(), reported = reported, order = ReportQuery.Order.RECENT, limit = limit, offset = offset))

    suspend fun countByStatus(): Map<ReportStatus, Int> = database.read { reports.countByStatus(it) }

    suspend fun countAgainst(reported: UUID): Int = database.read { reports.countAgainst(it, reported) }

    suspend fun countMadeBy(reporter: UUID): Int = database.read { reports.countMadeBy(it, reporter) }

    suspend fun reporters(id: ReportId): List<Reporter> = database.read { reports.reporters(it, id) }

    // ------------------------------------------------------------------ feedback

    /**
     * Claims every message [reporter] is owed and marks it delivered, in one transaction, so a
     * message is handed out exactly once even if two joins race.
     */
    suspend fun takePendingFeedback(reporter: UUID): List<ReporterFeedback> = database.write { tx ->
        reports.pendingFeedback(tx, reporter).filter { reports.markFeedbackDelivered(tx, it.report.id, reporter) }
    }

    // ------------------------------------------------------------------ Discord bookkeeping

    suspend fun setDiscord(id: ReportId, ref: DiscordRef) =
        database.write { reports.setDiscord(it, id, ref) }

    suspend fun markDiscordSynced(id: ReportId, version: Int): Boolean =
        database.write { reports.markDiscordSynced(it, id, version) }

    suspend fun markDiscordDirty(id: ReportId) = database.write { reports.markDiscordDirty(it, id) }

    suspend fun dirtyReports(limit: Int = 50): List<Report> = database.read { reports.dirty(it, limit) }

    // ------------------------------------------------------------------ internals

    private suspend fun notify(change: ReportChange) {
        for (listener in listeners) {
            try {
                listener.onChange(change)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.log(Level.WARNING, "A report listener failed on ${change::class.simpleName} for ${change.report.id}", e)
            }
        }
    }

    private fun lockFor(player: UUID): Mutex = playerLocks[Math.floorMod(player.hashCode(), LOCK_STRIPES)]

    /** What a transition's transaction stored besides the report itself. */
    private class Written(val note: Note?)

    private class VersionConflict : TransactionAborted("The report changed during the write")

    private companion object {
        const val MAX_ATTEMPTS = 5
        const val LOCK_STRIPES = 64
        const val AUDIT_NOTE_EXCERPT = 200
    }
}
