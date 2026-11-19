package dev.jeary.koban.game

import dev.jeary.koban.KobanCore
import dev.jeary.koban.command.Permissions
import dev.jeary.koban.domain.report.PlayerRef
import kotlinx.coroutines.delay
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration

/**
 * On join: remember the name (for `/reports history` and Discord lookups of offline players),
 * hand over report feedback the player missed, and show staff what's waiting.
 */
class PlayerSessions(private val core: KobanCore) : Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        val notifier = core.notifier
        notifier.launch("record ${player.name} in the player directory") {
            core.directory.seen(PlayerRef(player.uniqueId, player.name))
        }
        notifier.launch("deliver report feedback to ${player.name}") {
            // After the join messages, so it isn't lost among them.
            delay(FEEDBACK_DELAY)
            if (player.isOnline) notifier.deliverFeedback(player)
        }
        val notifications = core.config.core.notifications
        if (notifications.joinSummary && player.hasPermission(Permissions.NOTIFY)) {
            notifier.launch("show ${player.name} the open reports") {
                delay(notifications.joinSummaryDelay.toKotlinDuration())
                if (player.isOnline) notifier.joinSummary(player)
            }
        }
    }

    private companion object {
        val FEEDBACK_DELAY = 2.seconds
    }
}
