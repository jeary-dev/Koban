package dev.jeary.koban.domain.evidence

import kotlinx.serialization.Serializable

/**
 * Everything captured at the moment of a report, stored once per reporter as JSON.
 * Field names are part of the stored format: add fields with defaults, never rename them.
 */
@Serializable
data class EvidenceSnapshot(
    val format: Int = FORMAT,
    val capturedAt: Long,
    val server: String,
    /** Server ticks per second, measured by Koban over the last minute; null before a minute has passed. */
    val tps: Double? = null,
    val reported: PlayerState,
    val reporter: PlayerState? = null,
    /** Players near the reported player (vanished staff excluded), nearest first. */
    val nearby: List<NearbyPlayer> = emptyList(),
    /** Chat from both players, oldest first. */
    val chat: List<ChatLine> = emptyList(),
    /** The reported player's recent commands, if enabled. Sensitive commands are never included. */
    val commands: List<ChatLine> = emptyList(),
) {
    companion object {
        const val FORMAT = 1
    }
}

/** A player's state when the report was made. Location fields are null when they were offline. */
@Serializable
data class PlayerState(
    val uuid: String,
    val name: String,
    val online: Boolean,
    val world: String? = null,
    val x: Double? = null,
    val y: Double? = null,
    val z: Double? = null,
    val gameMode: String? = null,
    val health: Double? = null,
    val maxHealth: Double? = null,
    val ping: Int? = null,
    val clientBrand: String? = null,
)

@Serializable
data class NearbyPlayer(val name: String, val distance: Double)

@Serializable
data class ChatLine(
    val at: Long,
    val uuid: String,
    val name: String,
    val text: String,
)
