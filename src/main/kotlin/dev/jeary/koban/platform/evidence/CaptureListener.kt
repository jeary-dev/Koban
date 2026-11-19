package dev.jeary.koban.platform.evidence

import dev.jeary.koban.config.EvidenceSettings
import dev.jeary.koban.domain.evidence.ChatHistory
import dev.jeary.koban.domain.report.ReportText
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

/**
 * Feeds [ChatHistory]: chat as it was actually sent (MONITOR, ignoring cancelled messages, so
 * muted lines and answers to Koban's own prompts are never recorded), and commands only when
 * enabled and only after the redactor has dropped anything sensitive.
 *
 * Everything stays in memory; nothing reaches the database unless a report copies it.
 */
class CaptureListener(
    private val history: ChatHistory,
    private val settings: () -> EvidenceSettings,
) : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onChat(event: AsyncPlayerChatEvent) {
        val player = event.player
        history.recordChat(player.uniqueId, player.name, ReportText.clean(event.message, MAX_LINE) ?: return)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        val config = settings()
        if (!config.commandsEnabled) return
        val line = event.message.removePrefix("/")
        if (!config.redactor.mayRecord(line)) return
        val player = event.player
        history.recordCommand(player.uniqueId, player.name, ReportText.clean("/$line", MAX_LINE) ?: return)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        history.joined(event.player.uniqueId, event.player.name)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        history.quit(event.player.uniqueId)
    }

    private companion object {
        /** Chat lines are 256 characters at most in vanilla; commands can be longer. */
        const val MAX_LINE = 300
    }
}
