package dev.jeary.koban.config

import dev.jeary.koban.domain.abuse.AntiAbuseSettings
import dev.jeary.koban.domain.evidence.CommandRedactor
import dev.jeary.koban.domain.report.PriorityScale
import dev.jeary.koban.domain.report.PriorityTier
import dev.jeary.koban.platform.compat.Compat
import dev.jeary.koban.service.ReportSettings
import dev.jeary.koban.storage.StorageSettings
import java.time.Duration

/** What evidence is captured with each report (config.yml `evidence:`). */
data class EvidenceSettings(
    /** Lines of chat kept per online player, in memory only. */
    val bufferSize: Int,
    /** Lines of each player's chat copied into a report. */
    val chatLines: Int,
    /** How far around the reported player to look for witnesses, in blocks. */
    val nearbyRadius: Double,
    val maxNearby: Int,
    /** Recording the reported player's recent commands. Off by default. */
    val commandsEnabled: Boolean,
    val commandLines: Int,
    val redactor: CommandRedactor,
    /** How long a player who left can still be reported (and their chat kept). */
    val retainAfterQuit: Duration,
)

/** The `/report` flow (config.yml `reports:`). */
data class ReportFlowSettings(
    val askDetails: Boolean,
    val detailsTimeout: Duration,
    /** Allow reporting players who left within `evidence.retain-after-quit`. */
    val allowRecentlyLeft: Boolean,
)

/** Staff alerts (config.yml `notifications:`). */
data class NotificationSettings(
    val alertSound: String?,
    val joinSummary: Boolean,
    /** Delay before the join summary, so it isn't lost among other plugins' join messages. */
    val joinSummaryDelay: Duration,
)

/** Everything in config.yml that isn't Discord. */
data class CoreSettings(
    val serverName: String,
    val storage: StorageSettings,
    val reports: ReportSettings,
    val flow: ReportFlowSettings,
    val evidence: EvidenceSettings,
    val notifications: NotificationSettings,
    val priority: PriorityScale,
) {
    companion object {
        private val SERVER_NAME = Regex("""^[\p{L}\p{N} _.\-]{1,32}$""")

        fun parse(root: ConfigNode): CoreSettings {
            val serverName = root.string("server-name", "survival").trim().let { name ->
                if (SERVER_NAME.matches(name)) name else "survival".also {
                    root.error("server-name", "expected 1-32 letters, digits, spaces, dots, dashes or underscores, got \"$name\"")
                }
            }

            val reports = root.nodeOrEmpty("reports")
            val antiAbuse = AntiAbuseSettings(
                cooldown = reports.duration("cooldown", Duration.ofSeconds(60), max = Duration.ofDays(1)),
                maxOpenPerReporter = reports.int("max-open-per-reporter", 3, 0..1000),
            )
            val mergeWindow = reports.duration("merge-window", Duration.ofMinutes(30), max = Duration.ofDays(30))
            val flow = ReportFlowSettings(
                askDetails = reports.bool("ask-details", true),
                detailsTimeout = reports.duration("details-timeout", Duration.ofSeconds(60), Duration.ofSeconds(10), Duration.ofMinutes(10)),
                allowRecentlyLeft = reports.bool("allow-recently-left", true),
            )

            val evidence = root.nodeOrEmpty("evidence")
            val chat = evidence.nodeOrEmpty("chat")
            val bufferSize = chat.int("buffer-size", 25, 0..500)
            val chatLines = chat.int("lines", 10, 0..100)
            if (chatLines > bufferSize) chat.error("lines", "can't be more than buffer-size ($bufferSize): only $bufferSize lines are kept")
            val commands = evidence.nodeOrEmpty("commands")
            val extraSensitive = commands.stringList("never-record")
            extraSensitive.filter { it.isBlank() || it.trim().contains(' ') }.forEach {
                commands.error("never-record", "\"$it\" isn't a command label (expected e.g. vault or myplugin:secret)")
            }
            val evidenceSettings = EvidenceSettings(
                bufferSize = bufferSize,
                chatLines = chatLines,
                nearbyRadius = evidence.double("nearby-radius", 32.0, 0.0..256.0),
                maxNearby = evidence.int("max-nearby", 10, 0..50),
                commandsEnabled = commands.bool("enabled", false),
                commandLines = commands.int("lines", 10, 1..100),
                redactor = CommandRedactor(extraSensitive),
                retainAfterQuit = evidence.duration("retain-after-quit", Duration.ofMinutes(5), max = Duration.ofHours(1)),
            )

            val notifications = root.nodeOrEmpty("notifications")
            val sound = notifications.string("alert-sound", "block.note_block.chime").trim().takeIf { it.isNotEmpty() }
            if (sound != null && !Compat.isSoundKey(sound)) {
                notifications.error("alert-sound", "expected a sound key such as block.note_block.chime, got \"$sound\"")
            }

            return CoreSettings(
                serverName = serverName,
                storage = StorageConfig.parse(root.nodeOrEmpty("storage")),
                reports = ReportSettings(serverName, antiAbuse, mergeWindow),
                flow = flow,
                evidence = evidenceSettings,
                notifications = NotificationSettings(
                    alertSound = sound,
                    joinSummary = notifications.bool("join-summary", true),
                    joinSummaryDelay = notifications.duration("join-summary-delay", Duration.ofSeconds(3), max = Duration.ofMinutes(1)),
                ),
                priority = parsePriority(root),
            )
        }

        private fun parsePriority(root: ConfigNode): PriorityScale {
            val node = root.node("priority") ?: return PriorityScale.DEFAULT
            val tiers = node.keys().mapNotNull { key ->
                val tier = node.node(key) ?: return@mapNotNull null.also { node.error(key, "expected a section with reporters and color") }
                val color = tier.string("color")?.trim()?.removePrefix("#")?.toIntOrNull(16)
                if (color == null || color !in 0..0xFFFFFF) {
                    tier.error("color", "expected a hex colour like #e8a5b4")
                    return@mapNotNull null
                }
                PriorityTier(tier.string("name", key), tier.int("reporters", 1, 1..10_000), color)
            }
            if (tiers.isEmpty()) {
                node.error(null, "needs at least one tier")
                return PriorityScale.DEFAULT
            }
            if (tiers.none { it.minReporters == 1 }) {
                node.error(null, "one tier must start at reporters: 1")
                return PriorityScale.DEFAULT
            }
            return PriorityScale(tiers)
        }
    }
}
