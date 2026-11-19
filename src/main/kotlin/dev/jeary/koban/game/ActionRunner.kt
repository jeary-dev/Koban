package dev.jeary.koban.game

import dev.jeary.koban.KobanCore
import dev.jeary.koban.config.ActionTemplate
import dev.jeary.koban.domain.action.CommandTemplate
import dev.jeary.koban.domain.action.TemplateValues
import dev.jeary.koban.domain.actor.Actor
import dev.jeary.koban.domain.actor.Source
import dev.jeary.koban.domain.audit.AuditAction
import dev.jeary.koban.domain.report.Outcome
import dev.jeary.koban.domain.report.Report
import dev.jeary.koban.domain.report.ReportId
import dev.jeary.koban.service.TransitionResult
import kotlinx.coroutines.withContext
import org.bukkit.Bukkit

/**
 * Runs an actions.yml template against a report as the console. The command is rendered from
 * the stored report (never from anything typed in the interaction), every placeholder escaped,
 * and the player name validated rather than escaped. Bukkit is only touched on the main thread.
 */
class ActionRunner(private val core: KobanCore) {

    sealed interface Preview {
        data class Ready(val report: Report, val action: ActionTemplate, val command: String) : Preview
        data class Refused(val reason: String) : Preview
        data object NotFound : Preview
        data object UnknownAction : Preview
        data object Closed : Preview
        data class ClaimedByOther(val staff: String) : Preview
    }

    sealed interface RunResult {
        data class Ran(val command: String, val action: ActionTemplate, val resolved: TransitionResult?) : RunResult
        data class Failed(val command: String, val reason: String) : RunResult
        data class NotRun(val preview: Preview) : RunResult
    }

    /** What would run, without running it: shown on the confirmation step. */
    suspend fun preview(id: ReportId, key: String, actor: Actor, mayOverride: Boolean): Preview {
        val action = core.config.actions[key] ?: return Preview.UnknownAction
        val report = core.reports.find(id) ?: return Preview.NotFound
        if (report.status.isClosed) return Preview.Closed
        val claim = report.claim
        val staff = actor.staff
        if (claim != null && !mayOverride && (staff == null || !claim.by.sameAs(staff))) return Preview.ClaimedByOther(claim.by.name)
        val actions = core.config.actions
        val values = TemplateValues(
            player = report.reported.name,
            uuid = report.reported.uuid,
            reason = core.config.reasons.displayName(report.category),
            category = report.category,
            report = report.id.value,
            staff = actor.displayName,
            server = report.server,
        )
        return when (val rendered = action.template.render(values, actions.allowedNamePrefixes, actions.reasonFallback)) {
            is CommandTemplate.Rendered.Ok -> Preview.Ready(report, action, rendered.command)
            is CommandTemplate.Rendered.Refused -> Preview.Refused(rendered.reason)
        }
    }

    /** Renders again (the report may have changed since the preview), runs, records, resolves. */
    suspend fun run(id: ReportId, key: String, actor: Actor, mayOverride: Boolean): RunResult {
        val preview = preview(id, key, actor, mayOverride)
        if (preview !is Preview.Ready) return RunResult.NotRun(preview)
        val command = preview.command
        val detail = mapOf("action" to key, "command" to command, "via" to actor.source.name.lowercase())
        val ok = try {
            withContext(core.main) { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command) }
        } catch (e: RuntimeException) {
            core.audit.record(id, AuditAction.ACTION_FAILED, actor, detail + ("error" to (e.message ?: e.javaClass.simpleName)))
            core.logger.warning("Action '$key' on report #${id.value} failed: ${e.message}")
            return RunResult.Failed(command, e.message ?: e.javaClass.simpleName)
        }
        if (!ok) {
            core.audit.record(id, AuditAction.ACTION_FAILED, actor, detail + ("error" to "unknown command"))
            return RunResult.Failed(command, "the server doesn't know this command")
        }
        core.audit.record(id, AuditAction.ACTION_EXECUTED, actor, detail)
        core.logger.info("Report #${id.value}: ${actor.displayName} (${actor.source.name.lowercase()}) ran action '$key': $command")
        val resolved = if (preview.action.resolves) {
            core.reports.resolve(id, actor, Outcome.ACTION_TAKEN, "${preview.action.label}: $command", override = mayOverride || actor.source == Source.CONSOLE)
        } else {
            null
        }
        return RunResult.Ran(command, preview.action, resolved)
    }
}
