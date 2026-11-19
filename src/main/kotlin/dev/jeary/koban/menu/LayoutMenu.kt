package dev.jeary.koban.menu

import dev.jeary.koban.config.MenuAction
import dev.jeary.koban.config.MenuConfig
import dev.jeary.koban.config.MenuItem
import dev.jeary.koban.config.MenuLayout
import dev.jeary.koban.platform.Messenger
import dev.jeary.koban.platform.Messenger.Companion.tag
import dev.jeary.koban.platform.compat.Compat
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack

/** Rendering helpers shared by every configurable menu. Main thread only. */
class MenuUi(private val messenger: Messenger, private val menus: () -> MenuConfig) {

    val config: MenuConfig get() = menus()

    /** MiniMessage → legacy text for item names and lore. */
    fun text(raw: String, vararg tags: TagResolver): String = messenger.itemText(raw, *tags)

    fun lines(raw: List<String>, vararg tags: TagResolver): List<String> = raw.flatMap { line ->
        // A tag that expands to several lines (a list of reporters, say) becomes several lore lines.
        text(line, *tags).split('\n')
    }

    fun title(raw: String, vararg tags: TagResolver): String = messenger.title(raw, *tags)

    fun icon(item: MenuItem, vararg tags: TagResolver): ItemStack =
        ItemBuilder(item.material)
            .name(text(item.name, *tags))
            .lore(lines(item.lore, *tags))
            .glow(item.glow)
            .customModelData(item.customModelData)
            .build()

    fun filler(): ItemStack = icon(config.filler)

    fun click(player: Player) = Compat.playSound(player, config.clickSound, 0.5f, 1.2f)
}

/**
 * A paginated menu drawn from a [MenuLayout] in menus.yml. Subclasses supply the entries, say
 * which actions are usable right now, and handle them; the layout decides where everything goes.
 */
abstract class LayoutMenu<T>(viewer: Player, protected val ui: MenuUi) : PaginatedMenu<T>(viewer) {

    /** Read on every open, so `/koban reload` applies to the next menu opened. */
    protected abstract val layout: MenuLayout

    override val size: Int get() = layout.size
    override val contentSlots: List<Int> get() = layout.contentSlots

    private var cached: List<T>? = null

    protected abstract fun load(): List<T>

    final override fun entries(): List<T> = cached ?: load().also { cached = it }

    override fun open() {
        cached = null
        super.open()
    }

    /** Re-reads the entries and redraws in place. */
    fun reload() {
        cached = null
        refresh()
    }

    /** Placeholders for the title and every button. */
    protected open fun tags(): List<TagResolver> = emptyList()

    private fun allTags(): Array<TagResolver> =
        (listOf(tag("page", page.coerceAtMost(pageCount - 1) + 1), tag("pages", pageCount)) + tags()).toTypedArray()

    override fun title(): String = ui.title(layout.title, *allTags())

    override fun render() {
        val l = layout
        val tags = allTags()
        l.rows.forEachIndexed { row, line ->
            line.forEachIndexed { col, char ->
                val slot = row * 9 + col
                when (char) {
                    MenuLayout.EMPTY_CHAR, l.contentChar, l.dynamicChar -> Unit
                    MenuLayout.FILLER_CHAR -> set(slot, ui.filler())
                    else -> {
                        val item = l.items[char]
                        if (item != null && available(item.action)) {
                            set(slot, ui.icon(item, *tags)) { click ->
                                ui.click(viewer)
                                onAction(item.action, click)
                            }
                        } else {
                            set(slot, ui.filler())
                        }
                    }
                }
            }
        }
        renderPage()
        renderDynamic(l.dynamicSlots)
    }

    /** Fills the layout's dynamic slots (the reported player's head, say). */
    protected open fun renderDynamic(slots: List<Int>) {}

    /** Whether a button's action can be used right now; unusable buttons show the filler. */
    protected open fun available(action: MenuAction): Boolean = when (action) {
        MenuAction.PREVIOUS_PAGE -> hasPrevious
        MenuAction.NEXT_PAGE -> hasNext
        else -> true
    }

    protected open fun onAction(action: MenuAction, click: ClickType) {
        when (action) {
            MenuAction.PREVIOUS_PAGE -> goTo(page - 1)
            MenuAction.NEXT_PAGE -> goTo(page + 1)
            MenuAction.CLOSE -> viewer.closeInventory()
            else -> Unit
        }
    }
}
