package dev.jeary.koban.platform.evidence

import dev.jeary.koban.platform.compat.Compat
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.messaging.PluginMessageListener
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Measures ticks per second the same way on Spigot and Paper (Spigot's API has no TPS): a task
 * runs every tick and records when, and TPS is the number of ticks in the last [WINDOW_SECONDS]
 * divided by the time they took. Main thread only.
 */
class TpsSampler(private val plugin: Plugin) {

    private val stamps = LongArray(WINDOW_SECONDS * 20 + 1)
    private var count = 0
    private var head = 0
    private var task: BukkitTask? = null

    fun start() {
        task = plugin.server.scheduler.runTaskTimer(plugin, Runnable { record(System.nanoTime()) }, 1L, 1L)
    }

    fun stop() {
        task?.cancel()
        task = null
    }

    internal fun record(now: Long) {
        stamps[head] = now
        head = (head + 1) % stamps.size
        if (count < stamps.size) count++
    }

    /** Ticks per second over the last minute (capped at 20), or null until a minute has passed. */
    fun tps(): Double? {
        if (count < stamps.size) return null
        val newest = stamps[(head - 1 + stamps.size) % stamps.size]
        val oldest = stamps[head]
        val seconds = (newest - oldest) / 1_000_000_000.0
        if (seconds <= 0) return null
        return ((stamps.size - 1) / seconds).coerceAtMost(20.0)
    }

    private companion object {
        const val WINDOW_SECONDS = 60
    }
}

/**
 * The client brand players report ("vanilla", "fabric", "lunarclient:…"). Paper exposes it as
 * public API; on Spigot it's read from the `minecraft:brand` plugin channel, which every client
 * sends once on join. A string of at most [MAX_LENGTH] printable characters, or null.
 */
class ClientBrands(private val plugin: Plugin) : PluginMessageListener, Listener {

    private val brands = ConcurrentHashMap<UUID, String>()
    private var listening = false

    fun start() {
        if (Compat.hasPaperClientBrand) return
        try {
            plugin.server.messenger.registerIncomingPluginChannel(plugin, CHANNEL, this)
            listening = true
        } catch (e: IllegalArgumentException) {
            plugin.logger.info("Client brands aren't available on this server (${e.message}); evidence will leave them out.")
        }
    }

    fun stop() {
        if (listening) plugin.server.messenger.unregisterIncomingPluginChannel(plugin, CHANNEL, this)
        listening = false
        brands.clear()
    }

    fun brandOf(player: Player): String? = (Compat.paperClientBrand(player) ?: brands[player.uniqueId])?.let(::clean)

    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (channel != CHANNEL) return
        decode(message)?.let { brands[player.uniqueId] = it }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        brands.remove(event.player.uniqueId)
    }

    companion object {
        const val CHANNEL = "minecraft:brand"
        const val MAX_LENGTH = 64

        /** A Minecraft string: a VarInt byte length, then UTF-8. Null if malformed. */
        fun decode(bytes: ByteArray): String? {
            var length = 0
            var shift = 0
            var i = 0
            while (true) {
                if (i >= bytes.size || shift > 28) return null
                val b = bytes[i++].toInt()
                length = length or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            if (length < 0 || i + length > bytes.size) return null
            return clean(String(bytes, i, length, Charsets.UTF_8))
        }

        /** Drops colour codes (the § and its code letter) and control characters. */
        fun clean(raw: String): String? =
            raw.replace(COLOUR_CODE, "").filter { !it.isISOControl() }.trim().take(MAX_LENGTH).takeIf { it.isNotEmpty() }

        private val COLOUR_CODE = Regex("§.?")
    }
}
