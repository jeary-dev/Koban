package dev.jeary.koban.domain

import dev.jeary.koban.domain.link.LinkCodes
import dev.jeary.koban.domain.permission.RoleGate
import dev.jeary.koban.domain.permission.StaffAction
import dev.jeary.koban.domain.version.ServerVersion
import dev.jeary.koban.support.MutableClock
import dev.jeary.koban.support.Players
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Duration

class PermissionVersionLinkTest {

    @Nested
    inner class Roles {
        private val helper = 100L
        private val moderator = 200L
        private val admin = 300L
        private val unrelated = 999L
        private val gate = RoleGate(
            mapOf(
                StaffAction.VIEW to setOf(helper),
                StaffAction.CLAIM to setOf(moderator),
                StaffAction.RESOLVE to setOf(moderator),
                StaffAction.NOTE to setOf(helper, moderator),
                StaffAction.PUNISH to emptySet(),
                StaffAction.ADMIN to setOf(admin),
            ),
        )

        @Test
        fun `a role gets exactly what it's mapped to`() {
            assertEquals(setOf(StaffAction.VIEW, StaffAction.NOTE), gate.allowed(listOf(helper)))
            assertEquals(
                setOf(StaffAction.VIEW, StaffAction.CLAIM, StaffAction.RESOLVE, StaffAction.NOTE),
                gate.allowed(listOf(moderator)),
            )
        }

        @Test
        fun `admin allows everything`() {
            assertEquals(StaffAction.entries.toSet(), gate.allowed(listOf(admin)))
        }

        @Test
        fun `any staff action implies view`() {
            val claimOnly = RoleGate(mapOf(StaffAction.CLAIM to setOf(moderator)))
            assertTrue(claimOnly.allows(listOf(moderator), StaffAction.VIEW))
            assertFalse(claimOnly.allows(listOf(moderator), StaffAction.RESOLVE))
        }

        @Test
        fun `no mapped role means nothing, and an empty mapping grants nothing`() {
            assertEquals(emptySet<StaffAction>(), gate.allowed(listOf(unrelated)))
            assertEquals(emptySet<StaffAction>(), gate.allowed(emptyList()))
            assertFalse(gate.allows(listOf(helper, moderator), StaffAction.PUNISH))
            assertTrue(RoleGate(emptyMap()).isEmpty)
        }

        @Test
        fun `roles combine`() {
            assertTrue(gate.allows(listOf(unrelated, moderator), StaffAction.CLAIM))
        }

        @Test
        fun `config keys map to actions case-insensitively`() {
            assertEquals(StaffAction.PUNISH, StaffAction.of("Punish"))
            assertNull(StaffAction.of("ban"))
        }
    }

    @Nested
    inner class Versions {
        @ParameterizedTest
        @CsvSource(
            "1.20.6-R0.1-SNAPSHOT, 1.20.6",
            "1.21-R0.1-SNAPSHOT, 1.21",
            "1.21.11-R0.1-SNAPSHOT, 1.21.11",
            "26.1.2-R0.1-SNAPSHOT, 26.1.2",
            "26.1.2.build.74-stable, 26.1.2",
            "26.3, 26.3",
            "v1.0.0, 1.0.0",
            "26.3-rc-3, 26.3-rc-3",
            "1.21.11-pre5, 1.21.11-pre5",
        )
        fun `both numbering schemes parse`(input: String, expected: String) {
            assertEquals(expected, ServerVersion.parse(input).toString())
        }

        @Test
        fun `year-based versions sort after every 1-x version`() {
            val ordered = listOf("1.20.6", "1.21", "1.21.4", "1.21.11", "26.1", "26.1.2", "26.2", "26.3-rc-3", "26.3", "26.10")
                .map { requireNotNull(ServerVersion.parse(it)) }
            assertEquals(ordered, ordered.shuffled(java.util.Random(7)).sorted())
        }

        @Test
        fun `missing parts count as zero`() {
            assertEquals(ServerVersion.parse("26.1"), ServerVersion.parse("26.1.0"))
            assertEquals(ServerVersion.parse("26.1")?.hashCode(), ServerVersion.parse("26.1.0")?.hashCode())
            assertNotEquals(ServerVersion.parse("26.1"), ServerVersion.parse("26.1.1"))
        }

        @Test
        fun `minimum support`() {
            assertTrue(ServerVersion.of(26, 1).isAtLeast(ServerVersion.MINIMUM))
            assertTrue(ServerVersion.of(1, 21).isAtLeast(ServerVersion.MINIMUM))
            assertFalse(ServerVersion.of(1, 20, 4).isAtLeast(ServerVersion.MINIMUM))
            assertEquals(26, ServerVersion.parse("26.1.2")?.major)
        }

        @Test
        fun `garbage doesn't parse`() {
            assertNull(ServerVersion.parse("unknown"))
            assertNull(ServerVersion.parse(""))
        }
    }

    @Nested
    inner class Linking {
        private val clock = MutableClock()
        private val codes = LinkCodes(clock, Duration.ofMinutes(10))

        @Test
        fun `a code is single use and case-insensitive`() {
            val code = codes.issue(Players.mod1.uuid, "ModOne")
            assertEquals(LinkCodes.LENGTH, code.length)
            assertTrue(code.all { it in LinkCodes.ALPHABET })
            assertEquals(Players.mod1.uuid, codes.redeem(" ${code.lowercase()} ")?.minecraft)
            assertNull(codes.redeem(code))
        }

        @Test
        fun `codes expire`() {
            val code = codes.issue(Players.mod1.uuid, "ModOne")
            clock.advance(Duration.ofMinutes(10).plusSeconds(1))
            assertNull(codes.redeem(code))
        }

        @Test
        fun `a new code replaces the old one`() {
            val first = codes.issue(Players.mod1.uuid, "ModOne")
            val second = codes.issue(Players.mod1.uuid, "ModOne")
            assertNull(codes.redeem(first).takeIf { first != second })
            assertEquals("ModOne", codes.redeem(second)?.name)
        }

        @Test
        fun `the alphabet has no look-alikes`() {
            listOf('0', 'O', '1', 'I', 'L').forEach { assertFalse(it in LinkCodes.ALPHABET, it.toString()) }
        }
    }
}
