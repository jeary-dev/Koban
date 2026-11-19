package dev.jeary.koban.discord

import dev.jeary.koban.config.ActionsConfig
import dev.jeary.koban.config.ConfigError
import dev.jeary.koban.config.ConfigNode
import dev.jeary.koban.config.ConfigLoader
import dev.jeary.koban.config.DiscordSettings
import dev.jeary.koban.config.ReportCategory
import dev.jeary.koban.config.Reasons
import dev.jeary.koban.config.Secret
import dev.jeary.koban.domain.evidence.ChatLine
import dev.jeary.koban.domain.evidence.EvidenceSnapshot
import dev.jeary.koban.domain.evidence.PlayerState
import dev.jeary.koban.domain.permission.StaffAction
import dev.jeary.koban.domain.report.FeedbackState
import dev.jeary.koban.domain.report.PlayerRef
import dev.jeary.koban.domain.report.PriorityScale
import dev.jeary.koban.domain.report.ReportId
import dev.jeary.koban.domain.report.ReportStatus
import dev.jeary.koban.domain.report.Reporter
import dev.jeary.koban.support.Players
import dev.jeary.koban.support.openReport
import dev.jeary.koban.support.present
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Base64

class DiscordContentTest {

    private val names = mapOf("cheating" to "Cheating", "spam" to "Spam", "griefing" to "Griefing", "harassment" to "Harassment", "other" to "Something else")
    private val content = ThreadContent(
        text = { key, values -> values.entries.fold(key) { acc, (k, v) -> "$acc[$k=$v]" }.let { if (key.startsWith("discord.tag-")) key.removePrefix("discord.tag-").replaceFirstChar(Char::uppercase) else it } },
        categoryName = { names[it] ?: it },
        priority = PriorityScale.DEFAULT,
        avatar = { "https://mc-heads.net/avatar/${it.reported.uuid}/64" },
    )
    private val at = Instant.parse("2026-11-19T12:00:00Z")

    private fun reporter(name: String, category: String, details: String? = null, offset: Long = 0) =
        Reporter(ReportId(1), Players.ref(name), category, details, at.plusSeconds(offset), FeedbackState.NONE)

    @Nested
    inner class Titles {
        @Test
        fun `the title is number, first category, extra reporters and name`() {
            assertEquals("#42 · Cheating +1 · Notch", content.title(openReport(id = 42, reporters = 2)))
            assertEquals("#7 · Cheating · Notch", content.title(openReport(id = 7)))
        }

        @Test
        fun `a long category is cut so the title stays under 100 characters`() {
            val long = ThreadContent({ k, _ -> k }, { "x".repeat(300) }, PriorityScale.DEFAULT, { null })
            val title = long.title(openReport(id = 123456, reporters = 99, reported = PlayerRef(Players.notch.uuid, "A_Very_Long_Bedrock_Name_Here")))
            assertTrue(title.length <= DiscordLimits.THREAD_NAME, "${title.length}: $title")
            assertTrue(title.startsWith("#123456 · xxx"))
            assertTrue(title.endsWith(" +98 · A_Very_Long_Bedrock_Name_Here"), title)
        }
    }

    @Nested
    inner class Tags {
        private val all = setOf("Open", "Claimed", "Resolved", "Dismissed", "Cheating", "Spam", "Griefing", "Harassment", "Something else")

        @Test
        fun `status first, then categories by reporter count`() {
            val reporters = listOf(
                reporter("A", "spam", offset = 0),
                reporter("B", "cheating", offset = 1),
                reporter("C", "cheating", offset = 2),
            )
            assertEquals(listOf("Open", "Cheating", "Spam"), content.tags(openReport(reporters = 3), reporters, all))
        }

        @Test
        fun `never more than five, and only tags the forum has`() {
            val reporters = names.keys.mapIndexed { i, key -> reporter("P$i", key, offset = i.toLong()) }
            val tags = content.tags(openReport(reporters = 5), reporters, all)
            assertEquals(5, tags.size)
            assertEquals("Open", tags.first())
            assertEquals(listOf("Open", "Spam"), content.tags(openReport(), listOf(reporter("A", "spam")), setOf("open", "Spam")).map { it.replaceFirstChar(Char::uppercase) })
        }
    }

    @Nested
    inner class Text {
        @Test
        fun `markdown in names and chat is escaped`() {
            assertEquals("""\_\_x\_\_ \*\*bold\*\* \`code\` \> quote""", DiscordText.escape("__x__ **bold** `code` > quote"))
            assertEquals("""a\\b""", DiscordText.escape("""a\b"""))
        }

        @Test
        fun `cutting never splits an emoji`() {
            val text = "ab" + "🌸".repeat(5)
            // Cutting at 4 would keep half of the first flower: it's dropped whole instead.
            assertEquals("ab…", DiscordText.cut(text, 4))
            assertEquals("ab🌸…", DiscordText.cut(text, 5))
        }

        @Test
        fun `last lines keeps the newest whole lines`() {
            assertEquals("three\nfour", DiscordText.lastLines(listOf("one", "two", "three", "four"), 10))
            assertEquals("…", DiscordText.lastLines(listOf("averyverylongline"), 1).takeLast(1))
        }
    }

    @Nested
    inner class Embed {
        @Test
        fun `a huge report still fits every Discord limit`() {
            val reported = Players.notch
            val chat = (0 until 400).map { ChatLine(at.toEpochMilli() + it * 1000L, reported.uuid.toString(), reported.name, "line $it " + "**spam** ".repeat(30)) }
            val evidence = EvidenceSnapshot(
                capturedAt = at.toEpochMilli(),
                server = "survival",
                reported = PlayerState(reported.uuid.toString(), reported.name, online = true, gameMode = "SURVIVAL", health = 20.0, ping = 30, clientBrand = "vanilla"),
                chat = chat,
            )
            val reporters = (0 until 60).map { reporter("Player$it", if (it % 2 == 0) "cheating" else "spam", "details ".repeat(40), it.toLong()) }
            val spec = content.embed(openReport(reporters = 60), reporters, listOf(evidence), noteCount = 3)
            assertTrue(spec.length <= DiscordLimits.EMBED_TOTAL, "embed is ${spec.length}")
            assertTrue(spec.fields.size <= DiscordLimits.FIELDS)
            spec.fields.forEach { assertTrue(it.value.length <= DiscordLimits.FIELD_VALUE, "${it.name} is ${it.value.length}") }
            // The newest chat is what's kept.
            val chatField = spec.fields.first { it.name == "discord.field-chat" }
            assertTrue("line 399" in chatField.value)
            assertFalse("line 0 " in chatField.value)
        }

        @Test
        fun `closed reports lose their priority colour`() {
            val open = openReport()
            assertEquals(PriorityScale.DEFAULT.of(open).color, content.color(open))
            val dismissed = open.copy(status = ReportStatus.DISMISSED, closure = dev.jeary.koban.domain.report.Closure(dev.jeary.koban.domain.report.Outcome.NO_ACTION, null, "Console", at))
            assertTrue(content.color(dismissed) != PriorityScale.DEFAULT.of(open).color)
        }
    }

    @Nested
    inner class ComponentIds {
        @Test
        fun `ids round-trip and junk is ignored`() {
            val id = Ids.of(Ids.PUNISH_CONFIRM, ReportId(42), "ban", "1234")
            assertEquals("koban:punish-yes:42:ban:1234", id)
            val parsed = present(Ids.parse(id))
            assertEquals(ReportId(42), parsed.id)
            assertEquals(listOf("ban", "1234"), parsed.extra)
            assertNull(Ids.parse("other:claim:1"))
            assertNull(Ids.parse("koban:claim:-1"))
            assertNull(Ids.parse("koban:claim:abc"))
            assertTrue(id.length <= 100)
        }
    }

    @Nested
    inner class Settings {
        private val fakeToken = Base64.getUrlEncoder().withoutPadding().encodeToString("123456789012345678".toByteArray()) + ".AbCdEf.ghIJklMNopQRstUVwxYZ0123456789-_"

        private fun parse(yaml: String, env: Map<String, String> = emptyMap()): Pair<DiscordSettings, List<ConfigError>> {
            val problems = ArrayList<ConfigError>()
            val config = YamlConfiguration().apply { loadFromString(yaml) }
            return DiscordSettings.parse(ConfigNode("config.yml", config, "", problems)) { env[it] } to problems
        }

        @Test
        fun `a valid section parses, roles included`() {
            val (s, problems) = parse(
                """
                discord:
                  enabled: true
                  token: '$fakeToken'
                  guild-id: 111111111111111111
                  channel-id: '222222222222222222'
                  roles:
                    claim: [333333333333333333]
                    admin: ['444444444444444444']
                """.trimIndent(),
            )
            assertEquals(emptyList<ConfigError>(), problems.filter { it.isError })
            assertEquals(111111111111111111, s.guildId)
            assertEquals(222222222222222222, s.channelId)
            assertTrue(s.roles.allows(listOf(333333333333333333), StaffAction.CLAIM))
            assertFalse(s.roles.allows(listOf(333333333333333333), StaffAction.PUNISH))
            assertTrue(s.roles.allows(listOf(444444444444444444), StaffAction.PUNISH))
        }

        @Test
        fun `the token never appears in an error, a toString or the settings`() {
            val bad = "not-a-token-but-secret-value"
            val (s, problems) = parse("discord:\n  enabled: true\n  token: '$bad'\n  guild-id: 1\n  channel-id: 2\n")
            assertTrue(problems.any { it.path == "discord.token" }, problems.toString())
            problems.forEach { assertFalse(bad in it.toString(), it.toString()) }
            assertFalse(bad in s.toString())
            assertFalse(bad in s.token.toString())
            assertEquals(bad, s.token.reveal())
            assertEquals("<hidden>", Secret("x.y.z").toString())
        }

        @Test
        fun `the environment variable wins over the file`() {
            val (s, problems) = parse("discord:\n  enabled: true\n  token: ''\n  guild-id: 1\n  channel-id: 2\n", mapOf(DiscordSettings.TOKEN_ENV to fakeToken))
            assertTrue(problems.none { it.path == "discord.token" })
            assertEquals(fakeToken, s.token.reveal())
        }

        @Test
        fun `more reasons than forum tags is a warning, only when tags are created`() {
            val (s, _) = parse("discord:\n  enabled: true\n  token: '$fakeToken'\n  guild-id: 1\n  channel-id: 2\n")
            fun reasons(n: Int) = Reasons((1..n).map { ReportCategory("r$it", "R$it", "R$it", Material.PAPER, null, emptyList(), false) })
            fun check(n: Int, settings: DiscordSettings) = ArrayList<ConfigError>().also { ConfigLoader.crossCheck(reasons(n), settings, it) }

            assertEquals(emptyList<ConfigError>(), check(16, s))
            val warned = check(17, s)
            assertEquals(1, warned.size)
            assertFalse(warned.single().isError)
            assertEquals(emptyList<ConfigError>(), check(17, s.copy(createTags = false)))
            assertTrue(check(0, s).single().isError)
        }

        @Test
        fun `bad ids and unknown actions are named`() {
            val (_, problems) = parse("discord:\n  enabled: true\n  token: '$fakeToken'\n  guild-id: abc\n  channel-id: ''\n  roles:\n    fly: [1]\n    claim: [nope]\n")
            val paths = problems.filter { it.isError }.map { it.path }.toSet()
            assertEquals(setOf("discord.guild-id", "discord.channel-id", "discord.roles.fly", "discord.roles.claim"), paths)
        }

        @Test
        fun `disabled needs nothing`() {
            val (s, problems) = parse("discord:\n  enabled: false\n")
            assertFalse(s.enabled)
            assertEquals(emptyList<ConfigError>(), problems.filter { it.isError })
        }

        @Test
        fun `only connection settings decide a reconnect`() {
            val base = "discord:\n  enabled: true\n  token: '$fakeToken'\n  guild-id: 1\n  channel-id: 2\n"
            val a = parse(base).first
            val b = parse(base + "  avatar-url: 'https://crafatar.com/avatars/<uuid>'\n").first
            val c = parse(base.replace("channel-id: 2", "channel-id: 3")).first
            assertEquals(a.connection, b.connection)
            assertTrue(a.connection != c.connection)
        }

        @Test
        fun `the application id is read from the token without logging in`() {
            assertEquals(123456789012345678, DiscordBot.applicationIdFromToken(fakeToken))
            assertNull(DiscordBot.applicationIdFromToken("!!!.x.y"))
        }
    }

    @Nested
    inner class Actions {
        private fun parse(yaml: String): Pair<ActionsConfig, List<ConfigError>> {
            val problems = ArrayList<ConfigError>()
            val config = YamlConfiguration().apply { loadFromString(yaml) }
            return ActionsConfig.parse(ConfigNode("actions.yml", config, "", problems)) to problems
        }

        @Test
        fun `the bundled actions are valid`() {
            val stream = present(javaClass.classLoader.getResourceAsStream("actions.yml"))
            val (actions, problems) = parse(stream.reader(Charsets.UTF_8).readText())
            assertEquals(emptyList<ConfigError>(), problems)
            assertEquals(listOf("warn", "kick", "tempban-1d", "ban"), actions.templates.map { it.key })
        }

        @Test
        fun `broken templates and prefixes are reported`() {
            val (actions, problems) = parse(
                """
                templates:
                  bad:
                    command: 'ban {player} {nope}'
                  'Bad Key':
                    command: 'kick {player}'
                  fine:
                    command: 'kick {player}'
                allowed-name-prefixes: ['.', '-', 'ab']
                """.trimIndent(),
            )
            assertEquals(listOf("fine"), actions.templates.map { it.key })
            assertEquals(setOf('.'), actions.allowedNamePrefixes)
            val paths = problems.map { it.path }
            assertTrue("actions.yml".let { paths.contains("templates.bad.command") }, paths.toString())
            assertTrue(paths.contains("templates.Bad Key"), paths.toString())
            assertEquals(2, paths.count { it == "allowed-name-prefixes" }, paths.toString())
        }
    }
}
