package io.aequicor.heartbeat.core.datastore.impl

import androidx.room.RoomDatabase
import androidx.room.immediateTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import io.aequicor.heartbeat.core.datastore.RecordRetention
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.concurrent.Volatile

/**
 * Retention of the rows of one database, also its Room callback (logs `DB`).
 *
 * Tables with all [RecordRetention] columns are found when the database opens. On open — before any query of the
 * feature — expired rows and rows of events fired while it was closed ([journal]) are deleted on the opening
 * connection; afterwards by [purgeExpired] (timer) and [purgeEvent].
 */
internal class DatabaseRetention(
    private val label: String,
    private val clock: RetentionClock,
    private val journal: () -> Map<String, Long>,
) : RoomDatabase.Callback() {

    private val log = Log.tag(DB_LOG_TAG)

    /** Tables with retention columns; known after the database has been opened. */
    @Volatile
    var tables: List<String> = emptyList()
        private set

    override fun onCreate(connection: SQLiteConnection) {
        log.i { "$label: created" }
    }

    override fun onDestructiveMigration(connection: SQLiteConnection) {
        log.w { "$label: destructive migration, all data dropped" }
    }

    override fun onOpen(connection: SQLiteConnection) {
        tables = findRetentionTables(connection)
        val now = clock.now()
        val statements = expiredDeletes(now) + journal().flatMap { (event, firedAt) -> eventDeletes(event, firedAt) }
        val removed = statements.sumOf { connection.execute(it) }
        log.i { "$label: opened, version ${connection.userVersion()}, retention tables $tables, purged $removed rows" }
    }

    /** Deletes the rows whose deadline has passed. */
    suspend fun purgeExpired(db: RoomDatabase): Long {
        val removed = write(db, expiredDeletes(clock.now()))
        if (removed > 0) log.i { "$label: purged $removed expired rows" }
        return removed
    }

    /** Deletes the rows of [event] written up to [firedAt]. */
    suspend fun purgeEvent(db: RoomDatabase, event: String, firedAt: Long): Long {
        val removed = write(db, eventDeletes(event, firedAt))
        log.i { "$label: event $event purged $removed rows" }
        return removed
    }

    /** Nearest deadline of the rows; re-emitted whenever a retention table changes. */
    fun nextDeadline(db: RoomDatabase): Flow<Long?> {
        val watched = tables
        if (watched.isEmpty()) return flowOf(null)
        @Suppress("SpreadOperator") // Room takes the table names as varargs; a handful of names, once per open
        return db.invalidationTracker.createFlow(*watched.toTypedArray()).map {
            db.useReaderConnection { connection ->
                connection.usePrepared(nearestDeadlineQuery(watched)) { statement ->
                    if (statement.step() && !statement.isNull(0)) statement.getLong(0) else null
                }
            }
        }
    }

    private suspend fun write(db: RoomDatabase, statements: List<Sql>): Long {
        if (statements.isEmpty()) return 0
        val removed = db.useWriterConnection { connection ->
            connection.immediateTransaction {
                var total = 0L
                for (sql in statements) {
                    usePrepared(sql.text) { statement -> statement.bind(sql.args).step() }
                    total += usePrepared(CHANGES) { statement -> if (statement.step()) statement.getLong(0) else 0L }
                }
                total
            }
        }
        // Room's observers are refreshed by its own DAOs; a raw write has to trigger them explicitly.
        @Suppress("SpreadOperator") // Room takes the table names as varargs; only after rows were deleted
        if (removed > 0) db.invalidationTracker.refresh(*tables.toTypedArray())
        return removed
    }

    private fun expiredDeletes(now: Long): List<Sql> = tables.map { table ->
        Sql("DELETE FROM ${quote(table)} WHERE ${RecordRetention.EXPIRES_AT_COLUMN} <= ?", listOf(now))
    }

    private fun eventDeletes(event: String, firedAt: Long): List<Sql> = tables.map { table ->
        Sql(
            "DELETE FROM ${quote(table)} WHERE ${RecordRetention.EVENT_COLUMN} = ? " +
                "AND ${RecordRetention.CREATED_AT_COLUMN} <= ?",
            listOf(event, firedAt),
        )
    }

    private fun nearestDeadlineQuery(tables: List<String>): String {
        val perTable = tables.map { "SELECT MIN(${RecordRetention.EXPIRES_AT_COLUMN}) AS deadline FROM ${quote(it)}" }
        return "SELECT MIN(deadline) FROM (${perTable.joinToString(" UNION ALL ")})"
    }

    /** A statement with positional arguments (`String` or `Long`). */
    private data class Sql(val text: String, val args: List<Any>)

    private fun SQLiteStatement.bind(args: List<Any>): SQLiteStatement = apply {
        args.forEachIndexed { index, arg ->
            when (arg) {
                is String -> bindText(index + 1, arg)
                is Long -> bindLong(index + 1, arg)
                else -> error("unsupported argument type ${arg::class}")
            }
        }
    }

    private fun SQLiteConnection.execute(sql: Sql): Long {
        prepare(sql.text).use { it.bind(sql.args).step() }
        return prepare(CHANGES).use { if (it.step()) it.getLong(0) else 0L }
    }

    private fun SQLiteConnection.userVersion(): Long =
        prepare("PRAGMA user_version").use { if (it.step()) it.getLong(0) else 0L }

    private fun findRetentionTables(connection: SQLiteConnection): List<String> {
        val names = connection.prepare(TABLES_QUERY).use { statement ->
            buildList { while (statement.step()) add(statement.getText(0)) }
        }
        return names.filter { table ->
            val columns = connection.prepare("PRAGMA table_info(${quote(table)})").use { statement ->
                buildSet { while (statement.step()) add(statement.getText(COLUMN_NAME_INDEX)) }
            }
            columns.containsAll(RETENTION_COLUMNS)
        }
    }

    private companion object {
        const val CHANGES = "SELECT changes()"
        const val COLUMN_NAME_INDEX = 1 // PRAGMA table_info: cid, name, type, notnull, dflt_value, pk
        const val TABLES_QUERY = "SELECT name FROM sqlite_master WHERE type = 'table' " +
            "AND name NOT GLOB 'sqlite_*' " +
            "AND name NOT IN ('room_master_table', 'room_table_modification_log', 'android_metadata')"
        val RETENTION_COLUMNS = setOf(
            RecordRetention.CREATED_AT_COLUMN,
            RecordRetention.EXPIRES_AT_COLUMN,
            RecordRetention.EVENT_COLUMN,
        )

        fun quote(identifier: String): String = "\"" + identifier.replace("\"", "\"\"") + "\""
    }
}
