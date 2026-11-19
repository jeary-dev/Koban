package dev.jeary.koban.domain.evidence

import java.time.Clock
import java.time.Duration
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A fixed-size ring of a player's most recent lines. Thread-safe: chat arrives on async threads
 * while reports read from the main thread.
 */
class ChatRingBuffer(val capacity: Int) {
    private val lines = ArrayDeque<ChatLine>(capacity)

    init {
        require(capacity >= 0) { "capacity must not be negative" }
    }

    @Synchronized
    fun add(line: ChatLine) {
        if (capacity == 0) return
        while (lines.size >= capacity) lines.removeFirst()
        lines.addLast(line)
    }

    /** Up to [limit] of the newest lines, oldest first. */
    @Synchronized
    fun snapshot(limit: Int = capacity): List<ChatLine> =
        lines.toList().takeLast(limit.coerceAtLeast(0))

    @Synchronized
    fun size(): Int = lines.size
}

/**
 * Recent chat (and, when enabled, commands) for every online player, held only in memory.
 *
 * Nothing here is ever written anywhere unless the player is part of a report: the report copies
 * the lines it needs into its evidence. A player's buffer is kept for [retainAfterQuit] after
 * they leave, so someone can still be reported straight after logging off, then dropped.
 */
class ChatHistory(
    private val clock: Clock,
    @Volatile var chatCapacity: Int,
    @Volatile var commandCapacity: Int,
    @Volatile var retainAfterQuit: Duration,
) {
    private class Entry(val chat: ChatRingBuffer, val commands: ChatRingBuffer) {
        @Volatile var name: String = ""

        /** Epoch millis when the player left; null while online. */
        @Volatile var quitAt: Long? = null
    }

    private val entries = ConcurrentHashMap<UUID, Entry>()

    private fun entry(uuid: UUID, name: String): Entry =
        entries.computeIfAbsent(uuid) { Entry(ChatRingBuffer(chatCapacity), ChatRingBuffer(commandCapacity)) }
            .also { it.name = name }

    fun recordChat(uuid: UUID, name: String, text: String) {
        entry(uuid, name).chat.add(ChatLine(clock.millis(), uuid.toString(), name, text))
    }

    /** Callers must have already dropped sensitive commands (see [CommandRedactor]). */
    fun recordCommand(uuid: UUID, name: String, command: String) {
        if (commandCapacity == 0) return
        entry(uuid, name).commands.add(ChatLine(clock.millis(), uuid.toString(), name, command))
    }

    fun joined(uuid: UUID, name: String) {
        entry(uuid, name).quitAt = null
    }

    fun quit(uuid: UUID) {
        entries[uuid]?.quitAt = clock.millis()
    }

    fun chat(uuid: UUID, limit: Int): List<ChatLine> = entries[uuid]?.chat?.snapshot(limit).orEmpty()

    fun commands(uuid: UUID, limit: Int): List<ChatLine> = entries[uuid]?.commands?.snapshot(limit).orEmpty()

    /** Both players' chat interleaved by time, each limited to their own newest [perPlayer] lines. */
    fun conversation(first: UUID, second: UUID?, perPlayer: Int): List<ChatLine> {
        val lines = chat(first, perPlayer) + (second?.let { chat(it, perPlayer) }.orEmpty())
        return lines.sortedBy { it.at }
    }

    /** Players who left within [retainAfterQuit], newest first: they can still be reported. */
    fun recentlyLeft(): List<Pair<UUID, String>> {
        val cutoff = clock.millis() - retainAfterQuit.toMillis()
        return entries.entries
            .mapNotNull { (uuid, e) -> e.quitAt?.takeIf { it >= cutoff }?.let { Triple(uuid, e.name, it) } }
            .sortedByDescending { it.third }
            .map { it.first to it.second }
    }

    fun knows(uuid: UUID): Boolean = entries.containsKey(uuid)

    /** Drops buffers of players who left more than [retainAfterQuit] ago. Run periodically. */
    fun purgeExpired() {
        val cutoff = clock.millis() - retainAfterQuit.toMillis()
        entries.entries.removeIf { (_, e) -> e.quitAt?.let { it < cutoff } ?: false }
    }

    fun trackedPlayers(): Int = entries.size
}
