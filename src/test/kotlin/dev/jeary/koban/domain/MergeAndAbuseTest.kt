package dev.jeary.koban.domain

import dev.jeary.koban.domain.abuse.AntiAbuseSettings
import dev.jeary.koban.domain.abuse.CooldownTracker
import dev.jeary.koban.domain.abuse.GateResult
import dev.jeary.koban.domain.abuse.ReportGate
import dev.jeary.koban.domain.report.FeedbackState
import dev.jeary.koban.domain.report.MergePolicy
import dev.jeary.koban.domain.report.PriorityScale
import dev.jeary.koban.domain.report.REPORT_PRIORITY
import dev.jeary.koban.domain.report.ReportId
import dev.jeary.koban.domain.report.ReportStatus
import dev.jeary.koban.domain.report.Reporter
import dev.jeary.koban.domain.report.categoryCounts
import dev.jeary.koban.support.MutableClock
import dev.jeary.koban.support.Players
import dev.jeary.koban.support.openReport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class MergeAndAbuseTest {

    private val t0: Instant = Instant.parse("2026-11-19T12:00:00Z")

    @Nested
    inner class Merging {
        private val policy = MergePolicy(Duration.ofMinutes(30))
        private val existing = openReport(id = 7, lastReportedAt = t0)

        @Test
        fun `no active report means a new one`() {
            assertEquals(MergePolicy.Decision.CreateNew, policy.decide(emptyList(), Players.steve.uuid, t0) { emptySet() })
        }

        @Test
        fun `a report inside the window merges, whatever its category`() {
            val decision = policy.decide(listOf(existing), Players.alex.uuid, t0.plus(Duration.ofMinutes(29))) { setOf(Players.steve.uuid) }
            assertEquals(MergePolicy.Decision.MergeInto(existing), decision)
        }

        @Test
        fun `the window is measured from the last report, inclusive`() {
            val at = t0.plus(Duration.ofMinutes(30))
            assertEquals(MergePolicy.Decision.MergeInto(existing), policy.decide(listOf(existing), Players.alex.uuid, at) { emptySet() })
            val later = at.plusMillis(1)
            assertEquals(MergePolicy.Decision.CreateNew, policy.decide(listOf(existing), Players.alex.uuid, later) { emptySet() })
        }

        @Test
        fun `reporting the same player twice is caught`() {
            val decision = policy.decide(listOf(existing), Players.steve.uuid, t0) { setOf(Players.steve.uuid) }
            assertEquals(MergePolicy.Decision.AlreadyReported(existing), decision)
        }

        @Test
        fun `closed reports are never merge targets`() {
            val closed = existing.copy(
                status = ReportStatus.DISMISSED,
                closure = dev.jeary.koban.domain.report.Closure(dev.jeary.koban.domain.report.Outcome.NO_ACTION, null, "Console", t0),
            )
            assertEquals(MergePolicy.Decision.CreateNew, policy.decide(listOf(closed), Players.alex.uuid, t0) { emptySet() })
        }

        @Test
        fun `the most recently reported candidate wins`() {
            val older = openReport(id = 1, lastReportedAt = t0)
            val newer = openReport(id = 2, lastReportedAt = t0.plusSeconds(60))
            assertEquals(MergePolicy.Decision.MergeInto(newer), policy.decide(listOf(older, newer), Players.alex.uuid, t0.plusSeconds(90)) { emptySet() })
        }
    }

    @Nested
    inner class Priority {
        @Test
        fun `more reporters first, then oldest first`() {
            val a = openReport(id = 1, reporters = 1, createdAt = t0)
            val b = openReport(id = 2, reporters = 3, createdAt = t0.plusSeconds(60))
            val c = openReport(id = 3, reporters = 3, createdAt = t0.plusSeconds(30))
            val d = openReport(id = 4, reporters = 1, createdAt = t0.minusSeconds(30))
            assertEquals(listOf(3L, 2L, 4L, 1L), listOf(a, b, c, d).sortedWith(REPORT_PRIORITY).map { it.id.value })
        }

        @Test
        fun `tiers pick the highest threshold reached`() {
            val scale = PriorityScale.DEFAULT
            assertEquals("Normal", scale.of(1).name)
            assertEquals("Raised", scale.of(2).name)
            assertEquals("Raised", scale.of(3).name)
            assertEquals("Urgent", scale.of(4).name)
            assertEquals("Urgent", scale.of(400).name)
        }

        @Test
        fun `category counts are ordered by popularity, ties by who came first`() {
            fun r(name: String, category: String, offset: Long) =
                Reporter(ReportId(1), Players.ref(name), category, null, t0.plusSeconds(offset), FeedbackState.NONE)
            val counts = categoryCounts(
                listOf(
                    r("a", "spam", 0), r("b", "cheating", 10), r("c", "cheating", 20),
                    r("d", "harassment", 30), r("e", "spam", 40), r("f", "griefing", 50),
                ),
            )
            assertEquals(listOf("spam" to 2, "cheating" to 2, "harassment" to 1, "griefing" to 1), counts)
        }
    }

    @Nested
    inner class AntiAbuse {
        private val clock = MutableClock(t0)
        private val gate = ReportGate(CooldownTracker(clock))
        private val settings = AntiAbuseSettings(cooldown = Duration.ofSeconds(60), maxOpenPerReporter = 3)

        @Test
        fun `self-reports are refused`() {
            assertEquals(GateResult.SelfReport, gate.precheck(Players.steve.uuid, Players.steve.uuid, settings, bypassCooldown = true))
        }

        @Test
        fun `the cooldown counts down and then lets the reporter through`() {
            gate.recordAccepted(Players.steve.uuid)
            clock.advance(Duration.ofSeconds(20))
            assertEquals(
                GateResult.OnCooldown(Duration.ofSeconds(40)),
                gate.precheck(Players.steve.uuid, Players.notch.uuid, settings, bypassCooldown = false),
            )
            clock.advance(Duration.ofSeconds(40))
            assertEquals(GateResult.Allowed, gate.precheck(Players.steve.uuid, Players.notch.uuid, settings, bypassCooldown = false))
        }

        @Test
        fun `cooldowns are per reporter and can be bypassed`() {
            gate.recordAccepted(Players.steve.uuid)
            assertEquals(GateResult.Allowed, gate.precheck(Players.alex.uuid, Players.notch.uuid, settings, bypassCooldown = false))
            assertEquals(GateResult.Allowed, gate.precheck(Players.steve.uuid, Players.notch.uuid, settings, bypassCooldown = true))
        }

        @Test
        fun `a zero cooldown is off`() {
            gate.recordAccepted(Players.steve.uuid)
            val off = settings.copy(cooldown = Duration.ZERO)
            assertEquals(GateResult.Allowed, gate.precheck(Players.steve.uuid, Players.notch.uuid, off, bypassCooldown = false))
        }

        @Test
        fun `the open report limit`() {
            assertEquals(GateResult.Allowed, gate.checkOpenLimit(2, settings))
            assertEquals(GateResult.TooManyOpen(3), gate.checkOpenLimit(3, settings))
            assertEquals(false, gate.limitApplies(settings.copy(maxOpenPerReporter = 0), bypassLimit = false))
            assertEquals(false, gate.limitApplies(settings, bypassLimit = true))
        }

        @Test
        fun `pruning forgets expired cooldowns only`() {
            val tracker = CooldownTracker(clock)
            tracker.record(Players.steve.uuid)
            clock.advance(Duration.ofSeconds(30))
            tracker.record(Players.alex.uuid)
            clock.advance(Duration.ofSeconds(45))
            tracker.prune(Duration.ofSeconds(60))
            assertEquals(Duration.ZERO, tracker.remaining(Players.steve.uuid, Duration.ofSeconds(60)))
            assertEquals(Duration.ofSeconds(15), tracker.remaining(Players.alex.uuid, Duration.ofSeconds(60)))
        }
    }
}
