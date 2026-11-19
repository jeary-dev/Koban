package dev.jeary.koban.api.event

import dev.jeary.koban.domain.actor.Actor
import dev.jeary.koban.domain.report.Change
import dev.jeary.koban.domain.report.Outcome
import dev.jeary.koban.domain.report.PlayerRef
import dev.jeary.koban.domain.report.Report
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * Base class of Koban's events. Every Koban event is fired on the main thread, whether the change
 * started in game, on Discord or through the API, so listeners can use the Bukkit API freely.
 */
abstract class KobanEvent : Event(false)

/** A cancellable Koban event. Cancelling it means the change is never stored. */
abstract class CancellableKobanEvent : KobanEvent(), Cancellable {
    private var cancelled = false
    override fun isCancelled(): Boolean = cancelled
    override fun setCancelled(cancel: Boolean) {
        cancelled = cancel
    }
}

/**
 * A player is reporting someone. [mergeInto] is the open report this one will join, or null when
 * it starts a new report. Cancel to refuse it: nothing is stored and no cooldown starts.
 */
class ReportCreateEvent(
    val reporter: PlayerRef,
    val reported: PlayerRef,
    /** The category key from reasons.yml. */
    val category: String,
    val details: String?,
    val mergeInto: Report?,
) : CancellableKobanEvent() {
    val isMerge: Boolean get() = mergeInto != null

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}

/** Staff are claiming a report, or taking over someone else's claim ([isTakeover]). */
class ReportClaimEvent(
    /** The report as it was before the claim. */
    val report: Report,
    val staff: Actor,
    val isTakeover: Boolean,
) : CancellableKobanEvent() {
    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}

/** Staff are closing a report: resolving it with an [outcome], or dismissing it ([isDismissal]). */
class ReportResolveEvent(
    /** The report as it was before closing. */
    val report: Report,
    val staff: Actor,
    val outcome: Outcome,
    val isDismissal: Boolean,
) : CancellableKobanEvent() {
    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}

/**
 * Something about a report changed and was stored: a new reporter joined, it was claimed,
 * released, closed, reopened, or a note was added. Not cancellable; it has already happened.
 */
class ReportUpdateEvent(
    /** The report after the change. */
    val report: Report,
    val kind: Kind,
    /** Who caused it: the reporter for new reports, staff otherwise. */
    val actor: Actor?,
) : KobanEvent() {
    enum class Kind { CREATED, MERGED, CLAIMED, TAKEN_OVER, UNCLAIMED, RESOLVED, DISMISSED, REOPENED, NOTE_ADDED }

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS

        fun kindOf(change: Change): Kind = when (change) {
            Change.CLAIMED -> Kind.CLAIMED
            Change.TAKEN_OVER -> Kind.TAKEN_OVER
            Change.UNCLAIMED -> Kind.UNCLAIMED
            Change.RESOLVED -> Kind.RESOLVED
            Change.DISMISSED -> Kind.DISMISSED
            Change.REOPENED -> Kind.REOPENED
        }
    }
}
