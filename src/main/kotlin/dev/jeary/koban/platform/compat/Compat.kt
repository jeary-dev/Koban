package dev.jeary.koban.platform.compat

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.SoundCategory
import org.bukkit.entity.Player
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/** The server software, detected once at startup from public marker classes. */
enum class Platform(val displayName: String) {
    SPIGOT("Spigot"),
    PAPER("Paper"),
    ;

    companion object {
        private val PAPER_MARKERS = listOf(
            "io.papermc.paper.ServerBuildInfo",
            "com.destroystokyo.paper.PaperConfig",
            "io.papermc.paper.configuration.Configuration",
        )

        fun detect(): Platform = if (PAPER_MARKERS.any(::classExists)) PAPER else SPIGOT

        fun classExists(name: String): Boolean = try {
            Class.forName(name, false, Platform::class.java.classLoader)
            true
        } catch (_: ClassNotFoundException) {
            false
        } catch (_: LinkageError) {
            false
        }
    }
}

/**
 * Everything that differs between 1.20.6, 1.21.x and 26.x, resolved by name at runtime with
 * fallbacks, never by a list of versions:
 *
 * - **Sounds** are played by key through `Player.playSound(Location, String, …)`, which exists on
 *   every version. The `Sound` type itself changed from an enum to a registry interface in
 *   1.21.3, so Koban never references its constants.
 * - **Materials** are looked up with `Material.matchMaterial` from a list of candidates, so an
 *   icon renamed or added in a later version falls back to one that exists.
 * - **Client brand** comes from Paper's public `Player#getClientBrandName()` where it exists;
 *   elsewhere [ClientBrands] reads the `minecraft:brand` channel.
 * - **Inventories** are recognised by their `InventoryHolder`, never through `InventoryView`, which
 *   became an interface in 1.21 (calling it from code compiled against 1.20.6 would crash).
 */
object Compat {

    fun playSound(player: Player, key: String?, volume: Float = 0.7f, pitch: Float = 1.0f, at: Location = player.location) {
        if (key.isNullOrBlank()) return
        player.playSound(at, normaliseKey(key), SoundCategory.MASTER, volume, pitch)
    }

    /** Sound keys as the game names them: `ui.button.click` or `minecraft:ui.button.click`. */
    fun normaliseKey(key: String): String = key.trim().lowercase()

    /** Whether [key] looks like a sound key (config validation; the client ignores unknown ones). */
    fun isSoundKey(key: String): Boolean = SOUND_KEY.matches(normaliseKey(key))

    private val SOUND_KEY = Regex("""^([a-z0-9_.-]+:)?[a-z0-9_.-]+$""")

    /** The first candidate this server knows that can be an item, or [fallback]. */
    fun material(candidates: List<String>, fallback: Material = Material.PAPER): Material =
        candidates.firstNotNullOfOrNull { name -> Material.matchMaterial(name.trim())?.takeIf { it.isItem && !it.isAir } } ?: fallback

    /** Paper's `Player#getClientBrandName()`, or null on Spigot. Public API, looked up once. */
    private val paperBrand: MethodHandle? = try {
        MethodHandles.publicLookup().findVirtual(Player::class.java, "getClientBrandName", MethodType.methodType(String::class.java))
    } catch (_: NoSuchMethodException) {
        null
    } catch (_: IllegalAccessException) {
        null
    }

    fun paperClientBrand(player: Player): String? = paperBrand?.let {
        try {
            it.invoke(player) as String?
        } catch (_: Throwable) {
            null
        }
    }

    val hasPaperClientBrand: Boolean get() = paperBrand != null
}
