package dev.jeary.koban.config

import dev.jeary.koban.platform.compat.Compat
import org.bukkit.Material

/** What a menu button does when clicked, parsed from strings like `next-page`. */
enum class MenuAction(val id: String) {
    NONE("none"),
    CLOSE("close"),
    BACK("back"),
    PREVIOUS_PAGE("previous-page"),
    NEXT_PAGE("next-page"),

    // /reports list and history
    /** Cycle open → claimed → closed → all. */
    STATUS("status"),
    /** Cycle the category filter through reasons.yml. */
    CATEGORY("category"),
    /** Ask for a player name in chat and filter by it. */
    PLAYER("player"),
    CLEAR_FILTERS("clear-filters"),
    REFRESH("refresh"),

    // A report
    TELEPORT("teleport"),
    /** Claim, unclaim, or take over (with koban.override), whichever applies. */
    CLAIM("claim"),
    NOTE("note"),
    RESOLVE("resolve"),
    RESOLVE_NO_ACTION("resolve-no-action"),
    DISMISS("dismiss"),
    REOPEN("reopen"),
    HISTORY("history"),
    ;

    companion object {
        fun parse(raw: String): MenuAction? = entries.firstOrNull { it.id == raw.trim().lowercase() }
    }
}

/** A configurable item: name and lore are MiniMessage. */
data class MenuItem(
    val material: Material,
    val name: String,
    val lore: List<String>,
    val customModelData: Int?,
    val glow: Boolean,
    val action: MenuAction,
)

/**
 * A menu drawn as a character grid, one string per row of 9:
 * ```
 * "#########"
 * "#xxxxxxx#"
 * "#p##s##n#"
 * ```
 * Each character maps to an item in [items]; [contentChar] marks the slots that paginated content
 * fills; [FILLER_CHAR] uses the shared filler; a space leaves the slot empty.
 */
data class MenuLayout(
    val title: String,
    val rows: List<String>,
    val items: Map<Char, MenuItem>,
    val contentChar: Char?,
    /** Extra character for dynamically generated buttons (the reported player's head). */
    val dynamicChar: Char?,
) {
    val size: Int get() = rows.size * 9

    fun slotsOf(char: Char): List<Int> = rows.flatMapIndexed { row, line ->
        line.mapIndexedNotNull { col, c -> if (c == char) row * 9 + col else null }
    }

    val contentSlots: List<Int> get() = contentChar?.let(::slotsOf) ?: emptyList()
    val dynamicSlots: List<Int> get() = dynamicChar?.let(::slotsOf) ?: emptyList()

    companion object {
        const val FILLER_CHAR = '#'
        const val EMPTY_CHAR = ' '
    }
}

/** Name and lore for an icon Koban builds itself (a player head, say). MiniMessage. */
data class TextBlock(val name: String, val lore: List<String>)

/** The `/report` reason menu. */
data class ReasonMenuConfig(val layout: MenuLayout, val target: TextBlock, val reasonLore: List<String>)

/** A list of reports (`/reports`, history): [entry] is drawn once per report. */
data class ReportListConfig(val layout: MenuLayout, val entry: TextBlock)

/** One report. [head] is the reported player's head in the dynamic slots. */
data class ReportViewConfig(val layout: MenuLayout, val head: TextBlock)

/** menus.yml. Each layout is read again on every open, so `/koban reload` applies at once. */
data class MenuConfig(
    val filler: MenuItem,
    val clickSound: String?,
    val reasons: ReasonMenuConfig,
    val reports: ReportListConfig,
    val view: ReportViewConfig,
    val history: ReportListConfig,
) {
    companion object {
        fun parse(root: ConfigNode): MenuConfig {
            val filler = root.node("filler", required = true)?.let { parseItem(it, requireAction = false) }
                ?: MenuItem(Material.BLACK_STAINED_GLASS_PANE, " ", emptyList(), null, false, MenuAction.NONE)
            val sound = root.string("click-sound")?.trim()?.takeIf { it.isNotEmpty() }
            if (sound != null && !Compat.isSoundKey(sound)) root.error("click-sound", "expected a sound key such as ui.button.click, got \"$sound\"")
            return MenuConfig(
                filler = filler,
                clickSound = sound,
                reasons = ReasonMenuConfig(
                    layout = parseLayout(root, "report-reasons", contentRequired = true, dynamicRequired = false),
                    target = textBlock(root.nodeOrEmpty("report-reasons").nodeOrEmpty("target"), "<player>"),
                    reasonLore = root.nodeOrEmpty("report-reasons").stringList("reason-lore"),
                ),
                reports = listConfig(root, "report-list"),
                view = ReportViewConfig(
                    layout = parseLayout(root, "report-view", contentRequired = false, dynamicRequired = true),
                    head = textBlock(root.nodeOrEmpty("report-view").nodeOrEmpty("head"), "<player>"),
                ),
                history = listConfig(root, "report-history"),
            )
        }

        private fun listConfig(root: ConfigNode, key: String) = ReportListConfig(
            layout = parseLayout(root, key, contentRequired = true, dynamicRequired = false),
            entry = textBlock(root.nodeOrEmpty(key).nodeOrEmpty("entry"), "#<id> <player>"),
        )

        fun textBlock(node: ConfigNode, defaultName: String) = TextBlock(node.string("name", defaultName), node.stringList("lore"))

        private fun parseLayout(root: ConfigNode, key: String, contentRequired: Boolean, dynamicRequired: Boolean): MenuLayout {
            val node = root.node(key, required = true)
                ?: return MenuLayout(key, listOf("         "), emptyMap(), null, null)
            val rows = node.stringList("layout")
            if (rows.isEmpty() || rows.size > 6) node.error("layout", "expected between 1 and 6 rows, got ${rows.size}")
            rows.forEachIndexed { i, row ->
                if (row.length != 9) node.error("layout[$i]", "each row must be exactly 9 characters, \"$row\" has ${row.length}")
            }
            val contentChar = node.string("content-char")?.singleOrNull()
            if (contentRequired && contentChar == null) node.error("content-char", "expected a single character marking content slots")
            val dynamicChar = node.string("dynamic-char")?.singleOrNull()
            if (dynamicRequired && dynamicChar == null) node.error("dynamic-char", "expected a single character")

            val itemsNode = node.node("items")
            val items = itemsNode?.keys().orEmpty().mapNotNull { k ->
                val char = k.singleOrNull()
                if (char == null) {
                    itemsNode?.error(k, "item keys must be a single character")
                    null
                } else {
                    itemsNode?.node(k)?.let { char to parseItem(it, requireAction = true) }
                }
            }.toMap()

            val used = rows.joinToString("").toSet()
            val known = items.keys + MenuLayout.FILLER_CHAR + MenuLayout.EMPTY_CHAR + listOfNotNull(contentChar, dynamicChar)
            (used - known).forEach { c -> node.error("layout", "character '$c' is used but has no entry under items") }
            if (contentRequired && contentChar != null && contentChar !in used) {
                node.error("layout", "no slot uses the content character '$contentChar'")
            }

            val safeRows = rows.take(6).map { it.padEnd(9).take(9) }.ifEmpty { listOf("         ") }
            return MenuLayout(node.string("title", key), safeRows, items, contentChar, dynamicChar)
        }

        private fun parseItem(node: ConfigNode, requireAction: Boolean): MenuItem {
            val candidates = node.stringList("material")
            if (candidates.isEmpty()) node.error("material", "missing required item")
            val material = Compat.material(candidates, Material.AIR).let { found ->
                if (found == Material.AIR && candidates.isNotEmpty()) {
                    node.error("material", "none of ${candidates.joinToString()} is an item on this server version")
                    Material.PAPER
                } else {
                    found.takeUnless { it == Material.AIR } ?: Material.PAPER
                }
            }
            val action = node.string("action")?.let { raw ->
                MenuAction.parse(raw) ?: MenuAction.NONE.also {
                    node.error("action", "unknown action \"$raw\" (expected one of: ${MenuAction.entries.joinToString { it.id }})")
                }
            } ?: MenuAction.NONE
            if (requireAction && !node.has("action")) {
                node.warn("action", "no action set; the item will do nothing (set `action: none` to silence this)")
            }
            return MenuItem(
                material = material,
                name = node.string("name", " "),
                lore = node.stringList("lore"),
                customModelData = if (node.has("custom-model-data")) node.int("custom-model-data", 0, 0..Int.MAX_VALUE) else null,
                glow = node.bool("glow", false),
                action = action,
            )
        }
    }
}
