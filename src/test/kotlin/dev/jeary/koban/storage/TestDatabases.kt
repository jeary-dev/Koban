package dev.jeary.koban.storage

import org.junit.jupiter.api.extension.ConditionEvaluationResult
import org.junit.jupiter.api.extension.ExecutionCondition
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.testcontainers.DockerClientFactory
import org.testcontainers.mysql.MySQLContainer
import java.nio.file.Files
import java.util.logging.Logger

/**
 * Databases for storage tests. Each call returns a fresh, migrated, empty database.
 *
 * - SQLite: a temporary file (always available).
 * - MySQL: one Testcontainers MySQL 8.4 container shared by the whole run, with a new schema
 *   per call. Requires Docker; tests using it are annotated [RequiresDocker].
 */
object TestDatabases {

    private val logger: Logger = Logger.getLogger("koban-test")

    fun sqlite(poolSize: Int = 4): Database {
        val file = Files.createTempFile("koban-test", ".db").toFile().apply { deleteOnExit() }
        return DatabaseFactory.sqlite(file, logger, poolSize).also { migrate(it) }
    }

    private val mysql: MySQLContainer by lazy {
        MySQLContainer("mysql:8.4")
            .withDatabaseName("koban")
            .withUsername("root")
            .withPassword("test")
            .withCommand("--innodb-lock-wait-timeout=10")
            .apply { start() }
    }

    private var schemaCounter = 0

    @Synchronized
    fun mysql(poolSize: Int = 8): Database {
        val container = mysql
        val schema = "koban_${++schemaCounter}"
        container.createConnection("").use { it.createStatement().execute("CREATE DATABASE $schema") }
        val settings = StorageSettings.Server(
            flavour = StorageSettings.Server.Flavour.MYSQL,
            host = container.host,
            port = container.firstMappedPort,
            database = schema,
            username = container.username,
            password = container.password,
            poolSize = poolSize,
            properties = mapOf("allowPublicKeyRetrieval" to "true", "useSSL" to "false"),
        )
        return DatabaseFactory.server(settings, logger).also { migrate(it) }
    }

    fun create(dialect: Dialect, poolSize: Int = 6): Database = when (dialect) {
        Dialect.SQLITE -> sqlite(poolSize)
        Dialect.MYSQL -> mysql(poolSize)
    }

    private fun migrate(database: Database) {
        dev.jeary.koban.storage.migration.Migrator(database, logger).migrate()
    }
}

/**
 * Skips (rather than fails) a test class when Docker isn't available, with a message saying so.
 * CI runs these; a laptop without Docker still gets a green build from the SQLite suite.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ExtendWith(DockerCondition::class)
annotation class RequiresDocker

class DockerCondition : ExecutionCondition {
    override fun evaluateExecutionCondition(context: ExtensionContext): ConditionEvaluationResult =
        if (dockerAvailable) {
            ConditionEvaluationResult.enabled("Docker is available")
        } else {
            ConditionEvaluationResult.disabled(
                "MySQL tests skipped: Docker is not available on this machine. They run in CI; " +
                    "start Docker to run them locally.",
            )
        }

    private companion object {
        val dockerAvailable: Boolean by lazy {
            runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
        }
    }
}
