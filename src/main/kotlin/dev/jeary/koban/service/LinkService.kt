package dev.jeary.koban.service

import dev.jeary.koban.domain.actor.Actor
import dev.jeary.koban.domain.audit.AuditAction
import dev.jeary.koban.domain.link.LinkCodes
import dev.jeary.koban.domain.report.PlayerRef
import dev.jeary.koban.domain.report.ReportId
import dev.jeary.koban.storage.Database
import dev.jeary.koban.storage.repo.AccountLink
import dev.jeary.koban.storage.repo.AuditRepository
import dev.jeary.koban.storage.repo.LinkRepository
import dev.jeary.koban.storage.repo.PlayerRepository
import java.time.Clock
import java.util.UUID

sealed interface LinkResult {
    data class Linked(val link: AccountLink) : LinkResult
    data object InvalidCode : LinkResult
}

/**
 * Ties a Minecraft account to a Discord account, so every action in the audit log carries both
 * identities. In game: `/koban link` issues a code. On Discord: `/link <code>` redeems it.
 */
class LinkService(
    private val database: Database,
    private val clock: Clock,
    val codes: LinkCodes,
) {
    private val links = LinkRepository()
    private val audit = AuditRepository()

    fun issueCode(player: PlayerRef): String = codes.issue(player.uuid, player.name)

    suspend fun redeem(code: String, discordId: Long, discordName: String): LinkResult {
        val pending = codes.redeem(code) ?: return LinkResult.InvalidCode
        val player = PlayerRef(pending.minecraft, pending.name)
        val link = database.write { tx ->
            val now = clock.instant()
            val replaced = links.link(tx, player, discordId, now)
            val actor = Actor.discord(discordId, discordName, player.uuid, player.name)
            val detail = buildMap {
                put("minecraft", player.name)
                put("discord", discordId.toString())
                replaced.filter { it.minecraft.uuid != player.uuid || it.discord != discordId }
                    .takeIf { it.isNotEmpty() }
                    ?.let { put("replaced", it.joinToString { r -> "${r.minecraft.name}/${r.discord}" }) }
            }
            audit.insert(tx, null, AuditAction.ACCOUNT_LINKED, actor, detail, now)
            AccountLink(player, discordId, now)
        }
        return LinkResult.Linked(link)
    }

    suspend fun byDiscord(discordId: Long): AccountLink? = database.read { links.byDiscord(it, discordId) }

    suspend fun byMinecraft(uuid: UUID): AccountLink? = database.read { links.byMinecraft(it, uuid) }

    suspend fun unlinkMinecraft(uuid: UUID, actor: Actor): Boolean = database.write { tx ->
        val existing = links.byMinecraft(tx, uuid) ?: return@write false
        links.unlinkMinecraft(tx, uuid)
        audit.insert(tx, null, AuditAction.ACCOUNT_UNLINKED, actor, mapOf("minecraft" to existing.minecraft.name, "discord" to existing.discord.toString()), clock.instant())
        true
    }

    suspend fun unlinkDiscord(discordId: Long, actor: Actor): Boolean = database.write { tx ->
        val existing = links.byDiscord(tx, discordId) ?: return@write false
        links.unlinkDiscord(tx, discordId)
        audit.insert(tx, null, AuditAction.ACCOUNT_UNLINKED, actor, mapOf("minecraft" to existing.minecraft.name, "discord" to existing.discord.toString()), clock.instant())
        true
    }
}

/** The audit log, for actions recorded outside a report transition (teleports, actions, reloads). */
class AuditService(private val database: Database, private val clock: Clock) {
    private val audit = AuditRepository()

    suspend fun record(reportId: ReportId?, action: AuditAction, actor: Actor, detail: Map<String, String> = emptyMap()) {
        database.write { audit.insert(it, reportId, action, actor, detail, clock.instant()) }
    }

    suspend fun recent(limit: Int) = database.read { audit.recent(it, limit) }
}

/** The name cache: who has played here, for lookups and autocomplete. */
class PlayerDirectory(private val database: Database, private val clock: Clock) {
    private val players = PlayerRepository()

    suspend fun seen(player: PlayerRef) = database.write { players.seen(it, player, clock.instant()) }

    suspend fun byName(name: String): PlayerRef? = database.read { players.byName(it, name) }

    suspend fun byUuid(uuid: UUID): PlayerRef? = database.read { players.byUuid(it, uuid) }

    suspend fun search(prefix: String, limit: Int = 25): List<String> = database.read { players.search(it, prefix, limit) }
}
