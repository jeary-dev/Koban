package dev.jeary.koban.menu

import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta

/** A tiny fluent builder for menu icons. Names and lore are already-rendered legacy strings. */
class ItemBuilder(material: Material) {
    private val item = ItemStack(material)
    private var name: String? = null
    private val lore = ArrayList<String>()
    private var glow = false
    private var model: Int? = null
    private var owner: OfflinePlayer? = null

    fun name(legacy: String) = apply { name = legacy }
    fun lore(lines: Collection<String>) = apply { lore += lines }
    fun glow(enabled: Boolean = true) = apply { glow = glow || enabled }
    fun customModelData(value: Int?) = apply { if (value != null) model = value }

    /** For a PLAYER_HEAD: whose face to show. The client fetches the skin itself. */
    fun head(player: OfflinePlayer) = apply { owner = player }

    fun build(): ItemStack {
        val meta = item.itemMeta ?: return item
        name?.let(meta::setDisplayName)
        if (lore.isNotEmpty()) meta.lore = lore
        // The int setter is deprecated since 1.21.4 in favour of CustomModelDataComponent, which
        // doesn't exist on 1.20.6. The int form still works everywhere (it maps to floats[0]).
        @Suppress("DEPRECATION")
        model?.let { meta.setCustomModelData(it) }
        if (glow) meta.setEnchantmentGlintOverride(true)
        owner?.let { (meta as? SkullMeta)?.owningPlayer = it }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS)
        item.itemMeta = meta
        return item
    }
}
