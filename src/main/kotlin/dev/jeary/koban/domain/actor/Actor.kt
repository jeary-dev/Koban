package dev.jeary.koban.domain.actor

import java.util.UUID

/** Where an action came from. Recorded on every audit entry. */
enum class Source { GAME, DISCORD, CONSOLE, API }

/**
 * A member of staff, known by a Minecraft identity, a Discord identity, or both once the accounts
 * are linked. [name] is what people see: the Minecraft name when there is one.
 */
data class StaffRef(
    val name: String,
    val minecraft: UUID? = null,
    val discord: Long? = null,
) {
    init {
        require(minecraft != null || discord != null) { "A staff reference needs a Minecraft or Discord identity" }
    }

    /** The same person: they share a Minecraft UUID or a Discord ID. */
    fun sameAs(other: StaffRef): Boolean =
        (minecraft != null && minecraft == other.minecraft) || (discord != null && discord == other.discord)
}

/** Who did something, and from where. */
data class Actor(val source: Source, val staff: StaffRef?, val displayName: String) {

    companion object {
        val CONSOLE = Actor(Source.CONSOLE, null, "Console")

        fun player(uuid: UUID, name: String, discord: Long? = null) =
            Actor(Source.GAME, StaffRef(name, uuid, discord), name)

        fun discord(id: Long, displayName: String, linkedMinecraft: UUID? = null, linkedName: String? = null) =
            Actor(Source.DISCORD, StaffRef(linkedName ?: displayName, linkedMinecraft, id), linkedName ?: displayName)

        fun api(pluginName: String) = Actor(Source.API, null, pluginName)
    }
}
