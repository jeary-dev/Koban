package dev.jeary.koban.support

import dev.jeary.koban.domain.actor.Actor
import dev.jeary.koban.domain.report.PlayerRef
import dev.jeary.koban.domain.report.Report
import dev.jeary.koban.domain.report.ReportId
import dev.jeary.koban.domain.report.ReportStatus
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** A clock tests move by hand. */
class MutableClock(private var now: Instant = Instant.parse("2026-11-19T12:00:00Z")) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now

    @Synchronized
    fun advance(by: Duration) {
        now = now.plus(by)
    }
}

/** Stable, readable test players. */
object Players {
    fun ref(name: String) = PlayerRef(UUID.nameUUIDFromBytes("player:$name".toByteArray()), name)

    val notch = ref("Notch")
    val steve = ref("Steve")
    val alex = ref("Alex")
    val herobrine = ref("Herobrine")
    val mod1 = ref("ModOne")
    val mod2 = ref("ModTwo")

    fun staff(player: PlayerRef) = Actor.player(player.uuid, player.name)
}

fun openReport(
    id: Long = 1,
    reported: PlayerRef = Players.notch,
    reporters: Int = 1,
    createdAt: Instant = Instant.parse("2026-11-19T12:00:00Z"),
    lastReportedAt: Instant = createdAt,
) = Report(
    id = ReportId(id),
    status = ReportStatus.OPEN,
    category = "cheating",
    reported = reported,
    server = "survival",
    location = null,
    reporterCount = reporters,
    createdAt = createdAt,
    lastReportedAt = lastReportedAt,
    updatedAt = createdAt,
    claim = null,
    closure = null,
    discord = null,
    discordDirty = true,
    version = 0,
)

/** Fails the test when [value] is null, and returns it otherwise (JUnit's assertNotNull returns nothing). */
fun <T : Any> present(value: T?, message: String = "expected a value, got null"): T = value ?: throw AssertionError(message)
