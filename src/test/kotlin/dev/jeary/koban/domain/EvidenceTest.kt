package dev.jeary.koban.domain

import dev.jeary.koban.domain.evidence.ChatHistory
import dev.jeary.koban.domain.evidence.ChatLine
import dev.jeary.koban.domain.evidence.ChatRingBuffer
import dev.jeary.koban.domain.evidence.CommandRedactor
import dev.jeary.koban.domain.report.ReportText
import dev.jeary.koban.support.MutableClock
import dev.jeary.koban.support.Players
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class EvidenceTest {

    @Nested
    inner class RingBuffer {
        private fun line(n: Int) = ChatLine(n.toLong(), "u", "Steve", "message $n")

        @Test
        fun `keeps only the newest lines, oldest first`() {
            val buffer = ChatRingBuffer(3)
            (1..5).forEach { buffer.add(line(it)) }
            assertEquals(listOf("message 3", "message 4", "message 5"), buffer.snapshot().map { it.text })
            assertEquals(listOf("message 4", "message 5"), buffer.snapshot(2).map { it.text })
        }

        @Test
        fun `a zero-size buffer keeps nothing`() {
            val buffer = ChatRingBuffer(0)
            buffer.add(line(1))
            assertEquals(0, buffer.size())
        }

        @Test
        fun `concurrent writers never break the bound`() {
            val buffer = ChatRingBuffer(50)
            val pool = Executors.newFixedThreadPool(8)
            repeat(8) { t -> pool.execute { repeat(1_000) { buffer.add(line(t * 1_000 + it)) } } }
            pool.shutdown()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
            assertEquals(50, buffer.size())
        }
    }

    @Nested
    inner class History {
        private val clock = MutableClock()
        private val history = ChatHistory(clock, chatCapacity = 3, commandCapacity = 2, retainAfterQuit = Duration.ofMinutes(5))

        @Test
        fun `a conversation interleaves both players by time, each capped separately`() {
            history.recordChat(Players.notch.uuid, "Notch", "n1")
            clock.advance(Duration.ofSeconds(1))
            history.recordChat(Players.steve.uuid, "Steve", "s1")
            clock.advance(Duration.ofSeconds(1))
            history.recordChat(Players.notch.uuid, "Notch", "n2")
            clock.advance(Duration.ofSeconds(1))
            history.recordChat(Players.notch.uuid, "Notch", "n3")
            assertEquals(listOf("s1", "n2", "n3"), history.conversation(Players.notch.uuid, Players.steve.uuid, perPlayer = 2).map { it.text })
        }

        @Test
        fun `a player who left can still be reported for five minutes, then is forgotten`() {
            history.recordChat(Players.notch.uuid, "Notch", "bye")
            history.quit(Players.notch.uuid)
            clock.advance(Duration.ofMinutes(4))
            history.purgeExpired()
            assertEquals(listOf(Players.notch.uuid to "Notch"), history.recentlyLeft())
            assertEquals(listOf("bye"), history.chat(Players.notch.uuid, 10).map { it.text })

            clock.advance(Duration.ofMinutes(2))
            history.purgeExpired()
            assertEquals(emptyList<Any>(), history.recentlyLeft())
            assertEquals(emptyList<ChatLine>(), history.chat(Players.notch.uuid, 10))
            assertFalse(history.knows(Players.notch.uuid))
        }

        @Test
        fun `rejoining cancels the countdown`() {
            history.recordChat(Players.notch.uuid, "Notch", "brb")
            history.quit(Players.notch.uuid)
            history.joined(Players.notch.uuid, "Notch")
            clock.advance(Duration.ofHours(1))
            history.purgeExpired()
            assertTrue(history.knows(Players.notch.uuid))
        }

        @Test
        fun `commands are kept separately from chat`() {
            history.recordCommand(Players.notch.uuid, "Notch", "/gamemode creative")
            history.recordChat(Players.notch.uuid, "Notch", "hi")
            assertEquals(listOf("/gamemode creative"), history.commands(Players.notch.uuid, 5).map { it.text })
            assertEquals(listOf("hi"), history.chat(Players.notch.uuid, 5).map { it.text })
        }

        @Test
        fun `with command capture off nothing is buffered`() {
            val off = ChatHistory(clock, chatCapacity = 3, commandCapacity = 0, retainAfterQuit = Duration.ofMinutes(5))
            off.recordCommand(Players.notch.uuid, "Notch", "/spawn")
            assertFalse(off.knows(Players.notch.uuid))
        }
    }

    @Nested
    inner class Redaction {
        private val redactor = CommandRedactor(extra = listOf("/secretcmd", "MyPlugin:Vault"))

        @ParameterizedTest
        @ValueSource(
            strings = [
                "/login hunter2", "/LOGIN hunter2", "/l hunter2", "/authme:login hunter2", "/AuthMe:Login hunter2",
                "/register pw pw", "/reg pw pw", "/changepassword old new", "/cp old new", "/2fa 123456",
                "/authme register Steve pw", "/nlogin:changepass a b", "  /login spaced", "login no-slash",
                "/secretcmd x", "/vault open", "/myplugin:vault open",
            ],
        )
        fun `sensitive commands are never recorded`(command: String) {
            assertFalse(redactor.mayRecord(command), command)
        }

        @ParameterizedTest
        @ValueSource(strings = ["/gamemode creative", "/tp Steve", "/fly", "/logs", "/loginfo", "/essentials:give Notch diamond 64"])
        fun `ordinary commands are recorded`(command: String) {
            assertTrue(redactor.mayRecord(command), command)
        }

        @Test
        fun `config can add to the blocklist but never remove from it`() {
            val plain = CommandRedactor()
            assertTrue(CommandRedactor.ALWAYS_SENSITIVE.all { it in plain.sensitive })
            assertFalse(plain.mayRecord("/login x"))
            assertFalse(plain.mayRecord("/"))
            assertFalse(plain.mayRecord(""))
        }
    }

    @Nested
    inner class Text {
        @Test
        fun `typed text loses control characters, colour codes and invisible characters`() {
            assertEquals("flying near spawn", ReportText.clean("  flying\n\tnear §cspawn\u200B ", 100))
            assertEquals("ab", ReportText.clean("a\u202Eb", 100)) // a right-to-left override can't reorder the text
            assertNull(ReportText.clean(" \n\t ", 100))
            assertNull(ReportText.clean(null, 100))
        }

        @Test
        fun `long text is cut with an ellipsis`() {
            val cut = ReportText.clean("x".repeat(300), 10)
            assertEquals(10, cut?.length)
            assertTrue(cut?.endsWith("…") == true)
        }

        @Test
        fun `cutting never splits an emoji`() {
            // The emoji is two UTF-16 chars; cutting at 9 would leave half of one.
            assertEquals("abcdefgh…", ReportText.clean("abcdefgh\uD83C\uDF38\uD83C\uDF38", 10))
            assertEquals("abcdefg\uD83C\uDF38…", ReportText.clean("abcdefg\uD83C\uDF38\uD83C\uDF38", 10))
        }
    }
}
