package dev.jeary.koban.menu

import dev.jeary.koban.KobanCore
import dev.jeary.koban.command.Permissions
import dev.jeary.koban.config.MenuAction
import dev.jeary.koban.config.MenuLayout
import dev.jeary.koban.config.ReportListConfig
import dev.jeary.koban.domain.report.Outcome
import dev.jeary.koban.domain.report.PlayerRef
import dev.jeary.koban.domain.report.Report
import dev.jeary.koban.domain.report.ReportId
import dev.jeary.koban.domain.report.ReportStatus
import dev.jeary.koban.platform.Messenger.Companion.component
import dev.jeary.koban.platform.Messenger.Companion.tag
import dev.jeary.koban.service.ReportDetails
import dev.jeary.koban.storage.repo.ReportQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import java.util.logging.Level

/** Which reports `/reports` shows. */
enum class ListView(val statuses: Set<ReportStatus>, val order: ReportQuery.Order, val messageKey: String) {
    OPEN(setOf(ReportStatus.OPEN), ReportQuery.Order.PRIORITY, "view.list-open"),
    CLAIMED(setOf(ReportStatus.CLAIMED), ReportQuery.Order.PRIORITY, "view.list-claimed"),
    CLOSED(ReportStatus.CLOSED, ReportQuery.Order.RECENT, "view.list-closed"),
    ;

    fun next(): ListView = entries[(ordinal + 1) % entries.size]
}

/** The filters of one `/reports` session, kept while staff move between the list and reports. */
data class ListState(
    val view: ListView = ListView.OPEN,
    val category: String? = null,
    val player: PlayerRef? = null,
    val page: Int = 0,
)

/**
 * Opens the staff menus. Data is loaded off the main thread first, then the menu is drawn from
 * it, so a slow database never freezes the server.
 */
class StaffMenus(private val core: KobanCore) {

    fun openList(viewer: Player, state: ListState = ListState()) = launch(viewer) {
        val query = ReportQuery(
            statuses = state.view.statuses,
            category = state.category,
            reported = state.player?.uuid,
            order = state.view.order,
            limit = MAX_LISTED,
        )
        val reports = core.reports.list(query)
        if (!viewer.isOnline) return@launch
        ReportListMenu(viewer, core, core.config.menus.reports, reports, state, history = null).openAt(state.page)
    }

    fun openHistory(viewer: Player, player: PlayerRef, page: Int = 0) = launch(viewer) {
        val reports = core.reports.history(player.uuid, limit = MAX_LISTED)
        if (!viewer.isOnline) return@launch
        ReportListMenu(viewer, core, core.config.menus.history, reports, ListState(player = player), history = player).openAt(page)
    }

    /** Opens report [id]; [back] reopens whatever the viewer came from. */
    fun openReport(viewer: Player, id: ReportId, back: (() -> Unit)? = null) = launch(viewer) {
        val details = core.reports.details(id) ?: return@launch core.staffActions.notFound(viewer, id)
        if (!viewer.isOnline) return@launch
        ReportViewMenu(viewer, core, details, back).open()
    }

    private fun launch(viewer: Player, block: suspend () -> Unit) {
        core.scope.launch(core.main) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                core.logger.log(Level.SEVERE, "Could not open a report menu for ${viewer.name}", e)
                core.messenger.send(viewer, "command.error")
            }
        }
    }

    companion object {
        /** Ten pages of the default layout; older reports are found through filters or history. */
        const val MAX_LISTED = 450
    }
}

/** `/reports` and `/reports history <player>`: one icon per report. */
class ReportListMenu(
    viewer: Player,
    private val core: KobanCore,
    private val config: ReportListConfig,
    private val reports: List<Report>,
    private val state: ListState,
    private val history: PlayerRef?,
) : LayoutMenu<Report>(viewer, core.menuUi) {

    override val layout: MenuLayout get() = config.layout

    override fun load(): List<Report> = reports

    fun openAt(page: Int) {
        open()
        if (page > 0) goTo(page)
    }

    override fun tags(): List<TagResolver> = listOf(
        component("view", core.messenger.component(state.view.messageKey)),
        tag("filter", filterText()),
        tag("player", history?.name ?: state.player?.name ?: ""),
        tag("count", reports.size),
    )

    private fun filterText(): String {
        val parts = listOfNotNull(
            state.category?.let { core.config.reasons.displayName(it) },
            state.player?.name,
        )
        return if (parts.isEmpty()) core.messenger.plain("view.no-filter") else parts.joinToString(" · ")
    }

    override fun button(entry: Report): MenuButton {
        val tags = (core.presenter.summary(entry) + tags()).toTypedArray()
        val category = core.config.reasons[entry.category]
        val icon = ItemBuilder(if (history == null) Material.PLAYER_HEAD else (category?.icon ?: Material.PAPER))
            .name(ui.text(config.entry.name, *tags))
            .lore(ui.lines(config.entry.lore, *tags))
            .glow(entry.reporterCount > 1 && entry.status.isActive)
            .apply { if (history == null) head(Bukkit.getOfflinePlayer(entry.reported.uuid)) }
            .build()
        return MenuButton(icon) {
            ui.click(viewer)
            val here = state.copy(page = page)
            core.staffMenus.openReport(viewer, entry.id) {
                if (history != null) core.staffMenus.openHistory(viewer, history, page) else core.staffMenus.openList(viewer, here)
            }
        }
    }

    override fun available(action: MenuAction): Boolean = when (action) {
        MenuAction.STATUS, MenuAction.CATEGORY, MenuAction.PLAYER -> history == null
        MenuAction.CLEAR_FILTERS -> history == null && (state.category != null || state.player != null)
        MenuAction.BACK -> history != null
        else -> super.available(action)
    }

    override fun onAction(action: MenuAction, click: ClickType) {
        val menus = core.staffMenus
        when (action) {
            MenuAction.STATUS -> menus.openList(viewer, state.copy(view = state.view.next(), page = 0))
            MenuAction.CATEGORY -> {
                val keys = listOf<String?>(null) + core.config.reasons.keys
                val step = if (click.isRightClick) -1 else 1
                val next = keys[(keys.indexOf(state.category) + step + keys.size) % keys.size]
                menus.openList(viewer, state.copy(category = next, page = 0))
            }
            MenuAction.PLAYER -> core.prompts.ask(
                viewer, "staff.filter-ask", timeout = core.config.core.flow.detailsTimeout, optional = true,
            ) { answer ->
                when (answer) {
                    is ChatPrompts.Answer.Text -> core.scope.launch(core.main) {
                        val name = answer.value.trim()
                        val found = Bukkit.getPlayerExact(name)?.let { PlayerRef(it.uniqueId, it.name) } ?: core.directory.byName(name)
                        if (found == null) {
                            core.messenger.send(viewer, "staff.unknown-player", tag("player", name))
                        } else if (viewer.isOnline) {
                            menus.openList(viewer, state.copy(player = found, page = 0))
                        }
                    }
                    ChatPrompts.Answer.Skipped -> menus.openList(viewer, state.copy(player = null, page = 0))
                    ChatPrompts.Answer.Cancelled -> Unit
                }
            }
            MenuAction.CLEAR_FILTERS -> menus.openList(viewer, ListState(view = state.view))
            MenuAction.REFRESH -> if (history != null) menus.openHistory(viewer, history, page) else menus.openList(viewer, state.copy(page = page))
            MenuAction.BACK -> menus.openList(viewer)
            else -> super.onAction(action, click)
        }
    }
}

/** One report: evidence in the icons' lore, and buttons for everything staff can do. */
class ReportViewMenu(
    viewer: Player,
    private val core: KobanCore,
    private val details: ReportDetails,
    private val back: (() -> Unit)?,
) : LayoutMenu<Unit>(viewer, core.menuUi) {

    private val report get() = details.report
    private val config get() = core.config.menus.view

    override val layout: MenuLayout get() = config.layout

    override fun load(): List<Unit> = emptyList()

    override fun button(entry: Unit): MenuButton = MenuButton(ui.filler())

    private val cachedTags: List<TagResolver> by lazy {
        val mine = report.claim?.by?.minecraft == viewer.uniqueId
        val claimLabel = when {
            mine -> "view.claim-release"
            report.claim != null -> "view.claim-take-over"
            else -> "view.claim"
        }
        core.presenter.details(details) + component("claim-action", core.messenger.component(claimLabel))
    }

    override fun tags(): List<TagResolver> = cachedTags

    override fun renderDynamic(slots: List<Int>) {
        if (slots.isEmpty()) return
        val tags = tags().toTypedArray()
        val head = ItemBuilder(Material.PLAYER_HEAD)
            .name(ui.text(config.head.name, *tags))
            .lore(ui.lines(config.head.lore, *tags))
            .head(Bukkit.getOfflinePlayer(report.reported.uuid))
            .build()
        slots.forEach { set(it, head) }
    }

    override fun available(action: MenuAction): Boolean {
        val active = report.status.isActive
        return when (action) {
            MenuAction.TELEPORT -> report.location != null && viewer.hasPermission(Permissions.TELEPORT)
            MenuAction.CLAIM -> active && viewer.hasPermission(Permissions.CLAIM)
            MenuAction.NOTE -> viewer.hasPermission(Permissions.NOTE)
            MenuAction.RESOLVE, MenuAction.RESOLVE_NO_ACTION, MenuAction.DISMISS -> active && viewer.hasPermission(Permissions.RESOLVE)
            MenuAction.REOPEN -> !active && viewer.hasPermission(Permissions.RESOLVE)
            MenuAction.HISTORY -> viewer.hasPermission(Permissions.HISTORY)
            MenuAction.BACK -> back != null
            else -> super.available(action)
        }
    }

    private fun reopen() = core.staffMenus.openReport(viewer, report.id, back)

    /** Runs a change, then redraws the report with its new state. */
    private fun act(block: suspend () -> Unit) {
        core.scope.launch(core.main) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                core.logger.log(Level.SEVERE, "Could not change report ${report.id}", e)
                core.messenger.send(viewer, "command.error")
            }
            if (viewer.isOnline) reopen()
        }
    }

    private fun askNoteThen(key: String, optional: Boolean, then: suspend (String?) -> Unit) {
        core.prompts.ask(
            viewer, key, tag("id", report.id.value), tag("player", report.reported.name),
            timeout = core.config.core.flow.detailsTimeout, optional = optional,
        ) { answer ->
            when (answer) {
                is ChatPrompts.Answer.Text -> act { then(answer.value) }
                ChatPrompts.Answer.Skipped -> act { then(null) }
                ChatPrompts.Answer.Cancelled -> reopen()
            }
        }
    }

    override fun onAction(action: MenuAction, click: ClickType) {
        val staff = core.staffActions
        val id = report.id
        when (action) {
            MenuAction.TELEPORT -> core.scope.launch(core.main) {
                viewer.closeInventory()
                staff.teleport(viewer, report)
            }
            MenuAction.CLAIM -> act { staff.toggleClaim(viewer, id) }
            MenuAction.NOTE -> askNoteThen("staff.note-ask", optional = false) { text -> if (text != null) staff.note(viewer, id, text) }
            MenuAction.RESOLVE -> askNoteThen("staff.resolve-ask", optional = true) { staff.resolve(viewer, id, Outcome.ACTION_TAKEN, it) }
            MenuAction.RESOLVE_NO_ACTION -> askNoteThen("staff.resolve-ask", optional = true) { staff.resolve(viewer, id, Outcome.NO_ACTION, it) }
            MenuAction.DISMISS -> askNoteThen("staff.dismiss-ask", optional = true) { staff.dismiss(viewer, id, it) }
            MenuAction.REOPEN -> act { staff.reopen(viewer, id) }
            MenuAction.HISTORY -> core.staffMenus.openHistory(viewer, report.reported)
            MenuAction.REFRESH -> reopen()
            MenuAction.BACK -> back?.invoke()
            else -> super.onAction(action, click)
        }
    }
}
