package io.aequicor.heartbeat.feature.attachments.impl.data

import androidx.room.ConstructedBy
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import io.aequicor.heartbeat.core.datastore.DatabaseSpec
import kotlinx.coroutines.flow.Flow

/** Permanent attachment metadata; profile wiping owns deletion of both rows and files. */
@Entity(tableName = "attachments", indices = [Index(value = ["deduplicationKey"], unique = true)])
internal data class AttachmentEntity(
    @PrimaryKey val id: String,
    val name: String,
    val mediaType: String,
    val sizeBytes: Long,
    val deduplicationKey: String? = null,
)

@Dao
internal interface AttachmentDao {
    @Query("SELECT * FROM attachments WHERE id = :id")
    suspend fun get(id: String): AttachmentEntity?

    @Query("SELECT * FROM attachments WHERE id IN (:ids)")
    fun observe(ids: List<String>): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE deduplicationKey = :key")
    suspend fun byDeduplicationKey(key: String): AttachmentEntity?

    @Insert
    suspend fun insert(rows: List<AttachmentEntity>)
}

@Database(entities = [AttachmentEntity::class], version = 1)
@ConstructedBy(AttachmentDatabaseConstructor::class)
internal abstract class AttachmentDatabase : RoomDatabase() {
    abstract fun attachments(): AttachmentDao
}

@Suppress("KotlinNoActualForExpect") // Room generates platform constructors.
internal expect object AttachmentDatabaseConstructor : RoomDatabaseConstructor<AttachmentDatabase> {
    override fun initialize(): AttachmentDatabase
}

internal val AttachmentDatabaseSpec = DatabaseSpec("attachments", AttachmentDatabaseConstructor::initialize)
