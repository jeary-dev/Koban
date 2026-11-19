package dev.jeary.koban.hook

import org.bukkit.entity.Player

/**
 * Whether a player is vanished, by the convention every major vanish plugin follows
 * (SuperVanish, PremiumVanish, EssentialsX, CMI, VanishNoPacket): a `vanished` metadata value
 * that is true. No dependency on any of them is needed.
 */
object VanishHook {
    fun isVanished(player: Player): Boolean =
        player.getMetadata("vanished").any { it.asBoolean() }

    /** Whether [viewer] should know [target] is online: not vanished, or the viewer can see them. */
    fun isVisibleTo(target: Player, viewer: Player?): Boolean =
        !isVanished(target) || (viewer != null && viewer.canSee(target))
}
