package dev.jeary.koban.storage

/**
 * The few places where SQLite and MySQL/MariaDB SQL differ. Everything else is written once in
 * portable SQL.
 */
enum class Dialect(
    /** Folder under `db/migration/` holding this dialect's migration scripts. */
    val migrationFolder: String,
) {
    SQLITE("sqlite"),
    MYSQL("mysql"),
    ;

    /** `INSERT … ` that silently skips rows violating a unique key. */
    val insertIgnore: String
        get() = when (this) {
            SQLITE -> "INSERT OR IGNORE INTO"
            MYSQL -> "INSERT IGNORE INTO"
        }

    /**
     * An upsert tail for `INSERT INTO t (cols) VALUES (…)`: on a key conflict, run [assignments]
     * where `new(col)` refers to the value that was being inserted.
     */
    fun onConflict(keys: List<String>, assignments: String): String = when (this) {
        SQLITE -> "ON CONFLICT(${keys.joinToString()}) DO UPDATE SET " + assignments.replace(NEW, "excluded.$1")
        MYSQL -> "ON DUPLICATE KEY UPDATE " + assignments.replace(NEW, "VALUES($1)")
    }

    /**
     * Whether [error] is transient — the transaction lost a race for a lock and simply needs to
     * run again (SQLite busy/locked, MySQL deadlock or lock-wait timeout).
     */
    fun isRetryable(error: java.sql.SQLException): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is java.sql.SQLException) {
                when (this) {
                    SQLITE -> if (current.errorCode == SQLITE_BUSY || current.errorCode == SQLITE_LOCKED ||
                        current.message?.contains("SQLITE_BUSY") == true
                    ) return true
                    MYSQL -> if (current.errorCode == MYSQL_DEADLOCK || current.errorCode == MYSQL_LOCK_WAIT ||
                        current.sqlState == "40001"
                    ) return true
                }
            }
            current = current.cause
        }
        return false
    }

    private companion object {
        val NEW = Regex("""new\((\w+)\)""")
        const val SQLITE_BUSY = 5
        const val SQLITE_LOCKED = 6
        const val MYSQL_DEADLOCK = 1213
        const val MYSQL_LOCK_WAIT = 1205
    }
}
