package dev.jeary.koban.storage.repo

import dev.jeary.koban.domain.actor.Actor
import dev.jeary.koban.domain.actor.Source
import dev.jeary.koban.domain.actor.StaffRef
import dev.jeary.koban.domain.audit.AuditAction
import dev.jeary.koban.domain.audit.AuditEntry
import dev.jeary.koban.domain.evidence.EvidenceSnapshot
import dev.jeary.koban.domain.report.Note
import dev.jeary.koban.domain.report.PlayerRef
import dev.jeary.koban.domain.report.ReportId
import dev.jeary.koban.storage.Row
import dev.jeary.koban.storage.Tx
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

/** The JSON format for stored evidence and audit details: tolerant of fields added later. */
internal val StorageJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
}

class EvidenceRepository {

    fun insert(tx: Tx, reportId: ReportId, reporter: UUID, snapshot: EvidenceSnapshot) {
        tx.update(
            "INSERT INTO evidence (report_id, reporter_uuid, captured_at, format, payload) VALUES (?, ?, ?, ?, ?)",
            reportId.value, reporter, snapshot.capturedAt, snapshot.format,
            StorageJson.encodeToString(EvidenceSnapshot.serializer(), snapshot),
        )
    }

    /** Every reporter's snapshot, oldest first. A row that can't be read is skipped, not fatal. */
    fun forReport(tx: Tx, reportId: ReportId): List<EvidenceSnapshot> =
        tx.query("SELECT payload FROM evidence WHERE report_id = ? ORDER BY captured_at, id", reportId.value) {
            it.string("payload")
        }.mapNotNull { payload ->
            try {
                StorageJson.decodeFromString(EvidenceSnapshot.serializer(), payload)
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
}

class NoteRepository {

    /** @return the new note's ID, or null if a note for this Discord message already exists. */
    fun insert(tx: Tx, note: Note): Long? {
        if (note.discordMessageId != null &&
            tx.long("SELECT COUNT(*) FROM report_notes WHERE discord_message_id = ?", note.discordMessageId) > 0
        ) {
            return null
        }
        return tx.insert(
            """
            INSERT INTO report_notes (report_id, source, author_uuid, author_discord, author_name, body, discord_message_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            note.reportId.value, note.source, note.author?.minecraft, note.author?.discord, note.authorName,
            note.body, note.discordMessageId, note.createdAt,
        )
    }

    fun forReport(tx: Tx, reportId: ReportId, limit: Int = 200): List<Note> =
        tx.query(
            "SELECT * FROM report_notes WHERE report_id = ? ORDER BY created_at, id LIMIT ?",
            reportId.value, limit, mapper = ::note,
        )

    private fun note(row: Row): Note {
        val uuid = row.uuidOrNull("author_uuid")
        val discord = row.longOrNull("author_discord")
        val name = row.string("author_name")
        return Note(
            id = row.long("id"),
            reportId = ReportId(row.long("report_id")),
            source = row.enum("source"),
            author = if (uuid != null || discord != null) StaffRef(name, uuid, discord) else null,
            authorName = name,
            body = row.string("body"),
            discordMessageId = row.longOrNull("discord_message_id"),
            createdAt = row.instant("created_at"),
        )
    }
}

class AuditRepository {

    fun insert(tx: Tx, reportId: ReportId?, action: AuditAction, actor: Actor, detail: Map<String, String>, at: Instant): Long =
        tx.insert(
            """
            INSERT INTO audit_log (report_id, action, source, actor_uuid, actor_discord, actor_name, detail, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            reportId?.value, action, actor.source, actor.staff?.minecraft, actor.staff?.discord, actor.displayName,
            StorageJson.encodeToString(DETAIL, detail), at,
        )

    fun forReport(tx: Tx, reportId: ReportId, limit: Int = 100): List<AuditEntry> =
        tx.query(
            "SELECT * FROM audit_log WHERE report_id = ? ORDER BY created_at, id LIMIT ?",
            reportId.value, limit, mapper = ::entry,
        )

    fun recent(tx: Tx, limit: Int): List<AuditEntry> =
        tx.query("SELECT * FROM audit_log ORDER BY created_at DESC, id DESC LIMIT ?", limit, mapper = ::entry)

    private fun entry(row: Row): AuditEntry {
        val source = row.enum<Source>("source")
        val uuid = row.uuidOrNull("actor_uuid")
        val discord = row.longOrNull("actor_discord")
        val name = row.string("actor_name")
        val staff = if (uuid != null || discord != null) StaffRef(name, uuid, discord) else null
        val detail = try {
            StorageJson.decodeFromString(DETAIL, row.string("detail"))
        } catch (_: SerializationException) {
            emptyMap()
        }
        return AuditEntry(
            id = row.long("id"),
            reportId = row.longOrNull("report_id")?.let(::ReportId),
            action = row.enum("action"),
            actor = Actor(source, staff, name),
            detail = detail,
            at = row.instant("created_at"),
        )
    }

    private companion object {
        val DETAIL = MapSerializer(String.serializer(), String.serializer())
    }
}

/** A linked pair of accounts. */
data class AccountLink(val minecraft: PlayerRef, val discord: Long, val linkedAt: Instant)

class LinkRepository {

    /**
     * Links [minecraft] to [discord], replacing any earlier link either account had.
     * @return the links that were replaced, for the audit log.
     */
    fun link(tx: Tx, minecraft: PlayerRef, discord: Long, at: Instant): List<AccountLink> {
        val replaced = tx.query(
            "SELECT * FROM account_links WHERE mc_uuid = ? OR discord_id = ?",
            minecraft.uuid, discord, mapper = ::link,
        )
        tx.update("DELETE FROM account_links WHERE mc_uuid = ? OR discord_id = ?", minecraft.uuid, discord)
        tx.update(
            "INSERT INTO account_links (mc_uuid, mc_name, discord_id, linked_at) VALUES (?, ?, ?, ?)",
            minecraft.uuid, minecraft.name, discord, at,
        )
        return replaced
    }

    fun byDiscord(tx: Tx, discord: Long): AccountLink? =
        tx.queryOne("SELECT * FROM account_links WHERE discord_id = ?", discord, mapper = ::link)

    fun byMinecraft(tx: Tx, minecraft: UUID): AccountLink? =
        tx.queryOne("SELECT * FROM account_links WHERE mc_uuid = ?", minecraft, mapper = ::link)

    fun unlinkMinecraft(tx: Tx, minecraft: UUID): Boolean =
        tx.update("DELETE FROM account_links WHERE mc_uuid = ?", minecraft) > 0

    fun unlinkDiscord(tx: Tx, discord: Long): Boolean =
        tx.update("DELETE FROM account_links WHERE discord_id = ?", discord) > 0

    private fun link(row: Row) = AccountLink(
        PlayerRef(row.uuid("mc_uuid"), row.string("mc_name")),
        row.long("discord_id"),
        row.instant("linked_at"),
    )
}

class PlayerRepository {

    fun seen(tx: Tx, player: PlayerRef, at: Instant) {
        tx.update(
            "INSERT INTO players (uuid, name, name_lower, last_seen) VALUES (?, ?, ?, ?) " +
                tx.dialect.onConflict(listOf("uuid"), "name = new(name), name_lower = new(name_lower), last_seen = new(last_seen)"),
            player.uuid, player.name, player.name.lowercase(), at,
        )
    }

    /** The player most recently seen with [name] (names can move between accounts). */
    fun byName(tx: Tx, name: String): PlayerRef? =
        tx.queryOne(
            "SELECT uuid, name FROM players WHERE name_lower = ? ORDER BY last_seen DESC",
            name.lowercase(),
        ) { PlayerRef(it.uuid("uuid"), it.string("name")) }

    fun byUuid(tx: Tx, uuid: UUID): PlayerRef? =
        tx.queryOne("SELECT uuid, name FROM players WHERE uuid = ?", uuid) { PlayerRef(it.uuid("uuid"), it.string("name")) }

    /** Names starting with [prefix], most recently seen first: for autocomplete. */
    fun search(tx: Tx, prefix: String, limit: Int): List<String> {
        // '!' as the escape character: a backslash means different things in MySQL and SQLite literals.
        val escaped = prefix.lowercase().replace("!", "!!").replace("%", "!%").replace("_", "!_")
        return tx.query(
            "SELECT name FROM players WHERE name_lower LIKE ? ESCAPE '!' ORDER BY last_seen DESC LIMIT ?",
            "$escaped%", limit,
        ) { it.string("name") }
    }
}
