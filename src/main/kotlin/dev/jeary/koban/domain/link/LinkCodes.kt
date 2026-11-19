package dev.jeary.koban.domain.link

import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Short-lived codes that link a Minecraft account to a Discord account: staff run
 * `/koban link` in game, then `/link <code>` in Discord.
 *
 * Codes are 6 characters from an alphabet without look-alikes (no 0/O, 1/I/L), so they're easy
 * to type; that's ~30 bits, which together with single use, the expiry and the limit of one
 * live code per player makes guessing pointless. They live in memory only: the bot runs in this
 * process, so nothing else needs them.
 */
class LinkCodes(
    private val clock: Clock,
    private val ttl: Duration = Duration.ofMinutes(10),
    private val random: SecureRandom = SecureRandom(),
) {
    data class Pending(val minecraft: UUID, val name: String, val expiresAt: Long)

    private val byCode = ConcurrentHashMap<String, Pending>()

    /** A fresh code for [minecraft], replacing any code they already had. */
    fun issue(minecraft: UUID, name: String): String {
        byCode.entries.removeIf { it.value.minecraft == minecraft }
        while (true) {
            val code = (1..LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
            val pending = Pending(minecraft, name, clock.millis() + ttl.toMillis())
            if (byCode.putIfAbsent(code, pending) == null) return code
        }
    }

    /** Uses up [code]: returns who it belongs to, or null if it's unknown or expired. */
    fun redeem(code: String): Pending? {
        val normalised = code.trim().uppercase()
        val pending = byCode.remove(normalised) ?: return null
        return pending.takeIf { it.expiresAt >= clock.millis() }
    }

    fun purgeExpired() {
        val now = clock.millis()
        byCode.entries.removeIf { it.value.expiresAt < now }
    }

    val ttlMinutes: Long get() = ttl.toMinutes()

    companion object {
        const val LENGTH = 6
        const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    }
}
