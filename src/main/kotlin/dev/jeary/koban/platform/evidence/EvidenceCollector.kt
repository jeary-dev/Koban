package dev.jeary.koban.platform.evidence

import dev.jeary.koban.config.EvidenceSettings
import dev.jeary.koban.domain.evidence.ChatHistory
import dev.jeary.koban.domain.evidence.EvidenceSnapshot
import dev.jeary.koban.domain.evidence.NearbyPlayer
import dev.jeary.koban.domain.evidence.PlayerState
import dev.jeary.koban.domain.report.BlockPosition
import dev.jeary.koban.domain.report.PlayerRef
import dev.jeary.koban.hook.VanishHook
import org.bukkit.Bukkit
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import java.time.Clock

/** Who is being reported: someone online, or someone who left a moment ago. */
sealed interface ReportTarget {
    val ref: PlayerRef

    data class Online(val player: Player) : ReportTarget {
        override val ref: PlayerRef get() = PlayerRef(player.uniqueId, player.name)
    }

    data class RecentlyLeft(override val ref: PlayerRef) : ReportTarget
}

/** Evidence plus the block position staff can teleport to. */
data class CapturedEvidence(val snapshot: EvidenceSnapshot, val location: BlockPosition?)

/**
 * Captures the evidence for a report at the moment it's made. Main thread only (it reads
 * players, worlds and locations); everything it returns is plain data, safe to store anywhere.
 */
class EvidenceCollector(
    private val history: ChatHistory,
    private val tps: TpsSampler,
    private val brands: ClientBrands,
    private val clock: Clock,
    private val settings: () -> EvidenceSettings,
    private val serverName: () -> String,
) {

    fun capture(reporter: Player, target: ReportTarget): CapturedEvidence {
        val config = settings()
        val reported = (target as? ReportTarget.Online)?.player
        val anchor = reported ?: reporter
        val location = anchor.location.let { loc ->
            loc.world?.let { BlockPosition(it.name, loc.blockX, loc.blockY, loc.blockZ) }
        }
        val snapshot = EvidenceSnapshot(
            capturedAt = clock.millis(),
            server = serverName(),
            tps = tps.tps()?.let { Math.round(it * 10) / 10.0 },
            reported = reported?.let(::state) ?: PlayerState(target.ref.uuid.toString(), target.ref.name, online = false),
            reporter = state(reporter),
            nearby = nearby(anchor, setOf(reporter.uniqueId, target.ref.uuid), config),
            chat = history.conversation(target.ref.uuid, reporter.uniqueId, config.chatLines),
            commands = if (config.commandsEnabled) history.commands(target.ref.uuid, config.commandLines) else emptyList(),
        )
        return CapturedEvidence(snapshot, location)
    }

    private fun state(player: Player): PlayerState {
        val loc = player.location
        return PlayerState(
            uuid = player.uniqueId.toString(),
            name = player.name,
            online = true,
            world = loc.world?.name,
            x = round1(loc.x),
            y = round1(loc.y),
            z = round1(loc.z),
            gameMode = player.gameMode.name,
            health = round1(player.health),
            maxHealth = maxHealth(player)?.let(::round1),
            ping = player.ping,
            clientBrand = brands.brandOf(player),
        )
    }

    private fun nearby(anchor: Player, exclude: Set<java.util.UUID>, config: EvidenceSettings): List<NearbyPlayer> {
        if (config.maxNearby == 0 || config.nearbyRadius <= 0.0) return emptyList()
        val world = anchor.world
        val origin = anchor.location
        val radiusSquared = config.nearbyRadius * config.nearbyRadius
        return world.players.asSequence()
            .filter { it.uniqueId !in exclude && !VanishHook.isVanished(it) }
            .map { it to it.location.distanceSquared(origin) }
            .filter { it.second <= radiusSquared }
            .sortedBy { it.second }
            .take(config.maxNearby)
            .map { (player, d2) -> NearbyPlayer(player.name, round1(Math.sqrt(d2))) }
            .toList()
    }

    private fun round1(value: Double): Double = Math.round(value * 10) / 10.0

    private companion object {
        /**
         * The max-health attribute was renamed in 1.21.3 (GENERIC_MAX_HEALTH → MAX_HEALTH) and
         * `Attribute` became a registry type, so it's looked up by key, which never changed.
         */
        fun maxHealth(player: Player): Double? {
            val key = org.bukkit.NamespacedKey.minecraft("max_health")
            val attribute = Bukkit.getRegistry(Attribute::class.java)?.get(key)
                ?: Bukkit.getRegistry(Attribute::class.java)?.get(org.bukkit.NamespacedKey.minecraft("generic.max_health"))
                ?: return null
            return player.getAttribute(attribute)?.value
        }
    }
}
