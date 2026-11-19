package dev.jeary.koban.hook

import dev.jeary.koban.KobanCore
import dev.jeary.koban.domain.report.ReportStatus
import dev.jeary.koban.service.ReportChange
import dev.jeary.koban.service.ReportListener
import dev.jeary.koban.storage.repo.ReportQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * PlaceholderAPI expansion. PlaceholderAPI asks on the main thread, often (scoreboards every
 * tick), so every answer comes from memory: counts are refreshed in the background after each
 * report change and once a minute. A player's own numbers are loaded the first time they're
 * asked for, so the very first request answers empty.
 *
 * | Placeholder               | Value                                             |
 * |---------------------------|---------------------------------------------------|
 * | `%koban_open%`            | reports nobody has claimed yet                    |
 * | `%koban_claimed%`         | reports being handled                             |
 * | `%koban_active%`          | open + claimed                                    |
 * | `%koban_resolved%`        | resolved reports, ever                            |
 * | `%koban_dismissed%`       | dismissed reports, ever                           |
 * | `%koban_my_claimed%`      | reports the player has claimed right now          |
 * | `%koban_against%`         | reports ever made against the player              |
 * | `%koban_made%`            | reports the player has made (merged ones too)     |
 * | `%koban_linked%`          | `true` when the player linked a Discord account   |
 * | `%koban_discord%`         | `online` or `offline`: the bot's connection       |
 */
class KobanPlaceholders(private val core: KobanCore) : PlaceholderExpansion(), ReportListener {

    private class Personal(val myClaimed: Int, val against: Int, val made: Int, val linked: Boolean, val generation: Long)

    @Volatile
    private var counts: Map<ReportStatus, Int> = emptyMap()
    private val personal = ConcurrentHashMap<UUID, Personal>()
    private val loading = ConcurrentHashMap.newKeySet<UUID>()

    /** Bumped on every change, so cached personal numbers are reloaded the next time they're asked for. */
    private val generation = AtomicLong()
    private val refresh = Channel<Unit>(Channel.CONFLATED)
    private var jobs: List<Job> = emptyList()

    override fun getIdentifier(): String = "koban"
    override fun getAuthor(): String = "jeary-dev"
    override fun getVersion(): String = core.plugin.description.version
    override fun persist(): Boolean = true

    fun start(): KobanPlaceholders {
        jobs = listOf(
            core.scope.launch(Dispatchers.IO) {
                for (unit in refresh) {
                    runCatching { counts = core.reports.countByStatus() }
                    delay(DEBOUNCE_MS) // a burst of changes is one query
                }
            },
            core.scope.launch {
                while (isActive) {
                    refresh.trySend(Unit)
                    delay(PERIOD_MS)
                }
            },
        )
        core.reports.addListener(this)
        register()
        return this
    }

    fun stop() {
        core.reports.removeListener(this)
        jobs.forEach { it.cancel() }
        unregister()
    }

    override suspend fun onChange(change: ReportChange) {
        generation.incrementAndGet()
        refresh.trySend(Unit)
    }

    override fun onRequest(player: OfflinePlayer?, params: String): String? {
        when (params.lowercase()) {
            "open" -> return count(ReportStatus.OPEN)
            "claimed" -> return count(ReportStatus.CLAIMED)
            "active" -> return (counts[ReportStatus.OPEN] ?: 0).plus(counts[ReportStatus.CLAIMED] ?: 0).toString()
            "resolved" -> return count(ReportStatus.RESOLVED)
            "dismissed" -> return count(ReportStatus.DISMISSED)
            "discord" -> return if (core.discord.ready != null) "online" else "offline"
            "my_claimed" -> return personal(player) { it.myClaimed.toString() }
            "against" -> return personal(player) { it.against.toString() }
            "made" -> return personal(player) { it.made.toString() }
            "linked" -> return personal(player) { it.linked.toString() }
        }
        return null
    }

    private fun count(status: ReportStatus) = (counts[status] ?: 0).toString()

    private fun personal(player: OfflinePlayer?, value: (Personal) -> String): String {
        val uuid = player?.uniqueId ?: return ""
        val cached = personal[uuid]
        val now = generation.get()
        if ((cached == null || cached.generation != now) && loading.add(uuid)) {
            core.scope.launch(Dispatchers.IO) {
                try {
                    personal[uuid] = Personal(
                        myClaimed = core.reports.count(ReportQuery(setOf(ReportStatus.CLAIMED), claimedBy = uuid, limit = Int.MAX_VALUE)),
                        against = core.reports.countAgainst(uuid),
                        made = core.reports.countMadeBy(uuid),
                        linked = core.links.byMinecraft(uuid) != null,
                        generation = now,
                    )
                    if (personal.size > MAX_CACHED) personal.keys.retainAll(core.plugin.server.onlinePlayers.map { it.uniqueId }.toSet() + uuid)
                } finally {
                    loading.remove(uuid)
                }
            }
        }
        return cached?.let(value) ?: ""
    }

    private companion object {
        const val DEBOUNCE_MS = 1_000L
        const val PERIOD_MS = 60_000L
        const val MAX_CACHED = 2_000
    }
}
