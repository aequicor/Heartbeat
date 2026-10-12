package io.aequicor.heartbeat.feature.contextwindowoptimization.impl.data

import androidx.room.ConstructedBy
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.Transaction
import androidx.room.Upsert
import io.aequicor.heartbeat.core.datastore.DatabaseSpec
import io.aequicor.heartbeat.core.datastore.RecordRetention

/**
 * The progress ledger of one session: [session] and [ledger] are JSON, [updatedAt] orders the rows for the cap.
 * The ledger text is user data and never logged.
 */
@Entity(tableName = "ledgers", indices = [Index(RecordRetention.EXPIRES_AT_COLUMN), Index("updatedAt")])
internal data class LedgerEntity(
    @PrimaryKey val session: String,
    val ledger: String,
    val updatedAt: Long,
    @Embedded val retention: RecordRetention,
) {
    override fun toString(): String = "LedgerEntity(chars=${ledger.length})"
}

/** One offloaded tool output; [session] is JSON and [text] user data that is never logged. */
@Entity(tableName = "outputs", indices = [Index(RecordRetention.EXPIRES_AT_COLUMN)])
internal data class OutputEntity(
    @PrimaryKey val id: String,
    val session: String,
    val tool: String,
    val text: String,
    val chars: Int,
    @Embedded val retention: RecordRetention,
) {
    override fun toString(): String = "OutputEntity(tool=$tool, chars=$chars)"
}

/** Size of a stored output, newest first, for the total cap. */
internal data class OutputSize(val id: String, val chars: Int)

/** Ledgers, one row per session; at most a capped number of the most recently updated rows stays. */
@Dao
internal abstract class LedgerDao {
    @Query("SELECT * FROM ledgers WHERE hb_expires_at IS NULL OR hb_expires_at > :now")
    abstract suspend fun all(now: Long): List<LedgerEntity>

    @Upsert
    protected abstract suspend fun upsert(row: LedgerEntity)

    @Query("DELETE FROM ledgers WHERE session = :session")
    abstract suspend fun delete(session: String)

    @Query("DELETE FROM ledgers WHERE session NOT IN (SELECT session FROM ledgers ORDER BY updatedAt DESC LIMIT :max)")
    protected abstract suspend fun keepNewest(max: Int)

    /** Writes [row] and drops the least recently updated rows beyond [max], in one transaction. */
    @Transaction
    open suspend fun save(row: LedgerEntity, max: Int) {
        upsert(row)
        keepNewest(max)
    }
}

/** Offloaded outputs; the newest rows stay within a count and a total size. */
@Dao
internal abstract class OutputDao {
    @Query("SELECT * FROM outputs WHERE id = :id AND (hb_expires_at IS NULL OR hb_expires_at > :now)")
    abstract suspend fun get(id: String, now: Long): OutputEntity?

    @Insert
    protected abstract suspend fun insert(row: OutputEntity)

    @Query("SELECT id, chars FROM outputs ORDER BY rowid DESC")
    protected abstract suspend fun sizesNewestFirst(): List<OutputSize>

    @Query("DELETE FROM outputs WHERE id IN (:ids)")
    protected abstract suspend fun delete(ids: List<String>)

    /**
     * Inserts [row] and drops the oldest rows beyond [maxRows] or beyond [maxChars] in total, in one transaction.
     * Returns the number of rows that stay.
     */
    @Transaction
    open suspend fun insertCapped(row: OutputEntity, maxRows: Int, maxChars: Long): Int {
        insert(row)
        val sizes = sizesNewestFirst()
        var total = 0L
        val kept = sizes.withIndex().takeWhile { (index, size) ->
            total += size.chars
            index < maxRows && total <= maxChars
        }.size
        val dropped = sizes.drop(kept).map { it.id }
        if (dropped.isNotEmpty()) delete(dropped)
        return kept
    }
}

/** The feature's profile database: progress ledgers and offloaded tool outputs. */
@Database(entities = [LedgerEntity::class, OutputEntity::class], version = 1)
@ConstructedBy(ContextOptimizationDatabaseConstructor::class)
internal abstract class ContextOptimizationDatabase : RoomDatabase() {
    abstract fun ledgers(): LedgerDao

    abstract fun outputs(): OutputDao
}

@Suppress("KotlinNoActualForExpect") // Room generates platform constructors.
internal expect object ContextOptimizationDatabaseConstructor : RoomDatabaseConstructor<ContextOptimizationDatabase> {
    override fun initialize(): ContextOptimizationDatabase
}

internal val ContextOptimizationDatabaseSpec =
    DatabaseSpec("context_optimization", ContextOptimizationDatabaseConstructor::initialize)
