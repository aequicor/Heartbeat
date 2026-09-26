package io.aequicor.heartbeat.core.datastore.impl

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import io.aequicor.heartbeat.core.datastore.RecordRetention
import kotlinx.coroutines.flow.Flow

/**
 * A feature database: one table with retention columns, one without. JVM-only, so it needs no `@ConstructedBy`:
 * the spec creates the generated `TestDatabase_Impl` directly.
 */
@Database(entities = [NoteEntity::class, TagEntity::class], version = 1, exportSchema = false)
abstract class TestDatabase : RoomDatabase() {
    abstract fun notes(): NoteDao
}

@Entity(tableName = "notes", indices = [Index(RecordRetention.EXPIRES_AT_COLUMN)])
data class NoteEntity(
    @PrimaryKey val id: String,
    val text: String,
    @Embedded val retention: RecordRetention,
)

@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey val name: String,
)

@Dao
interface NoteDao {
    @Insert
    suspend fun insert(note: NoteEntity)

    @Insert
    suspend fun insert(tag: TagEntity)

    @Query("SELECT id FROM notes ORDER BY id")
    fun observeIds(): Flow<List<String>>

    @Query("SELECT id FROM notes ORDER BY id")
    suspend fun ids(): List<String>

    @Query("SELECT COUNT(*) FROM tags")
    suspend fun tagCount(): Int
}
