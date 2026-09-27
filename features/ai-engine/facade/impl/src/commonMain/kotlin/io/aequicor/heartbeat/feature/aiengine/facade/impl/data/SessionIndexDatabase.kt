package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import androidx.room.ColumnInfo
import androidx.room.ConstructedBy
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.RoomRawQuery
import io.aequicor.heartbeat.core.datastore.DatabaseSpec

/**
 * Profile index of AI-engine sessions. Rows hold the full summary as JSON plus the columns needed for filtering
 * and keyset paging; titles and workspaces are user data and never logged.
 */
@Database(entities = [SessionEntity::class, CoverageEntity::class, IndexMetaEntity::class], version = 1)
@ConstructedBy(SessionIndexDatabaseConstructor::class)
abstract class SessionIndexDatabase : RoomDatabase() {
    /** Session rows. */
    abstract fun sessions(): SessionDao
}

/** Room-generated constructor. */
@Suppress("KotlinNoActualForExpect") // Room generates the actual implementations
expect object SessionIndexDatabaseConstructor : RoomDatabaseConstructor<SessionIndexDatabase> {
    override fun initialize(): SessionIndexDatabase
}

/** Opened through the profile DataStores. */
internal val SessionIndexDatabaseSpec =
    DatabaseSpec("aiengine_sessions", SessionIndexDatabaseConstructor::initialize)

/** One indexed session; [seenGeneration] is null until a discovery reports it. */
@Entity(
    tableName = "sessions",
    indices = [
        Index("engine", "source"),
        Index(value = ["sort_updated", "ref_key"], orders = [Index.Order.DESC, Index.Order.ASC]),
        Index(value = ["sort_created", "ref_key"], orders = [Index.Order.DESC, Index.Order.ASC]),
    ],
)
data class SessionEntity(
    @PrimaryKey @ColumnInfo(name = "ref_key") val refKey: String,
    val engine: String,
    val source: String,
    val title: String?,
    val workspace: String?,
    @ColumnInfo(name = "is_heartbeat") val isHeartbeat: Boolean,
    @ColumnInfo(name = "is_archived") val isArchived: Boolean,
    @ColumnInfo(name = "sort_updated") val sortUpdated: Long,
    @ColumnInfo(name = "sort_created") val sortCreated: Long,
    @ColumnInfo(name = "seen_generation") val seenGeneration: Long?,
    val summary: String,
)

/** Last discovery coverage of one source, as JSON. */
@Entity(tableName = "coverage")
data class CoverageEntity(
    @PrimaryKey val key: String,
    val discovery: String,
)

/** Named counters, e.g. the snapshot revision. */
@Entity(tableName = "meta")
data class IndexMetaEntity(
    @PrimaryKey val key: String,
    val value: Long,
)

/** Access to the index tables. */
@Dao
interface SessionDao {
    /** Filtered keyset page built by the index adapter from whitelisted columns and bound arguments. */
    @RawQuery
    suspend fun page(query: RoomRawQuery): List<SessionEntity>

    /** One row. */
    @Query("SELECT * FROM sessions WHERE ref_key = :refKey")
    suspend fun find(refKey: String): SessionEntity?

    /** Rows by key. */
    @Query("SELECT * FROM sessions WHERE ref_key IN (:refKeys)")
    suspend fun findAll(refKeys: List<String>): List<SessionEntity>

    /** Inserts or replaces rows. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rows: List<SessionEntity>)

    /** Deletes external rows of a source that a discovery generation did not report. */
    @Query(
        "DELETE FROM sessions WHERE engine = :engine AND source = :source AND is_heartbeat = 0 " +
            "AND (seen_generation IS NULL OR seen_generation < :generation)",
    )
    suspend fun deleteMissing(engine: String, source: String, generation: Long): Int

    /** Every coverage row. */
    @Query("SELECT * FROM coverage")
    suspend fun coverage(): List<CoverageEntity>

    /** Inserts or replaces coverage. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCoverage(row: CoverageEntity)

    /** Counter value. */
    @Query("SELECT value FROM meta WHERE key = :key")
    suspend fun counter(key: String): Long?

    /** Sets a counter. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setCounter(row: IndexMetaEntity)
}
