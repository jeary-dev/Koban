package dev.jeary.koban.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.jeary.koban.storage.migration.Migrator
import java.io.File
import java.util.logging.Logger

/** Where data lives, as configured under `storage:` in config.yml. */
sealed interface StorageSettings {

    data class Sqlite(val file: String = "koban.db") : StorageSettings

    data class Server(
        val flavour: Flavour,
        val host: String,
        val port: Int,
        val database: String,
        val username: String,
        val password: String,
        val poolSize: Int,
        val properties: Map<String, String>,
    ) : StorageSettings {
        enum class Flavour(val scheme: String, val driverClass: String) {
            MYSQL("mysql", "com.mysql.cj.jdbc.Driver"),
            MARIADB("mariadb", "org.mariadb.jdbc.Driver"),
        }
    }
}

/** Builds a pooled, migrated [Database] from [StorageSettings]. */
object DatabaseFactory {

    fun create(settings: StorageSettings, dataFolder: File, logger: Logger): Database {
        val database = when (settings) {
            is StorageSettings.Sqlite -> sqlite(File(dataFolder, settings.file), logger)
            is StorageSettings.Server -> server(settings, logger)
        }
        try {
            Migrator(database, logger).migrate()
        } catch (e: Exception) {
            database.close()
            throw e
        }
        return database
    }

    fun sqlite(file: File, logger: Logger, poolSize: Int = 4): Database {
        file.absoluteFile.parentFile?.mkdirs()
        val config = HikariConfig().apply {
            poolName = "Koban-SQLite"
            driverClassName = "org.sqlite.JDBC"
            jdbcUrl = "jdbc:sqlite:${file.absolutePath}"
            maximumPoolSize = poolSize
            // WAL lets readers run alongside the single writer.
            addDataSourceProperty("journal_mode", "WAL")
            addDataSourceProperty("synchronous", "NORMAL")
            addDataSourceProperty("foreign_keys", "true")
            addDataSourceProperty("busy_timeout", "10000")
            // Write transactions take the write lock at BEGIN, so two writers can never deadlock
            // upgrading from a read lock (the classic SQLITE_BUSY-ignores-busy_timeout trap).
            addDataSourceProperty("transaction_mode", "IMMEDIATE")
        }
        val pool = HikariDataSource(config)
        return Database(pool, Dialect.SQLITE, poolSize, logger, pool::close)
    }

    fun server(settings: StorageSettings.Server, logger: Logger): Database {
        val defaults = mapOf(
            "useUnicode" to "true",
            "characterEncoding" to "utf8",
            "rewriteBatchedStatements" to "true",
        ).takeIf { settings.flavour == StorageSettings.Server.Flavour.MYSQL } ?: emptyMap()
        val query = (defaults + settings.properties).entries.joinToString("&") { (k, v) -> "$k=$v" }
        val config = HikariConfig().apply {
            poolName = "Koban-${settings.flavour.name.lowercase()}"
            driverClassName = settings.flavour.driverClass
            jdbcUrl = "jdbc:${settings.flavour.scheme}://${settings.host}:${settings.port}/${settings.database}" +
                if (query.isNotEmpty()) "?$query" else ""
            username = settings.username
            password = settings.password
            maximumPoolSize = settings.poolSize
            minimumIdle = minOf(2, settings.poolSize)
            connectionTimeout = 10_000
            maxLifetime = 1_800_000
            // Row locks and conditional updates assume READ COMMITTED semantics for fresh reads.
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
            addDataSourceProperty("cachePrepStmts", "true")
            addDataSourceProperty("prepStmtCacheSize", "128")
        }
        val pool = HikariDataSource(config)
        return Database(pool, Dialect.MYSQL, settings.poolSize, logger, pool::close)
    }
}
