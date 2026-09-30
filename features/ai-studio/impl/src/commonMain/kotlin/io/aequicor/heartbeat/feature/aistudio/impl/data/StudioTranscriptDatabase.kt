package io.aequicor.heartbeat.feature.aistudio.impl.data

import androidx.room.ColumnInfo
import androidx.room.ConstructedBy
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.Transaction
import io.aequicor.heartbeat.core.datastore.DatabaseSpec
import kotlinx.coroutines.flow.Flow

/**
 * Profile database of studio transcripts: one row per native history item, so a streamed revision rewrites the
 * items it changed instead of every conversation of the profile. Titles and item content are user data and are
 * never logged.
 */
@Database(entities = [StudioTranscriptEntity::class], version = 1)
@ConstructedBy(StudioTranscriptDatabaseConstructor::class)
abstract class StudioTranscriptDatabase : RoomDatabase() {
    /** Transcript rows. */
    abstract fun transcripts(): StudioTranscriptDao
}

/** Room-generated constructor. */
@Suppress("KotlinNoActualForExpect") // Room generates the actual implementations
expect object StudioTranscriptDatabaseConstructor : RoomDatabaseConstructor<StudioTranscriptDatabase> {
    override fun initialize(): StudioTranscriptDatabase
}

/** Opened through the profile DataStores. */
internal val StudioTranscriptDatabaseSpec =
    DatabaseSpec("ai_studio_transcripts", StudioTranscriptDatabaseConstructor::initialize)

/**
 * One stored item of one conversation. [ordinal] is the display order, which is not the native item position: a
 * new native generation restarts positions from zero, so earlier items keep their place in front of it.
 * [revision] is the native revision the payload was written from; it only increases, so a row whose revision and
 * ordinal did not change holds the item a write received.
 */
@Entity(
    tableName = "transcript",
    primaryKeys = ["chat_id", "item_id"],
    indices = [Index("chat_id", "ordinal")],
)
data class StudioTranscriptEntity(
    @ColumnInfo(name = "chat_id") val chatId: String,
    @ColumnInfo(name = "item_id") val itemId: String,
    val ordinal: Int,
    val revision: Long,
    val payload: String,
)

/** Order and revision of a stored item, without its payload: what a write compares against. */
data class StudioTranscriptPosition(
    @ColumnInfo(name = "item_id") val itemId: String,
    val ordinal: Int,
    val revision: Long,
)

/** Access to the transcript table. */
@Dao
interface StudioTranscriptDao {
    /** Items of one conversation in display order, and their changes. */
    @Query("SELECT * FROM transcript WHERE chat_id = :chatId ORDER BY ordinal ASC")
    fun observe(chatId: String): Flow<List<StudioTranscriptEntity>>

    /** Items of one conversation in display order. */
    @Query("SELECT * FROM transcript WHERE chat_id = :chatId ORDER BY ordinal ASC")
    suspend fun items(chatId: String): List<StudioTranscriptEntity>

    /** Order and revision of the items of one conversation. */
    @Query("SELECT item_id, ordinal, revision FROM transcript WHERE chat_id = :chatId ORDER BY ordinal ASC")
    suspend fun positions(chatId: String): List<StudioTranscriptPosition>

    /** Inserts or replaces rows. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rows: List<StudioTranscriptEntity>)

    /** Deletes the named items of one conversation. */
    @Query("DELETE FROM transcript WHERE chat_id = :chatId AND item_id IN (:itemIds)")
    suspend fun delete(chatId: String, itemIds: List<String>)

    /** Stores the changed rows and drops the removed items of one conversation as one change. */
    @Transaction
    suspend fun apply(rows: List<StudioTranscriptEntity>, chatId: String, removed: List<String>) {
        if (rows.isNotEmpty()) upsert(rows)
        if (removed.isNotEmpty()) delete(chatId, removed)
    }
}
