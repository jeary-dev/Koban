package dev.jeary.koban.storage

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.time.Instant
import java.util.UUID

/**
 * A thin, typed layer over one JDBC [Connection], valid for the duration of a single
 * [Database.read] or [Database.write] block. No ORM: every query is plain, reviewable SQL.
 *
 * Parameters are bound by type: [UUID] as text, [Instant] as epoch milliseconds, enums by name,
 * booleans as 0/1, `null` as SQL NULL. Timestamps are stored as
 * epoch milliseconds so they compare and index identically on every database.
 */
class Tx internal constructor(val connection: Connection, val dialect: Dialect) {

    fun update(sql: String, vararg params: Any?): Int =
        connection.prepareStatement(sql).use { st ->
            bind(st, params)
            st.executeUpdate()
        }

    /** Runs an INSERT and returns the generated key. */
    fun insert(sql: String, vararg params: Any?): Long =
        connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { st ->
            bind(st, params)
            st.executeUpdate()
            st.generatedKeys.use { keys ->
                check(keys.next()) { "INSERT returned no generated key" }
                keys.getLong(1)
            }
        }

    /** Executes [sql] once per parameter row, as a JDBC batch. */
    fun batch(sql: String, rows: List<List<Any?>>): IntArray {
        if (rows.isEmpty()) return IntArray(0)
        return connection.prepareStatement(sql).use { st ->
            for (row in rows) {
                bind(st, row.toTypedArray())
                st.addBatch()
            }
            st.executeBatch()
        }
    }

    fun <T> query(sql: String, vararg params: Any?, mapper: (Row) -> T): List<T> =
        connection.prepareStatement(sql).use { st ->
            bind(st, params)
            st.executeQuery().use { rs ->
                val row = Row(rs)
                buildList { while (rs.next()) add(mapper(row)) }
            }
        }

    fun <T> queryOne(sql: String, vararg params: Any?, mapper: (Row) -> T): T? =
        connection.prepareStatement(sql).use { st ->
            bind(st, params)
            st.maxRows = 1
            st.executeQuery().use { rs -> if (rs.next()) mapper(Row(rs)) else null }
        }

    fun long(sql: String, vararg params: Any?): Long =
        queryOne(sql, *params) { it.long(1) } ?: 0L

    fun execute(sql: String) {
        connection.createStatement().use { it.execute(sql) }
    }

    private fun bind(st: PreparedStatement, params: Array<out Any?>) {
        params.forEachIndexed { index, value ->
            val i = index + 1
            when (value) {
                null -> st.setObject(i, null)
                is String -> st.setString(i, value)
                is Long -> st.setLong(i, value)
                is Int -> st.setInt(i, value)
                is Short -> st.setShort(i, value)
                is Double -> st.setDouble(i, value)
                is Boolean -> st.setInt(i, if (value) 1 else 0)
                is ByteArray -> st.setBytes(i, value)
                is UUID -> st.setString(i, value.toString())
                is Instant -> st.setLong(i, value.toEpochMilli())
                is Enum<*> -> st.setString(i, value.name)
                else -> throw IllegalArgumentException("Unsupported SQL parameter type ${value::class.qualifiedName}")
            }
        }
    }
}

/** A cursor-positioned view of the current result row with typed accessors. */
class Row internal constructor(private val rs: ResultSet) {
    fun long(column: String): Long = rs.getLong(column)
    fun long(index: Int): Long = rs.getLong(index)
    fun longOrNull(column: String): Long? = rs.getLong(column).takeUnless { rs.wasNull() }
    fun int(column: String): Int = rs.getInt(column)
    fun intOrNull(column: String): Int? = rs.getInt(column).takeUnless { rs.wasNull() }
    fun double(column: String): Double = rs.getDouble(column)
    fun string(column: String): String = rs.getString(column) ?: error("column $column is NULL")
    fun stringOrNull(column: String): String? = rs.getString(column)
    fun bytes(column: String): ByteArray = rs.getBytes(column) ?: error("column $column is NULL")
    fun bool(column: String): Boolean = rs.getInt(column) != 0
    fun uuid(column: String): UUID = UUID.fromString(string(column))
    fun uuidOrNull(column: String): UUID? = stringOrNull(column)?.let(UUID::fromString)
    fun instant(column: String): Instant = Instant.ofEpochMilli(long(column))
    fun instantOrNull(column: String): Instant? = longOrNull(column)?.let(Instant::ofEpochMilli)
    inline fun <reified E : Enum<E>> enum(column: String): E = enumValueOf(string(column))
    inline fun <reified E : Enum<E>> enumOrNull(column: String): E? = stringOrNull(column)?.let { enumValueOf<E>(it) }
}
