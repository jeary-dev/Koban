package dev.jeary.koban.storage

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.ThreadLocalRandom
import java.util.logging.Logger
import javax.sql.DataSource

/**
 * The single entry point for database access.
 *
 * - [read] runs in auto-commit mode (each statement sees the latest committed data).
 * - [write] runs the block in **one transaction**: it commits if the block returns and rolls
 *   back if it throws. Transient lock conflicts (SQLite busy, MySQL deadlock) are retried with
 *   jittered backoff; any other exception — including deliberate aborts such as
 *   [TransactionAborted] — rolls back and propagates immediately.
 *
 * All work runs on [dispatcher], a bounded slice of `Dispatchers.IO` sized to the connection
 * pool, so callers can never block the server thread on JDBC — the `suspend` signature makes
 * that impossible to do by accident.
 */
class Database(
    private val dataSource: DataSource,
    val dialect: Dialect,
    parallelism: Int,
    private val logger: Logger,
    private val onClose: () -> Unit = {},
) : AutoCloseable {

    val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(parallelism, "koban-db")

    suspend fun <T> read(block: (Tx) -> T): T = withContext(dispatcher) { readBlocking(block) }

    suspend fun <T> write(block: (Tx) -> T): T = withContext(dispatcher) { writeBlocking(block) }

    /** Blocking variant for startup, shutdown and tests. Never call from the server thread. */
    fun <T> readBlocking(block: (Tx) -> T): T =
        dataSource.connection.use { connection ->
            connection.autoCommit = true
            block(Tx(connection, dialect))
        }

    /** Blocking variant for startup, shutdown and tests. Never call from the server thread. */
    fun <T> writeBlocking(block: (Tx) -> T): T {
        var attempt = 1
        while (true) {
            try {
                return dataSource.connection.use { connection -> inTransaction(connection, block) }
            } catch (e: SQLException) {
                if (attempt >= MAX_ATTEMPTS || !dialect.isRetryable(e)) throw e
                val backoff = BASE_BACKOFF_MS * (1L shl (attempt - 1))
                Thread.sleep(backoff + ThreadLocalRandom.current().nextLong(backoff))
                attempt++
            }
        }
    }

    private fun <T> inTransaction(connection: Connection, block: (Tx) -> T): T {
        connection.autoCommit = false
        try {
            val result = block(Tx(connection, dialect))
            connection.commit()
            return result
        } catch (e: Throwable) {
            try {
                connection.rollback()
            } catch (rollbackError: SQLException) {
                e.addSuppressed(rollbackError)
            }
            throw e
        } finally {
            try {
                connection.autoCommit = true
            } catch (e: SQLException) {
                logger.fine { "Could not restore auto-commit: ${e.message}" }
            }
        }
    }

    override fun close() = onClose()

    companion object {
        const val MAX_ATTEMPTS = 6
        private const val BASE_BACKOFF_MS = 15L
    }
}

/**
 * Thrown inside a [Database.write] block to roll the transaction back on purpose — for example
 * when an optimistic-lock check finds the row has changed. Never retried.
 */
open class TransactionAborted(message: String) : RuntimeException(message)
