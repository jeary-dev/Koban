package dev.jeary.koban.command

/** Every permission node Koban checks. plugin.yml declares the same nodes with their defaults. */
object Permissions {
    /** File reports with /report. Everyone, by default. */
    const val REPORT = "koban.report"

    /** Can't be reported (staff, say). */
    const val EXEMPT = "koban.exempt"
    const val BYPASS_COOLDOWN = "koban.bypass.cooldown"
    const val BYPASS_LIMIT = "koban.bypass.limit"

    /** Live alerts for new reports and the open-report summary on join. */
    const val NOTIFY = "koban.notify"

    /** Open /reports and view reports. */
    const val STAFF = "koban.staff"
    const val CLAIM = "koban.claim"
    const val RESOLVE = "koban.resolve"
    const val NOTE = "koban.note"
    const val TELEPORT = "koban.teleport"
    const val HISTORY = "koban.history"
    const val PUNISH = "koban.punish"

    /** Act on reports claimed by someone else. */
    const val OVERRIDE = "koban.override"

    /** /koban reload, setup and debug. */
    const val ADMIN = "koban.admin"

    /** /koban link: link a Discord account. */
    const val LINK = "koban.link"
}
