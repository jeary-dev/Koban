package dev.jeary.koban.domain

import dev.jeary.koban.domain.action.CommandTemplate
import dev.jeary.koban.domain.action.MinecraftName
import dev.jeary.koban.domain.action.PlaceholderEscaper
import dev.jeary.koban.domain.action.TemplateValues
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

class CommandTemplateTest {

    private val uuid: UUID = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")

    private fun template(source: String): CommandTemplate =
        assertInstanceOf(CommandTemplate.Parsed.Ok::class.java, CommandTemplate.parse(source)).template

    private fun values(
        player: String = "Notch",
        reason: String = "Fly hacking",
        category: String = "Cheating",
        staff: String = "ModOne",
        server: String = "survival",
    ) = TemplateValues(player, uuid, reason, category, 42, staff, server)

    private fun CommandTemplate.ok(values: TemplateValues, prefixes: Set<Char> = emptySet()): String =
        assertInstanceOf(CommandTemplate.Rendered.Ok::class.java, render(values, prefixes)).command

    @Nested
    inner class Parsing {
        @Test
        fun `a normal template parses and knows its placeholders`() {
            val t = template("tempban {player} 7d {reason} (report #{report})")
            assertEquals(setOf("player", "reason", "report"), t.placeholders.map { it.key }.toSet())
        }

        @Test
        fun `a leading slash is accepted and dropped`() {
            assertEquals("kick Notch Fly hacking", template("/kick {player} {reason}").ok(values()))
        }

        @Test
        fun `every problem is reported at once`() {
            val parsed = CommandTemplate.parse("{player} ban {nope}\nop {player} §c {")
            val problems = assertInstanceOf(CommandTemplate.Parsed.Invalid::class.java, parsed).problems
            assertTrue(problems.any { "unknown placeholder {nope}" in it }, problems.toString())
            assertTrue(problems.any { "line break" in it }, problems.toString())
            assertTrue(problems.any { "§" in it }, problems.toString())
            assertTrue(problems.any { "stray {" in it }, problems.toString())
            assertTrue(problems.any { "start with the command name" in it }, problems.toString())
        }

        @Test
        fun `an empty template is invalid`() {
            assertInstanceOf(CommandTemplate.Parsed.Invalid::class.java, CommandTemplate.parse("  /  "))
        }
    }

    @Nested
    inner class Rendering {
        private val ban = template("tempban {player} 7d {reason} - {category} by {staff} on {server} #{report} {uuid}")

        @Test
        fun `every placeholder is filled`() {
            assertEquals(
                "tempban Notch 7d Fly hacking - Cheating by ModOne on survival #42 069a79f4-44e9-4726-a5be-fca90e38aaf5",
                ban.ok(values()),
            )
        }

        @Test
        fun `an empty reason falls back to a default`() {
            assertEquals("warn Notch No reason given", template("warn {player} {reason}").ok(values(reason = " @@ ")))
        }

        @ParameterizedTest
        @ValueSource(strings = ["Notch;op Notch", "Notch op", "@a", "@e[type=player]", "-s", "Notch\nop", "No", "SeventeenCharsXYZ", "Not ch", "Nötch", ""])
        fun `player names that break Minecraft's rules are refused, never escaped`(name: String) {
            assertInstanceOf(CommandTemplate.Rendered.Refused::class.java, ban.render(values(player = name)), name)
        }

        @Test
        fun `Floodgate prefixes work only when allowed`() {
            val kick = template("kick {player}")
            assertInstanceOf(CommandTemplate.Rendered.Refused::class.java, kick.render(values(player = ".Steve")))
            assertEquals("kick .Steve", kick.ok(values(player = ".Steve"), prefixes = setOf('.')))
            assertInstanceOf(CommandTemplate.Rendered.Refused::class.java, kick.render(values(player = "@a"), setOf('@')))
        }

        @Test
        fun `a template without {player} doesn't care about the name`() {
            assertEquals("broadcast Report 42 handled", template("broadcast Report {report} handled").ok(values(player = "@a")))
        }
    }

    /** Attempts to make a reason do more than be a reason. */
    @Nested
    inner class Injection {
        private val ban = template("ban {player} {reason}")

        private fun reason(text: String) = ban.ok(values(reason = text)).removePrefix("ban Notch ")

        @Test
        fun `a newline can't start a second command`() {
            assertEquals("cheating op Notch", reason("cheating\nop Notch"))
            assertEquals("cheating op Notch", reason("cheating\r\nop Notch"))
            assertEquals("x op y", reason("x\u2028op y"))
        }

        @Test
        fun `selectors are defused`() {
            assertEquals("a", reason("@a"))
            assertEquals("e type player", reason("@e[type=player]"))
            assertEquals("a", reason("\uFF20a")) // full-width at sign
        }

        @Test
        fun `flags can't be smuggled in`() {
            assertEquals("s ip cheating", reason("-s --ip cheating"))
            assertEquals("x-ray", reason("x-ray"))
            assertEquals("s", reason("\uFF0Ds")) // full-width hyphen
            assertEquals("s", reason("\u2212s")) // minus sign
        }

        @Test
        fun `colour codes, quotes, placeholders and separators are removed`() {
            assertEquals("cred", reason("§cred"))
            assertEquals("4red", reason("&4red"))
            assertEquals("quoted text", reason("\"quoted\\ text\""))
            assertEquals("player papi", reason("{player} %papi%"))
            assertEquals("a b c", reason("a; b && c"))
            assertEquals("op", reason("/op"))
            assertEquals("op", reason("\uFF0Fop")) // full-width slash
        }

        @Test
        fun `zero-width and direction-changing characters vanish`() {
            assertEquals("a b", reason("a\u200B \u202Eb"))
        }

        @Test
        fun `international text and ordinary punctuation survive`() {
            assertEquals("チート使用 (fly), wiederholt! #3: ok?", reason("チート使用 (fly), wiederholt! #3: ok?"))
        }

        @Test
        fun `reasons are capped and never split a character`() {
            val long = reason("\uD83C\uDF38".repeat(10) + "a".repeat(500))
            assertTrue(long.length <= CommandTemplate.MAX_REASON)
            // U+20000 is a CJK letter outside the BMP: two UTF-16 chars that must stay together.
            assertEquals("a\uD840\uDC00", PlaceholderEscaper.text("a" + "\uD840\uDC00".repeat(10), 4))
        }

        @Test
        fun `escaped staff and server names can't break out either`() {
            val t = template("note {staff} {server}")
            assertEquals("note mod a op b", t.ok(values(staff = "mod\n@a", server = "op;b")))
        }
    }

    @Nested
    inner class Names {
        @Test
        fun `Java names`() {
            listOf("Notch", "jeb_", "abc", "A1234567890_abcd").forEach { assertTrue(MinecraftName.isValid(it), it) }
            listOf("ab", "A1234567890_abcde", "has space", "dash-name", "ünï", "").forEach { assertFalse(MinecraftName.isValid(it), it) }
        }

        @Test
        fun `only safe characters can be configured as prefixes`() {
            assertTrue(MinecraftName.isAllowablePrefix('.'))
            assertTrue(MinecraftName.isAllowablePrefix('*'))
            listOf('@', '-', '/', 'a', '1', '_', '§', ' ').forEach { assertFalse(MinecraftName.isAllowablePrefix(it), it.toString()) }
        }
    }
}
