package dev.jeary.koban.menu

import dev.jeary.koban.platform.Messenger
import dev.jeary.koban.platform.Messenger.Companion.tag
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Asks a player to type one line in chat (report details, a note) and hands the answer back on
 * the main thread. Inventories can't take text input on every version, so the menu closes, the
 * player answers in chat, and the caller carries on.
 *
 * The answer never reaches public chat and is never recorded as chat evidence. `cancel` gives
 * up; `skip` answers with nothing (for optional questions); so does waiting past the timeout
 * when the question is optional.
 */
class ChatPrompts(private val plugin: Plugin, private val messenger: () -> Messenger) : Listener {

    sealed interface Answer {
        data class Text(val value: String) : Answer
        data object Skipped : Answer
        data object Cancelled : Answer
    }

    private class Pending(val optional: Boolean, val onAnswer: (Answer) -> Unit)

    private val pending = ConcurrentHashMap<UUID, Pending>()

    fun ask(
        player: Player,
        messageKey: String,
        vararg tags: TagResolver,
        timeout: Duration,
        optional: Boolean,
        onAnswer: (Answer) -> Unit,
    ) {
        val prompt = Pending(optional, onAnswer)
        pending.put(player.uniqueId, prompt)?.onAnswer?.invoke(Answer.Cancelled) // a newer question replaces an unanswered one
        player.closeInventory()
        val m = messenger()
        m.send(player, messageKey, *tags)
        m.send(player, if (optional) "prompt.hint-optional" else "prompt.hint", tag("seconds", timeout.seconds))
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (pending.remove(player.uniqueId, prompt)) {
                if (optional) {
                    prompt.onAnswer(Answer.Skipped)
                } else {
                    if (player.isOnline) messenger().send(player, "prompt.timeout")
                    prompt.onAnswer(Answer.Cancelled)
                }
            }
        }, timeout.seconds * 20L)
    }

    fun isWaiting(player: Player) = pending.containsKey(player.uniqueId)

    @EventHandler(priority = EventPriority.LOWEST)
    fun onChat(event: AsyncPlayerChatEvent) {
        val prompt = pending.remove(event.player.uniqueId) ?: return
        event.isCancelled = true
        val text = event.message.trim()
        val answer = when {
            text.equals("cancel", ignoreCase = true) -> Answer.Cancelled
            prompt.optional && text.equals("skip", ignoreCase = true) -> Answer.Skipped
            text.isEmpty() -> if (prompt.optional) Answer.Skipped else Answer.Cancelled
            else -> Answer.Text(text)
        }
        plugin.server.scheduler.runTask(plugin, Runnable {
            if (answer == Answer.Cancelled) messenger().send(event.player, "prompt.cancelled")
            prompt.onAnswer(answer)
        })
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        pending.remove(event.player.uniqueId)?.onAnswer?.invoke(Answer.Cancelled)
    }
}
