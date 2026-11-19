package dev.jeary.koban.platform

import dev.jeary.koban.config.ConfigError
import dev.jeary.koban.config.ConfigNode
import dev.jeary.koban.config.CoreSettings
import dev.jeary.koban.config.Messages
import dev.jeary.koban.domain.time.DurationText
import dev.jeary.koban.platform.evidence.ClientBrands
import dev.jeary.koban.platform.text.BungeeTextSink
import net.kyori.adventure.text.minimessage.MiniMessage
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.HoverEvent
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.InputStreamReader
import java.time.Duration

class PlatformPiecesTest {

    @Nested
    inner class Durations {
        @Test
        fun `short durations read naturally and never undersell a cooldown`() {
            assertEquals("0s", DurationText.short(Duration.ZERO))
            assertEquals("1s", DurationText.short(Duration.ofMillis(1)))
            assertEquals("45s", DurationText.short(Duration.ofSeconds(45)))
            assertEquals("59s", DurationText.short(Duration.ofSeconds(59)))
            assertEquals("1m", DurationText.short(Duration.ofMillis(59_200)))
            assertEquals("2m 5s", DurationText.short(Duration.ofSeconds(125)))
            assertEquals("1h 30m", DurationText.short(Duration.ofMinutes(90)))
            assertEquals("1h 1m", DurationText.short(Duration.ofSeconds(3_601)))
            assertEquals("3d 4h", DurationText.short(Duration.ofHours(76)))
            assertEquals("0s", DurationText.short(Duration.ofSeconds(-5)))
        }
    }

    @Nested
    inner class Brands {
        private fun mcString(text: String): ByteArray {
            val bytes = text.toByteArray(Charsets.UTF_8)
            require(bytes.size < 128)
            return byteArrayOf(bytes.size.toByte()) + bytes
        }

        @Test
        fun `a Minecraft string decodes`() {
            assertEquals("vanilla", ClientBrands.decode(mcString("vanilla")))
            assertEquals("fabric", ClientBrands.decode(mcString("fabric")))
        }

        @Test
        fun `a two-byte length decodes`() {
            val text = "x".repeat(200)
            // 200 = 0b1_1001000 -> 0xC8 0x01
            val bytes = byteArrayOf(0xC8.toByte(), 0x01) + text.toByteArray()
            assertEquals("x".repeat(ClientBrands.MAX_LENGTH), ClientBrands.decode(bytes))
        }

        @Test
        fun `malformed payloads are refused instead of throwing`() {
            assertNull(ClientBrands.decode(byteArrayOf()))
            assertNull(ClientBrands.decode(byteArrayOf(10, 'a'.code.toByte())))
            assertNull(ClientBrands.decode(byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x01)))
        }

        @Test
        fun `colour codes and control characters are stripped`() {
            assertEquals("lunar", ClientBrands.decode(mcString("§clunar\n")))
            assertNull(ClientBrands.decode(mcString("§")))
        }
    }

    @Nested
    inner class Bungee {
        @Test
        fun `colours, decorations, click and hover survive the conversion`() {
            val component = MiniMessage.miniMessage().deserialize(
                "<#f3d1d9><b>bold</b> <click:run_command:'/reports view 7'><hover:show_text:'<#e8a5b4>open'>link</hover></click>",
            )
            val converted = BungeeTextSink.convert(component)
            val all = flatten(converted)

            val bold = all.first { (it as? TextComponent)?.text == "bold" }
            assertEquals(true, bold.isBold)
            assertEquals("#f3d1d9", bold.color.name.lowercase())

            val link = all.first { (it as? TextComponent)?.text == "link" }
            assertEquals(ClickEvent.Action.RUN_COMMAND, link.clickEvent.action)
            assertEquals("/reports view 7", link.clickEvent.value)
            assertEquals(HoverEvent.Action.SHOW_TEXT, link.hoverEvent.action)
            assertEquals("bold link", converted.toPlainText())
        }

        private fun flatten(c: net.md_5.bungee.api.chat.BaseComponent): List<net.md_5.bungee.api.chat.BaseComponent> =
            listOf(c) + (c.extra ?: emptyList()).flatMap(::flatten)
    }

    @Nested
    inner class BundledConfig {
        private fun yaml(name: String): YamlConfiguration {
            val stream = requireNotNull(javaClass.classLoader.getResourceAsStream(name)) { "$name isn't bundled" }
            return InputStreamReader(stream, Charsets.UTF_8).use { YamlConfiguration.loadConfiguration(it) }
        }

        /**
         * reasons.yml and menus.yml resolve item materials, which needs a running server
         * (`Material.isItem` reads the registry); they're checked by the server smoke tests,
         * where any problem stops Koban from enabling.
         */
        @Test
        fun `the bundled files load without a single problem`() {
            val problems = ArrayList<ConfigError>()
            val core = CoreSettings.parse(ConfigNode("config.yml", yaml("config.yml"), "", problems))
            val messagesYaml = yaml("messages.yml")
            val messages = Messages.parse(ConfigNode("messages.yml", messagesYaml, "", problems), messagesYaml, Messages.flatten(messagesYaml))

            assertEquals(emptyList<ConfigError>(), problems)
            assertEquals(Duration.ofSeconds(60), core.reports.antiAbuse.cooldown)
            assertEquals(false, core.evidence.commandsEnabled)
            for (key in messages.keys) {
                MiniMessage.miniMessage().deserialize(messages.raw(key).replace("<prefix>", ""))
            }
        }

        @Test
        fun `a bad value names the file, the setting and the problem`() {
            val problems = ArrayList<ConfigError>()
            val config = yaml("config.yml").apply {
                set("evidence.chat.lines", 40)
                set("notifications.alert-sound", "not a sound!")
                set("server-name", "")
            }
            CoreSettings.parse(ConfigNode("config.yml", config, "", problems))
            val text = problems.map { it.toString() }
            assertTrue(text.any { it.startsWith("config.yml → evidence.chat.lines:") }, text.toString())
            assertTrue(text.any { it.startsWith("config.yml → notifications.alert-sound:") }, text.toString())
            assertTrue(text.any { it.startsWith("config.yml → server-name:") }, text.toString())
        }
    }
}
