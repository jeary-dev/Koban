package dev.jeary.koban.game

import dev.jeary.koban.KobanCore
import dev.jeary.koban.command.Permissions
import dev.jeary.koban.config.ReportCategory
import dev.jeary.koban.domain.abuse.GateResult
import dev.jeary.koban.domain.report.PlayerRef
import dev.jeary.koban.domain.time.DurationText
import dev.jeary.koban.hook.VanishHook
import dev.jeary.koban.menu.ChatPrompts
import dev.jeary.koban.menu.ReasonMenu
import dev.jeary.koban.platform.Messenger.Companion.tag
import dev.jeary.koban.platform.evidence.CapturedEvidence
import dev.jeary.koban.platform.evidence.ReportTarget
import dev.jeary.koban.service.CreateResult
import dev.jeary.koban.service.ReportDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.logging.Level

/**
 * `/report`, from the command to the stored report:
 *
 * 1. find the target (online and visible to the reporter, or recently left);
 * 2. refuse self-reports, exempt players, cooldowns and the open-report limit up front;
 * 3. capture the evidence *now*, at the moment the reporter decided to report;
 * 4. choose a reason (menu, or straight from the command);
 * 5. optionally ask for details in chat;
 * 6. file it and tell the reporter what happened.
 *
 * Runs on the main thread; storage calls suspend without blocking it.
 */
class ReportFlow(private val core: KobanCore) {

    private val messenger get() = core.messenger

    /** Names `/report` suggests: online players the viewer can see, then anyone who just left. */
    fun suggestions(viewer: CommandSender): List<String> {
        val self = (viewer as? Player)?.uniqueId
        val online = Bukkit.getOnlinePlayers()
            .filter { it.uniqueId != self && VanishHook.isVisibleTo(it, viewer as? Player) }
            .map { it.name }
        val left = if (core.config.core.flow.allowRecentlyLeft) core.history.recentlyLeft().map { it.second } else emptyList()
        return (online + left).distinct()
    }

    fun categoryKeys(): List<String> = core.config.reasons.keys

    /** Online and visible, or one of the players who left within `evidence.retain-after-quit`. */
    fun resolve(reporter: Player, name: String): ReportTarget? {
        Bukkit.getPlayerExact(name)
            ?.takeIf { VanishHook.isVisibleTo(it, reporter) }
            ?.let { return ReportTarget.Online(it) }
        if (!core.config.core.flow.allowRecentlyLeft) return null
        // A player who left and rejoined is online again (handled above); here they're gone.
        return core.history.recentlyLeft()
            .firstOrNull { (uuid, left) -> left.equals(name, ignoreCase = true) && Bukkit.getPlayer(uuid) == null }
            ?.let { (uuid, left) -> ReportTarget.RecentlyLeft(PlayerRef(uuid, left)) }
    }

    fun start(reporter: Player, name: String, reasonKey: String?, details: String?) = launch(reporter) {
        val target = resolve(reporter, name) ?: return@launch messenger.send(reporter, "report.not-found", tag("player", name))
        val reported = target.ref
        if (reported.uuid == reporter.uniqueId) return@launch messenger.send(reporter, "report.self")
        if (target is ReportTarget.Online && target.player.hasPermission(Permissions.EXEMPT)) {
            return@launch messenger.send(reporter, "report.exempt", tag("player", reported.name))
        }

        // A reason typed on the command line is checked before anything else happens.
        val category = reasonKey?.let { key ->
            core.config.reasons[key.lowercase()] ?: return@launch messenger.send(
                reporter, "report.unknown-reason",
                tag("reason", key),
                tag("reasons", core.config.reasons.keys.joinToString(", ")),
            )
        }

        val gate = core.reports.precheck(
            reporter.uniqueId, reported.uuid,
            bypassCooldown = reporter.hasPermission(Permissions.BYPASS_COOLDOWN),
            bypassLimit = reporter.hasPermission(Permissions.BYPASS_LIMIT),
        )
        if (gate != GateResult.Allowed) return@launch denied(reporter, reported, gate)
        if (!reporter.isOnline) return@launch

        // Evidence is taken now: by the time a reason is chosen the moment may have passed.
        val evidence = core.evidence.capture(reporter, target)

        if (category != null) {
            askDetails(reporter, reported, category, evidence, details)
        } else {
            val categories = core.config.reasons.categories
            ReasonMenu(reporter, core.menuUi, reported, categories) { chosen ->
                askDetails(reporter, reported, chosen, evidence, null)
            }.open()
        }
    }

    private fun askDetails(reporter: Player, reported: PlayerRef, category: ReportCategory, evidence: CapturedEvidence, given: String?) {
        val flow = core.config.core.flow
        if (given != null || !flow.askDetails || !category.askDetails) {
            submit(reporter, reported, category, evidence, given)
            return
        }
        core.prompts.ask(
            reporter, "report.ask-details", tag("player", reported.name),
            timeout = flow.detailsTimeout, optional = true,
        ) { answer ->
            when (answer) {
                is ChatPrompts.Answer.Text -> submit(reporter, reported, category, evidence, answer.value)
                ChatPrompts.Answer.Skipped -> submit(reporter, reported, category, evidence, null)
                ChatPrompts.Answer.Cancelled -> Unit
            }
        }
    }

    private fun submit(reporter: Player, reported: PlayerRef, category: ReportCategory, evidence: CapturedEvidence, details: String?) =
        launch(reporter) {
            val draft = ReportDraft(
                reporter = PlayerRef(reporter.uniqueId, reporter.name),
                reported = reported,
                category = category.key,
                details = details,
                location = evidence.location,
                evidence = evidence.snapshot,
                bypassCooldown = reporter.hasPermission(Permissions.BYPASS_COOLDOWN),
                bypassLimit = reporter.hasPermission(Permissions.BYPASS_LIMIT),
            )
            val result = try {
                core.reports.create(draft)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                core.logger.log(Level.SEVERE, "Could not save a report on ${reported.name}", e)
                return@launch messenger.send(reporter, "report.error")
            }
            when (result) {
                is CreateResult.Created -> messenger.send(reporter, "report.sent", tag("player", reported.name), tag("id", result.report.id.value))
                is CreateResult.Merged -> messenger.send(
                    reporter, "report.sent-merged",
                    tag("player", reported.name), tag("id", result.report.id.value), tag("count", result.report.reporterCount),
                )
                is CreateResult.AlreadyReported -> messenger.send(reporter, "report.already-reported", tag("player", reported.name))
                is CreateResult.Denied -> denied(reporter, reported, result.reason)
                CreateResult.Cancelled -> messenger.send(reporter, "report.cancelled")
            }
        }

    private fun denied(reporter: Player, reported: PlayerRef, reason: GateResult) = when (reason) {
        GateResult.Allowed -> Unit
        GateResult.SelfReport -> messenger.send(reporter, "report.self")
        is GateResult.OnCooldown -> messenger.send(reporter, "report.cooldown", tag("time", DurationText.short(reason.remaining)))
        is GateResult.TooManyOpen -> messenger.send(reporter, "report.too-many-open", tag("limit", reason.limit), tag("player", reported.name))
    }

    /** Runs on the main thread; any failure is logged and the reporter told, never swallowed. */
    private fun launch(reporter: Player, block: suspend () -> Unit) {
        core.scope.launch(core.main) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                core.logger.log(Level.SEVERE, "Error while handling a report from ${reporter.name}", e)
                if (reporter.isOnline) messenger.send(reporter, "report.error")
            }
        }
    }
}
