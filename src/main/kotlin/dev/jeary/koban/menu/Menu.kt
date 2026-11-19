package dev.jeary.koban.menu

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

/** A clickable slot: what it shows, and what happens on click. */
class MenuButton(
    val icon: ItemStack,
    val onClick: (ClickType) -> Unit = {},
)

/**
 * A chest GUI owned by one player. Subclasses only describe *what* is in the menu by
 * implementing [render]; inventory creation, click routing and refreshing live here.
 *
 * The menu is its own [InventoryHolder], which is how [MenuListener] recognises our inventories
 * without comparing titles (titles are configurable, so they can't be trusted).
 */
abstract class Menu(val viewer: Player) : InventoryHolder {

    private var inventory: Inventory? = null
    private val buttons = HashMap<Int, MenuButton>()

    /** Title as a legacy string (Bukkit inventories take strings on Spigot). */
    protected abstract fun title(): String

    /** Inventory size, a multiple of 9 up to 54. */
    protected abstract val size: Int

    /** Fill the menu by calling [set]. Called on open and on every [refresh]. */
    protected abstract fun render()

    protected fun set(slot: Int, button: MenuButton) {
        if (slot in 0 until size) buttons[slot] = button
    }

    protected fun set(slot: Int, icon: ItemStack, onClick: (ClickType) -> Unit = {}) = set(slot, MenuButton(icon, onClick))

    /** Opens (or re-opens, e.g. after a title change) this menu for its viewer. */
    open fun open() {
        val created = Bukkit.createInventory(this, size, title())
        inventory = created
        draw(created)
        viewer.openInventory(created)
    }

    /** Redraws the items in place, without reopening. */
    fun refresh() {
        inventory?.let(::draw)
    }

    private fun draw(target: Inventory) {
        buttons.clear()
        render()
        target.clear()
        buttons.forEach { (slot, button) -> target.setItem(slot, button.icon) }
    }

    /** Called by [MenuListener] for clicks in the top inventory. */
    internal fun click(slot: Int, type: ClickType) {
        buttons[slot]?.onClick?.invoke(type)
    }

    /** Called when the viewer closes the menu. */
    open fun onClose() {}

    override fun getInventory(): Inventory =
        inventory ?: Bukkit.createInventory(this, size, title()).also { inventory = it }
}

/**
 * A menu that lays a list of [T] across its content slots, a page at a time.
 */
abstract class PaginatedMenu<T>(viewer: Player) : Menu(viewer) {

    /** Zero-based current page. */
    var page: Int = 0
        private set

    protected abstract val contentSlots: List<Int>

    protected abstract fun entries(): List<T>

    protected abstract fun button(entry: T): MenuButton

    val pageCount: Int get() = maxOf(1, (entries().size + contentSlots.size - 1) / maxOf(1, contentSlots.size))

    val hasPrevious: Boolean get() = page > 0
    val hasNext: Boolean get() = page < pageCount - 1

    /** Draws the current page of entries; call from [render]. */
    protected fun renderPage() {
        val all = entries()
        val perPage = contentSlots.size
        if (perPage == 0) return
        page = page.coerceIn(0, pageCount - 1)
        all.drop(page * perPage).take(perPage).forEachIndexed { i, entry ->
            set(contentSlots[i], button(entry))
        }
    }

    /** Page changes reopen the inventory so the title's page number updates too. */
    fun goTo(newPage: Int) {
        val clamped = newPage.coerceIn(0, pageCount - 1)
        if (clamped == page) return
        page = clamped
        open()
    }
}
