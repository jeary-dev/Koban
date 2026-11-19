package dev.jeary.koban.domain

import dev.jeary.koban.domain.actor.Actor
import dev.jeary.koban.domain.actor.Source
import dev.jeary.koban.domain.report.Change
import dev.jeary.koban.domain.report.Outcome
import dev.jeary.koban.domain.report.Rejection
import dev.jeary.koban.domain.report.Report
import dev.jeary.koban.domain.report.ReportCommand
import dev.jeary.koban.domain.report.ReportLifecycle
import dev.jeary.koban.domain.report.ReportStatus
import dev.jeary.koban.domain.report.Transition
import dev.jeary.koban.support.Players
import dev.jeary.koban.support.openReport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

class ReportLifecycleTest {

    private val now: Instant = Instant.parse("2026-11-19T13:00:00Z")
    private val mod1 = Players.staff(Players.mod1)
    private val mod2 = Players.staff(Players.mod2)

    private fun Report.run(command: ReportCommand): Transition = ReportLifecycle.apply(this, command, now)

    private fun Transition.applied(): Transition.Applied = assertInstanceOf(Transition.Applied::class.java, this)

    private fun Transition.rejection(): Rejection = assertInstanceOf(Transition.Rejected::class.java, this).reason

    private fun claimedBy(actor: Actor): Report = openReport().run(ReportCommand.Claim(actor)).applied().after

    @Nested
    inner class Claiming {
        @Test
        fun `claiming an open report claims it and bumps the version`() {
            val t = openReport().run(ReportCommand.Claim(mod1)).applied()
            assertEquals(ReportStatus.CLAIMED, t.after.status)
            assertEquals(Change.CLAIMED, t.change)
            assertEquals("ModOne", t.after.claim?.by?.name)
            assertEquals(1, t.after.version)
            assertEquals(now, t.after.updatedAt)
            assertTrue(t.after.discordDirty)
        }

        @Test
        fun `claiming your own claim again is refused politely`() {
            assertEquals(Rejection.AlreadyYours, claimedBy(mod1).run(ReportCommand.Claim(mod1)).rejection())
        }

        @Test
        fun `the same person on Discord counts as the claimant once linked`() {
            val linked = Actor.discord(42L, "mod.one", Players.mod1.uuid, "ModOne")
            assertEquals(Rejection.AlreadyYours, claimedBy(mod1).run(ReportCommand.Claim(linked)).rejection())
        }

        @Test
        fun `someone else's claim is refused with their name`() {
            assertEquals(Rejection.ClaimedByOther("ModOne"), claimedBy(mod1).run(ReportCommand.Claim(mod2)).rejection())
        }

        @Test
        fun `override takes over someone else's claim`() {
            val t = claimedBy(mod1).run(ReportCommand.Claim(mod2, override = true)).applied()
            assertEquals(Change.TAKEN_OVER, t.change)
            assertEquals("ModTwo", t.after.claim?.by?.name)
        }

        @Test
        fun `the console has no identity to claim with`() {
            assertEquals(Rejection.NeedsIdentity, openReport().run(ReportCommand.Claim(Actor.CONSOLE)).rejection())
        }

        @Test
        fun `closed reports can't be claimed`() {
            val closed = openReport().run(ReportCommand.Dismiss(mod1)).applied().after
            assertEquals(Rejection.AlreadyClosed, closed.run(ReportCommand.Claim(mod1)).rejection())
        }

        @Test
        fun `unclaiming returns the report to open`() {
            val t = claimedBy(mod1).run(ReportCommand.Unclaim(mod1)).applied()
            assertEquals(ReportStatus.OPEN, t.after.status)
            assertNull(t.after.claim)
            assertEquals(Change.UNCLAIMED, t.change)
        }

        @Test
        fun `only the claimant or override can unclaim`() {
            val claimed = claimedBy(mod1)
            assertEquals(Rejection.ClaimedByOther("ModOne"), claimed.run(ReportCommand.Unclaim(mod2)).rejection())
            claimed.run(ReportCommand.Unclaim(mod2, override = true)).applied()
            assertEquals(Rejection.NotClaimed, openReport().run(ReportCommand.Unclaim(mod1)).rejection())
        }
    }

    @Nested
    inner class Closing {
        @Test
        fun `resolving records the outcome, the closer and clears the claim`() {
            val t = claimedBy(mod1).run(ReportCommand.Resolve(mod1, Outcome.ACTION_TAKEN)).applied()
            assertEquals(ReportStatus.RESOLVED, t.after.status)
            assertEquals(Outcome.ACTION_TAKEN, t.after.closure?.outcome)
            assertEquals("ModOne", t.after.closure?.byName)
            assertNull(t.after.claim)
            assertEquals(Change.RESOLVED, t.change)
        }

        @Test
        fun `an open report can be resolved without claiming first`() {
            openReport().run(ReportCommand.Resolve(mod2, Outcome.NO_ACTION)).applied()
        }

        @Test
        fun `dismissing means no action`() {
            val t = openReport().run(ReportCommand.Dismiss(mod1)).applied()
            assertEquals(ReportStatus.DISMISSED, t.after.status)
            assertEquals(Outcome.NO_ACTION, t.after.closure?.outcome)
        }

        @Test
        fun `someone else's claimed report needs override to close`() {
            val claimed = claimedBy(mod1)
            assertEquals(Rejection.ClaimedByOther("ModOne"), claimed.run(ReportCommand.Resolve(mod2, Outcome.NO_ACTION)).rejection())
            assertEquals(Rejection.ClaimedByOther("ModOne"), claimed.run(ReportCommand.Dismiss(mod2)).rejection())
            claimed.run(ReportCommand.Dismiss(mod2, override = true)).applied()
        }

        @Test
        fun `the console outranks every claim`() {
            val t = claimedBy(mod1).run(ReportCommand.Resolve(Actor.CONSOLE, Outcome.ACTION_TAKEN)).applied()
            assertNull(t.after.closure?.by)
            assertEquals("Console", t.after.closure?.byName)
        }

        @Test
        fun `an API caller without override can't close a claimed report`() {
            val api = Actor(Source.API, null, "SomePlugin")
            assertEquals(Rejection.ClaimedByOther("ModOne"), claimedBy(mod1).run(ReportCommand.Dismiss(api)).rejection())
        }

        @Test
        fun `closing twice is refused`() {
            val resolved = openReport().run(ReportCommand.Resolve(mod1, Outcome.NO_ACTION)).applied().after
            assertEquals(Rejection.AlreadyClosed, resolved.run(ReportCommand.Resolve(mod1, Outcome.ACTION_TAKEN)).rejection())
            assertEquals(Rejection.AlreadyClosed, resolved.run(ReportCommand.Dismiss(mod1)).rejection())
        }
    }

    @Nested
    inner class Reopening {
        @Test
        fun `reopening clears the closure`() {
            val closed = openReport().run(ReportCommand.Resolve(mod1, Outcome.ACTION_TAKEN)).applied().after
            val t = closed.run(ReportCommand.Reopen(mod2)).applied()
            assertEquals(ReportStatus.OPEN, t.after.status)
            assertNull(t.after.closure)
            assertEquals(Change.REOPENED, t.change)
            assertEquals(2, t.after.version)
        }

        @Test
        fun `active reports can't be reopened`() {
            assertEquals(Rejection.NotClosed, openReport().run(ReportCommand.Reopen(mod1)).rejection())
            assertEquals(Rejection.NotClosed, claimedBy(mod1).run(ReportCommand.Reopen(mod1)).rejection())
        }
    }

    @Test
    fun `every status and command pair either applies or gives a reason`() {
        val reports = listOf(
            openReport(),
            claimedBy(mod1),
            openReport().run(ReportCommand.Resolve(mod1, Outcome.ACTION_TAKEN)).applied().after,
            openReport().run(ReportCommand.Dismiss(mod1)).applied().after,
        )
        val commands = listOf(
            ReportCommand.Claim(mod2), ReportCommand.Unclaim(mod2), ReportCommand.Resolve(mod2, Outcome.NO_ACTION),
            ReportCommand.Dismiss(mod2), ReportCommand.Reopen(mod2),
        )
        for (report in reports) {
            for (command in commands) {
                when (val t = report.run(command)) {
                    // Report's own invariants (claim ⇔ CLAIMED, closure ⇔ closed) are checked on construction.
                    is Transition.Applied -> assertEquals(report.version + 1, t.after.version)
                    is Transition.Rejected -> Unit
                }
            }
        }
    }
}
