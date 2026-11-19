package dev.jeary.koban.storage.migration

import dev.jeary.koban.storage.Database
import dev.jeary.koban.storage.Dialect
import java.security.MessageDigest
import java.time.Instant
import java.util.logging.Logger

/** One versioned, forward-only schema change, stored as `db/migration/<dialect>/V<n>__<name>.sql`. */
data class Migration(val version: Int, val name: String) {
    fun resourcePath(dialect: Dialect) = "db/migration/${dialect.migrationFolder}/V${version}__$name.sql"
}

class MigrationException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * Applies pending [Migration]s in order and records each in `koban_migrations` with a SHA-256
 * checksum of its script. On every start the checksums of already-applied migrations are
 * compared against the bundled scripts: an edited migration is refused instead of silently
 * diverging from what the database actually contains.
 *
 * On MySQL a named lock (`GET_LOCK`) serialises servers that start against the same database at
 * the same time.
 */
class Migrator(
    private val database: Database,
    private val logger: Logger,
    private val migrations: List<Migration> = ALL,
    private val loadResource: (String) -> String? = ::bundledResource,
) {

    /** @return the migrations that were applied by this call. */
    fun migrate(): List<Migration> {
        if (database.dialect != Dialect.MYSQL) return migrateUnlocked()
        // GET_LOCK belongs to the session, so hold one connection for the whole run while the
        // migration itself uses others from the pool.
        return database.readBlocking { lock ->
            if (lock.long("SELECT GET_LOCK(?, ?)", LOCK_NAME, LOCK_TIMEOUT_SECONDS) != 1L) {
                throw MigrationException("Timed out waiting for another server to finish migrating the database")
            }
            try {
                migrateUnlocked()
            } finally {
                runCatching { lock.long("SELECT RELEASE_LOCK(?)", LOCK_NAME) }
            }
        }
    }

    private fun migrateUnlocked(): List<Migration> {
        val dialect = database.dialect
        database.writeBlocking { tx ->
            tx.execute(
                """
                CREATE TABLE IF NOT EXISTS koban_migrations (
                    version     INT          NOT NULL PRIMARY KEY,
                    description VARCHAR(200) NOT NULL,
                    checksum    CHAR(64)     NOT NULL,
                    applied_at  BIGINT       NOT NULL
                )
                """.trimIndent(),
            )
        }
        val applied = database.readBlocking { tx ->
            tx.query("SELECT version, checksum FROM koban_migrations") { it.int("version") to it.string("checksum") }.toMap()
        }

        val known = migrations.associateBy { it.version }
        applied.keys.filterNot { it in known }.maxOrNull()?.let { newest ->
            throw MigrationException(
                "The database schema (v$newest) is newer than this version of Koban supports. " +
                    "Update the plugin, or restore a backup made with the older version.",
            )
        }

        val pending = ArrayList<Migration>()
        for (migration in migrations.sortedBy { it.version }) {
            val script = loadScript(migration, dialect)
            val checksum = sha256(script)
            val recorded = applied[migration.version]
            if (recorded == null) {
                apply(migration, script, checksum)
                pending += migration
            } else if (recorded != checksum) {
                throw MigrationException(
                    "Migration V${migration.version} (${migration.name}) has changed since it was applied " +
                        "(checksum mismatch). Bundled migrations must never be edited.",
                )
            }
        }
        return pending
    }

    private fun apply(migration: Migration, script: String, checksum: String) {
        logger.info("Applying database migration V${migration.version}: ${migration.name.replace('_', ' ')}")
        try {
            database.writeBlocking { tx ->
                splitStatements(script).forEach(tx::execute)
                tx.update(
                    "INSERT INTO koban_migrations (version, description, checksum, applied_at) VALUES (?, ?, ?, ?)",
                    migration.version, migration.name, checksum, Instant.now(),
                )
            }
        } catch (e: Exception) {
            throw MigrationException("Migration V${migration.version} (${migration.name}) failed: ${e.message}", e)
        }
    }

    private fun loadScript(migration: Migration, dialect: Dialect): String =
        loadResource(migration.resourcePath(dialect))
            ?: throw MigrationException("Missing migration script ${migration.resourcePath(dialect)}")

    companion object {
        /** Every migration, in order. Append only: never edit or remove an entry once released. */
        val ALL: List<Migration> = listOf(
            Migration(1, "initial_schema"),
        )

        val LATEST: Int get() = ALL.maxOf { it.version }

        private const val LOCK_NAME = "koban_migrations"
        private const val LOCK_TIMEOUT_SECONDS = 60

        /** Splits a script into statements on `;` at end of line, dropping `--` comment lines. */
        fun splitStatements(script: String): List<String> =
            script.lineSequence()
                .filterNot { it.trimStart().startsWith("--") }
                .joinToString("\n")
                .split(Regex(""";\s*(\n|$)"""))
                .map { it.trim() }
                .filter { it.isNotEmpty() }

        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(text.replace("\r\n", "\n").toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        private fun bundledResource(path: String): String? =
            Migrator::class.java.classLoader.getResourceAsStream(path)?.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
