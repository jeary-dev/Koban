package dev.jeary.koban.menu

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import java.util.UUID

/**
 * The one listener behind every Koban menu. It cancels all item movement in our inventories
 * (including shift-clicks and drags from the player's own inventory) and forwards top-inventory
 * clicks to the owning [Menu]. It also tracks open menus so a reload can close them safely.
 *
 * Clicks run one tick later: Bukkit forbids closing or opening inventories inside the click
 * event itself. A click that arrives after its menu closed (a fast double click) is dropped.
 * Menus are recognised by their holder, never through `InventoryView`, which changed from a
 * class to an interface in 1.21 and can't be called from one jar on both.
 */
class MenuListener(private val plugin: Plugin) : Listener {

    private val open = HashMap<UUID, Menu>()

    /** Every menu currently open, for closing them all on reload/disable. */
    fun openMenus(): Collection<Menu> = open.values.toList()

    /** The menu [viewer] has open, if it's one of ours. */
    fun menuOf(viewer: UUID): Menu? = open[viewer]

    fun closeAll() {
        openMenus().forEach { it.viewer.closeInventory() }
        open.clear()
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onClick(event: InventoryClickEvent) {
        val menu = event.inventory.holder as? Menu ?: return
        event.isCancelled = true
        val topSize = event.inventory.size
        val slot = event.rawSlot
        if (slot !in 0 until topSize) return
        val type = event.click
        plugin.server.scheduler.runTask(plugin, Runnable {
            if (open[menu.viewer.uniqueId] === menu) menu.click(slot, type)
        })
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDrag(event: InventoryDragEvent) {
        if (event.inventory.holder is Menu) event.isCancelled = true
    }

    @EventHandler
    fun onOpen(event: InventoryOpenEvent) {
        val menu = event.inventory.holder as? Menu ?: return
        open[menu.viewer.uniqueId] = menu
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        val menu = event.inventory.holder as? Menu ?: return
        if (open[menu.viewer.uniqueId] === menu) open.remove(menu.viewer.uniqueId)
        menu.onClose()
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        open.remove(event.player.uniqueId)
    }
}
