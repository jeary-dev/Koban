package dev.jeary.koban.domain.permission

/**
 * What a member of staff may do. On Discord these map to roles (config.yml `discord.roles`); in
 * game to `koban.*` permissions.
 */
enum class StaffAction(val key: String) {
    /** See reports: `/reports open`, `/reports lookup`. */
    VIEW("view"),
    CLAIM("claim"),
    /** Resolve, dismiss and reopen. */
    RESOLVE("resolve"),
    /** Run an action template (warn, mute, ban …) as the console. */
    PUNISH("punish"),
    NOTE("note"),
    /** Take over another staff member's claim. */
    OVERRIDE("override"),
    /** Everything above, plus setup. */
    ADMIN("admin"),
    ;

    companion object {
        private val byKey = entries.associateBy { it.key }
        fun of(key: String): StaffAction? = byKey[key.lowercase()]
    }
}

/**
 * Maps Discord role IDs to [StaffAction]s and answers "may this member do that?".
 *
 * Checked on every interaction using the roles Discord sends with the interaction itself, so it
 * needs no member cache (and no privileged intent), and a role removed a second ago is already
 * gone.
 *
 * - [StaffAction.ADMIN] allows everything.
 * - Any action implies [StaffAction.VIEW]: you can't claim what you can't see.
 * - A member with no mapped role may do nothing.
 */
class RoleGate(grants: Map<StaffAction, Set<Long>>) {

    private val rolesFor: Map<StaffAction, Set<Long>> = grants.mapValues { it.value.toSet() }

    fun allows(memberRoles: Collection<Long>, action: StaffAction): Boolean {
        if (memberRoles.isEmpty()) return false
        val roles = memberRoles.toSet()
        fun has(a: StaffAction) = rolesFor[a].orEmpty().any { it in roles }
        if (has(StaffAction.ADMIN) || has(action)) return true
        return action == StaffAction.VIEW && StaffAction.entries.any { it != StaffAction.VIEW && has(it) }
    }

    /** Everything a member may do; used to decide which buttons to explain as unavailable. */
    fun allowed(memberRoles: Collection<Long>): Set<StaffAction> =
        StaffAction.entries.filterTo(LinkedHashSet()) { allows(memberRoles, it) }

    /** Every role ID mentioned in config, for `/koban setup` to check they exist. */
    val configuredRoles: Set<Long> get() = rolesFor.values.flatten().toSet()

    val isEmpty: Boolean get() = rolesFor.values.all { it.isEmpty() }
}
