package dev.jeary.koban.menu

import dev.jeary.koban.config.MenuLayout
import dev.jeary.koban.config.ReasonMenuConfig
import dev.jeary.koban.config.ReportCategory
import dev.jeary.koban.domain.report.PlayerRef
import dev.jeary.koban.platform.Messenger.Companion.tag
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * `/report <player>`: one button per category from reasons.yml, the reported player's head in
 * the layout's dynamic slots. Choosing a reason closes the menu and hands it to [onChoose];
 * closing the menu any other way sends nothing.
 */
class ReasonMenu(
    viewer: Player,
    ui: MenuUi,
    private val target: PlayerRef,
    private val categories: List<ReportCategory>,
    private val onChoose: (ReportCategory) -> Unit,
) : LayoutMenu<ReportCategory>(viewer, ui) {

    private val config: ReasonMenuConfig get() = ui.config.reasons

    override val layout: MenuLayout get() = config.layout

    override fun load(): List<ReportCategory> = categories

    override fun tags(): List<TagResolver> = listOf(tag("player", target.name))

    override fun button(entry: ReportCategory): MenuButton {
        val tags = tags().toTypedArray()
        val icon = ItemBuilder(entry.icon)
            .name(ui.text(entry.name, *tags))
            .lore(ui.lines(entry.description + config.reasonLore, *tags))
            .customModelData(entry.customModelData)
            .build()
        return MenuButton(icon) {
            ui.click(viewer)
            viewer.closeInventory()
            onChoose(entry)
        }
    }

    override fun renderDynamic(slots: List<Int>) {
        if (slots.isEmpty()) return
        val tags = tags().toTypedArray()
        val head = ItemBuilder(Material.PLAYER_HEAD)
            .name(ui.text(config.target.name, *tags))
            .lore(ui.lines(config.target.lore, *tags))
            .head(Bukkit.getOfflinePlayer(target.uuid))
            .build()
        slots.forEach { set(it, head) }
    }
}
