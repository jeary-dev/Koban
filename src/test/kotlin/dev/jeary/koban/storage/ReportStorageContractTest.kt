package dev.jeary.koban.storage

import dev.jeary.koban.domain.abuse.AntiAbuseSettings
import dev.jeary.koban.domain.abuse.CooldownTracker
import dev.jeary.koban.domain.abuse.GateResult
import dev.jeary.koban.domain.abuse.ReportGate
import dev.jeary.koban.domain.actor.Actor
import dev.jeary.koban.domain.audit.AuditAction
import dev.jeary.koban.domain.evidence.ChatLine
import dev.jeary.koban.domain.evidence.EvidenceSnapshot
import dev.jeary.koban.domain.evidence.PlayerState
import dev.jeary.koban.domain.link.LinkCodes
import dev.jeary.koban.domain.report.BlockPosition
import dev.jeary.koban.domain.report.Change
import dev.jeary.koban.domain.report.DiscordRef
import dev.jeary.koban.domain.report.FeedbackState
import dev.jeary.koban.domain.report.Outcome
import dev.jeary.koban.domain.report.PlayerRef
import dev.jeary.koban.domain.report.Rejection
import dev.jeary.koban.domain.report.Report
import dev.jeary.koban.domain.report.ReportId
import dev.jeary.koban.domain.report.ReportStatus
import dev.jeary.koban.domain.report.Transition
import dev.jeary.koban.domain.report.categoryCounts
import dev.jeary.koban.service.CreateResult
import dev.jeary.koban.service.LinkResult
import dev.jeary.koban.service.LinkService
import dev.jeary.koban.service.NoteResult
import dev.jeary.koban.service.PlayerDirectory
import dev.jeary.koban.service.ReportChange
import dev.jeary.koban.service.ReportDraft
import dev.jeary.koban.service.ReportService
import dev.jeary.koban.service.ReportSettings
import dev.jeary.koban.service.ReportVeto
import dev.jeary.koban.service.TransitionResult
import dev.jeary.koban.storage.migration.Migration
import dev.jeary.koban.storage.migration.MigrationException
import dev.jeary.koban.storage.migration.Migrator
import dev.jeary.koban.storage.repo.ReportQuery
import dev.jeary.koban.support.MutableClock
import dev.jeary.koban.support.Players
import dev.jeary.koban.support.present
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Report storage and [ReportService] against a real database. Runs once per dialect; the MySQL
 * run needs Docker and is skipped (with a message) without it.
 */
abstract class ReportStorageContractTest(private val dialect: Dialect) {

    // Quiet: one test makes a listener throw on purpose, which the service logs as a warning.
    private val logger = Logger.getLogger("koban-test.storage").apply { level = Level.SEVERE }
    private lateinit var database: Database
    private lateinit var clock: MutableClock
    private var settings = ReportSettings(
        serverName = "survival",
        antiAbuse = AntiAbuseSettings(cooldown = Duration.ZERO, maxOpenPerReporter = 0),
        mergeWindow = Duration.ofMinutes(30),
    )
    private var veto: ReportVeto = ReportVeto.AllowAll
    private lateinit var service: ReportService
    private val changes = CopyOnWriteArrayList<ReportChange>()

    private val mod1 = Players.staff(Players.mod1)
    private val mod2 = Players.staff(Players.mod2)

    @BeforeEach
    fun setUp() {
        database = TestDatabases.create(dialect, poolSize = 8)
        clock = MutableClock()
        service = newService()
    }

    private fun newService() = ReportService(
        database, clock, ReportGate(CooldownTracker(clock)), { settings }, logger,
        object : ReportVeto {
            override suspend fun allowCreate(draft: ReportDraft, mergeInto: Report?) = veto.allowCreate(draft, mergeInto)
            override suspend fun allowTransition(transition: Transition.Applied, actor: Actor) = veto.allowTransition(transition, actor)
        },
    ).also { it.addListener { change -> changes += change } }

    @AfterEach
    fun tearDown() = database.close()

    private fun evidence(reporter: PlayerRef = Players.steve) = EvidenceSnapshot(
        capturedAt = clock.millis(),
        server = "survival",
        tps = 19.8,
        reported = PlayerState(Players.notch.uuid.toString(), "Notch", true, "world", 1.5, 64.0, -2.5, "SURVIVAL", 20.0, 20.0, 42, "fabric"),
        reporter = PlayerState(reporter.uuid.toString(), reporter.name, true),
        chat = listOf(ChatLine(clock.millis(), Players.notch.uuid.toString(), "Notch", "ez")),
    )

    private fun draft(reporter: PlayerRef, reported: PlayerRef = Players.notch, category: String = "cheating", details: String? = null) =
        ReportDraft(reporter, reported, category, details, BlockPosition("world", 1, 64, -3), evidence(reporter))

    private fun file(reporter: PlayerRef, reported: PlayerRef = Players.notch, category: String = "cheating"): Report = runBlocking {
        when (val result = service.create(draft(reporter, reported, category))) {
            is CreateResult.Created -> result.report
            is CreateResult.Merged -> result.report
            else -> throw AssertionError("expected the report to be filed, got $result")
        }
    }

    @Nested
    inner class Migrations {
        @Test
        fun `migrating again changes nothing`() {
            assertEquals(emptyList<Migration>(), Migrator(database, logger).migrate())
        }

        @Test
        fun `an edited migration is refused`() {
            val tampered = Migrator(database, logger, loadResource = { "-- edited\nSELECT 1;" })
            assertThrows(MigrationException::class.java) { tampered.migrate() }
        }
    }

    @Nested
    inner class Filing {
        @Test
        fun `a report is stored with its reporter, details and evidence`() = runBlocking<Unit> {
            val result = service.create(draft(Players.steve, details = "  flying\nover spawn "))
            val report = assertInstanceOf(CreateResult.Created::class.java, result).report
            assertEquals(ReportStatus.OPEN, report.status)
            assertEquals("survival", report.server)
            assertEquals(BlockPosition("world", 1, 64, -3), report.location)
            assertTrue(report.discordDirty)

            val details = present(service.details(report.id))
            assertEquals("flying over spawn", details.reporters.single().details)
            assertEquals(evidence(), details.evidence.single())
            assertEquals(listOf(AuditAction.REPORT_CREATED), details.audit.map { it.action })
            assertInstanceOf(ReportChange.Created::class.java, changes.single())
        }

        @Test
        fun `a second reporter joins the report, keeping their own category`() = runBlocking<Unit> {
            val first = file(Players.steve, category = "cheating")
            clock.advance(Duration.ofMinutes(10))
            val merged = assertInstanceOf(CreateResult.Merged::class.java, service.create(draft(Players.alex, category = "harassment"))).report
            assertEquals(first.id, merged.id)
            assertEquals(2, merged.reporterCount)
            assertEquals("cheating", merged.category)
            assertEquals(clock.instant(), merged.lastReportedAt)

            val details = present(service.details(first.id))
            assertEquals(listOf("cheating" to 1, "harassment" to 1), categoryCounts(details.reporters))
            assertEquals(2, details.evidence.size)
            assertEquals(listOf(AuditAction.REPORT_CREATED, AuditAction.REPORT_MERGED), details.audit.map { it.action })
        }

        @Test
        fun `the same reporter can't report the same player twice`() = runBlocking<Unit> {
            val first = file(Players.steve)
            assertEquals(CreateResult.AlreadyReported(service.find(first.id) ?: first), service.create(draft(Players.steve)))
            assertEquals(1, service.find(first.id)?.reporterCount)
        }

        @Test
        fun `after the merge window a new report starts`() = runBlocking<Unit> {
            val first = file(Players.steve)
            clock.advance(Duration.ofMinutes(31))
            val second = assertInstanceOf(CreateResult.Created::class.java, service.create(draft(Players.alex))).report
            assertTrue(second.id != first.id)
        }

        @Test
        fun `a closed report is never merged into`() = runBlocking<Unit> {
            val first = file(Players.steve)
            service.dismiss(first.id, mod1)
            val second = assertInstanceOf(CreateResult.Created::class.java, service.create(draft(Players.alex))).report
            assertTrue(second.id != first.id)
        }

        @Test
        fun `self-reports, cooldowns and the open limit are enforced`() = runBlocking<Unit> {
            settings = settings.copy(antiAbuse = AntiAbuseSettings(cooldown = Duration.ofSeconds(60), maxOpenPerReporter = 2))
            assertEquals(CreateResult.Denied(GateResult.SelfReport), service.create(draft(Players.steve, reported = Players.steve)))

            file(Players.steve, reported = Players.notch)
            assertInstanceOf(GateResult.OnCooldown::class.java, (service.create(draft(Players.steve, reported = Players.alex)) as CreateResult.Denied).reason)

            clock.advance(Duration.ofSeconds(61))
            file(Players.steve, reported = Players.alex)
            clock.advance(Duration.ofSeconds(61))
            assertEquals(CreateResult.Denied(GateResult.TooManyOpen(2)), service.create(draft(Players.steve, reported = Players.herobrine)))

            val bypass = draft(Players.steve, reported = Players.herobrine).copy(bypassLimit = true)
            assertInstanceOf(CreateResult.Created::class.java, service.create(bypass))
        }

        @Test
        fun `a cancelled report stores nothing and starts no cooldown`() = runBlocking<Unit> {
            settings = settings.copy(antiAbuse = AntiAbuseSettings(cooldown = Duration.ofSeconds(60), maxOpenPerReporter = 0))
            veto = object : ReportVeto by ReportVeto.AllowAll {
                override suspend fun allowCreate(draft: ReportDraft, mergeInto: Report?) = false
            }
            assertEquals(CreateResult.Cancelled, service.create(draft(Players.steve)))
            assertEquals(0, service.count(ReportQuery(ReportStatus.entries.toSet())))
            veto = ReportVeto.AllowAll
            assertInstanceOf(CreateResult.Created::class.java, service.create(draft(Players.steve)))
        }

        @Test
        fun `twenty players reporting at the same moment make one report`() = runBlocking<Unit>(Dispatchers.Default) {
            val start = CompletableDeferred<Unit>()
            val reporters = (1..20).map { Players.ref("Reporter$it") }
            val results = reporters.map { reporter ->
                async {
                    start.await()
                    service.create(draft(reporter))
                }
            }
            start.complete(Unit)
            val all = results.awaitAll()
            assertEquals(1, all.count { it is CreateResult.Created })
            assertEquals(19, all.count { it is CreateResult.Merged })
            val reports = service.list(ReportQuery(ReportStatus.entries.toSet()))
            assertEquals(1, reports.size)
            assertEquals(20, reports.single().reporterCount)
            assertEquals(20, service.reporters(reports.single().id).size)
        }
    }

    @Nested
    inner class Lifecycle {
        @Test
        fun `two staff claiming at once - exactly one wins, every time`() = runBlocking<Unit>(Dispatchers.Default) {
            repeat(15) { round ->
                val report = file(Players.steve, reported = Players.ref("Target$round"))
                val start = CompletableDeferred<Unit>()
                val attempts = listOf(mod1, mod2).map { staff ->
                    async {
                        start.await()
                        staff to service.claim(report.id, staff)
                    }
                }
                start.complete(Unit)
                val results = attempts.awaitAll()
                val winners = results.filter { it.second is TransitionResult.Done }
                val losers = results.filter { it.second is TransitionResult.Rejected }
                assertEquals(1, winners.size, "round $round: $results")
                assertEquals(1, losers.size, "round $round: $results")
                val winner = winners.single().first
                assertEquals(
                    TransitionResult.Rejected(Rejection.ClaimedByOther(winner.displayName)),
                    losers.single().second,
                )
                val stored = present(service.find(report.id))
                assertEquals(winner.displayName, stored.claim?.by?.name)
                assertEquals(1, service.details(report.id)?.audit?.count { it.action == AuditAction.CLAIMED })
            }
        }

        @Test
        fun `a claim racing a dismissal never leaves a claimed closed report`() = runBlocking<Unit>(Dispatchers.Default) {
            repeat(15) { round ->
                val report = file(Players.steve, reported = Players.ref("Race$round"))
                val start = CompletableDeferred<Unit>()
                val claim = async { start.await(); service.claim(report.id, mod1) }
                val dismiss = async { start.await(); service.dismiss(report.id, mod2) }
                start.complete(Unit)
                val results = listOf(claim.await(), dismiss.await())
                // One wins. The other is refused with the reason that matches the winner.
                assertEquals(1, results.count { it is TransitionResult.Done }, "round $round: $results")
                val stored = present(service.find(report.id))
                if (results[0] is TransitionResult.Done) {
                    assertEquals(TransitionResult.Rejected(Rejection.ClaimedByOther("ModOne")), results[1])
                    assertEquals(ReportStatus.CLAIMED, stored.status)
                    assertEquals("ModOne", stored.claim?.by?.name)
                } else {
                    assertEquals(TransitionResult.Rejected(Rejection.AlreadyClosed), results[0])
                    assertEquals(ReportStatus.DISMISSED, stored.status)
                    assertNull(stored.claim)
                }
            }
        }

        @Test
        fun `resolving queues feedback for every reporter, delivered exactly once`() = runBlocking<Unit> {
            val report = file(Players.steve)
            file(Players.alex)
            val result = service.resolve(report.id, mod1, Outcome.ACTION_TAKEN, note = "Banned for 7 days")
            assertEquals(Change.RESOLVED, assertInstanceOf(TransitionResult.Done::class.java, result).change)

            val steve = service.takePendingFeedback(Players.steve.uuid)
            assertEquals(Outcome.ACTION_TAKEN, steve.single().report.closure?.outcome)
            assertEquals(emptyList<Any>(), service.takePendingFeedback(Players.steve.uuid))
            assertEquals(1, service.takePendingFeedback(Players.alex.uuid).size)

            val details = present(service.details(report.id))
            assertEquals("Banned for 7 days", details.notes.single().body)
            assertEquals(FeedbackState.DELIVERED, details.reporters.first().feedback)
            val audit = details.audit.last()
            assertEquals(AuditAction.RESOLVED, audit.action)
            assertEquals("ACTION_TAKEN", audit.detail["outcome"])
            assertEquals(Players.mod1.uuid, audit.actor.staff?.minecraft)
        }

        @Test
        fun `reopening withdraws undelivered feedback`() = runBlocking<Unit> {
            val report = file(Players.steve)
            service.dismiss(report.id, mod1)
            service.reopen(report.id, mod2)
            assertEquals(emptyList<Any>(), service.takePendingFeedback(Players.steve.uuid))
            service.resolve(report.id, mod2, Outcome.NO_ACTION)
            assertEquals(Outcome.NO_ACTION, service.takePendingFeedback(Players.steve.uuid).single().report.closure?.outcome)
        }

        @Test
        fun `rejections and cancellations change nothing`() = runBlocking<Unit> {
            val report = file(Players.steve)
            service.claim(report.id, mod1)
            assertEquals(TransitionResult.Rejected(Rejection.ClaimedByOther("ModOne")), service.resolve(report.id, mod2, Outcome.NO_ACTION))
            veto = object : ReportVeto by ReportVeto.AllowAll {
                override suspend fun allowTransition(transition: Transition.Applied, actor: Actor) = false
            }
            assertEquals(TransitionResult.Cancelled, service.unclaim(report.id, mod1))
            assertEquals(ReportStatus.CLAIMED, service.find(report.id)?.status)
            assertEquals(TransitionResult.NotFound, service.claim(ReportId(9_999), mod1))
        }

        @Test
        fun `listeners hear every stored change, and one failing listener doesn't stop the rest`() = runBlocking<Unit> {
            service.addListener { error("boom") }
            val heard = CopyOnWriteArrayList<String>()
            service.addListener { heard += it::class.simpleName.orEmpty() }
            val report = file(Players.steve)
            file(Players.alex)
            service.claim(report.id, mod1)
            service.addNote(report.id, mod1, "looking now")
            assertEquals(listOf("Created", "Merged", "Transitioned", "NoteAdded"), heard)
        }
    }

    @Nested
    inner class Notes {
        @Test
        fun `notes are cleaned, stored and deduplicated by Discord message`() = runBlocking<Unit> {
            val report = file(Players.steve)
            val note = assertInstanceOf(NoteResult.Added::class.java, service.addNote(report.id, mod1, " checking\nnow ")).note
            assertEquals("checking now", note.body)
            val discord = Actor.discord(77L, "mod.two", Players.mod2.uuid, "ModTwo")
            assertInstanceOf(NoteResult.Added::class.java, service.addNote(report.id, discord, "on it", discordMessageId = 1234L))
            assertEquals(NoteResult.Duplicate, service.addNote(report.id, discord, "on it", discordMessageId = 1234L))
            assertEquals(NoteResult.Empty, service.addNote(report.id, mod1, "   "))
            assertEquals(NoteResult.NotFound, service.addNote(ReportId(9_999), mod1, "x"))

            val notes = present(service.details(report.id)).notes
            assertEquals(listOf("checking now", "on it"), notes.map { it.body })
            assertEquals(77L, notes.last().author?.discord)
            assertEquals(Players.mod2.uuid, notes.last().author?.minecraft)
        }
    }

    @Nested
    inner class Queries {
        @Test
        fun `lists sort by priority and filter by category and player`() = runBlocking<Unit> {
            val single = file(Players.steve, reported = Players.alex, category = "spam")
            clock.advance(Duration.ofMinutes(1))
            val popular = file(Players.steve, reported = Players.notch, category = "cheating")
            file(Players.alex, reported = Players.notch, category = "harassment")
            clock.advance(Duration.ofMinutes(1))
            val newer = file(Players.steve, reported = Players.herobrine, category = "griefing")

            val active = ReportQuery(ReportStatus.ACTIVE)
            assertEquals(listOf(popular.id, single.id, newer.id), service.list(active).map { it.id })
            assertEquals(listOf(popular.id), service.list(active.copy(category = "harassment")).map { it.id })
            assertEquals(listOf(single.id), service.list(active.copy(reported = Players.alex.uuid)).map { it.id })
            assertEquals(3, service.count(active))
            assertEquals(listOf(single.id), service.list(active.copy(limit = 1, offset = 1)).map { it.id })

            service.dismiss(newer.id, mod1)
            assertEquals(2, service.count(active))
            assertEquals(mapOf(ReportStatus.OPEN to 2, ReportStatus.CLAIMED to 0, ReportStatus.RESOLVED to 0, ReportStatus.DISMISSED to 1), service.countByStatus())
            assertEquals(listOf(newer.id), service.history(Players.herobrine.uuid).map { it.id })
            assertEquals(1, service.countAgainst(Players.notch.uuid))
            assertEquals(3, service.countMadeBy(Players.steve.uuid))
        }

        @Test
        fun `Discord sync only clears the dirty flag for the version that was synced`() = runBlocking<Unit> {
            val report = file(Players.steve)
            assertEquals(listOf(report.id), service.dirtyReports().map { it.id })
            service.setDiscord(report.id, DiscordRef(111L, 222L))
            assertEquals(report.id, service.findByThread(111L)?.id)

            service.claim(report.id, mod1) // version moves on while the sync of version 0 is in flight
            assertEquals(false, service.markDiscordSynced(report.id, report.version))
            assertEquals(listOf(report.id), service.dirtyReports().map { it.id })

            val current = present(service.find(report.id))
            assertTrue(service.markDiscordSynced(report.id, current.version))
            assertEquals(emptyList<Report>(), service.dirtyReports())
            assertEquals(DiscordRef(111L, 222L), service.find(report.id)?.discord)
        }
    }

    @Nested
    inner class Accounts {
        @Test
        fun `linking ties both identities, and a relink replaces the old link`() = runBlocking<Unit> {
            val links = LinkService(database, clock, LinkCodes(clock))
            val code = links.issueCode(Players.mod1)
            assertEquals(LinkResult.InvalidCode, links.redeem("ZZZZZZ", 1L, "x"))
            val linked = assertInstanceOf(LinkResult.Linked::class.java, links.redeem(code, 555L, "mod.one")).link
            assertEquals(Players.mod1, linked.minecraft)
            assertEquals(Players.mod1.uuid, links.byDiscord(555L)?.minecraft?.uuid)
            assertEquals(LinkResult.InvalidCode, links.redeem(code, 555L, "mod.one"))

            // The same Discord account linking a different Minecraft account moves the link.
            links.redeem(links.issueCode(Players.mod2), 555L, "mod.one")
            assertNull(links.byMinecraft(Players.mod1.uuid))
            assertEquals(Players.mod2.uuid, links.byDiscord(555L)?.minecraft?.uuid)

            assertTrue(links.unlinkDiscord(555L, Actor.CONSOLE))
            assertNull(links.byDiscord(555L))
        }

        @Test
        fun `the name cache finds players by name and prefix, treating wildcards literally`() = runBlocking<Unit> {
            val players = PlayerDirectory(database, clock)
            players.seen(PlayerRef(Players.notch.uuid, "Notch"))
            clock.advance(Duration.ofSeconds(1))
            players.seen(PlayerRef(Players.steve.uuid, "No_Way"))
            players.seen(PlayerRef(Players.alex.uuid, "Nox"))
            clock.advance(Duration.ofSeconds(1))
            players.seen(PlayerRef(Players.notch.uuid, "NotchRenamed"))

            assertEquals(Players.notch.uuid, players.byName("notchrenamed")?.uuid)
            assertNull(players.byName("Notch"))
            assertEquals(listOf("No_Way"), players.search("no_"))
            assertEquals(emptyList<String>(), players.search("%"))
            assertEquals(setOf("NotchRenamed", "No_Way", "Nox"), players.search("no").toSet())
        }
    }
}

class SqliteReportStorageTest : ReportStorageContractTest(Dialect.SQLITE)

@RequiresDocker
class MysqlReportStorageTest : ReportStorageContractTest(Dialect.MYSQL)
