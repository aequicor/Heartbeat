package io.aequicor.heartbeat.feature.harness.impl.data.services

import androidx.room.ConstructedBy
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.Transaction
import androidx.room.Upsert
import io.aequicor.heartbeat.core.datastore.DatabaseSpec

/**
 * Permanent profile metadata, removed only by profile wipe. No retention columns: a late persisted event may
 * still refer to a finished request. Contains exact identity and restrictions, never source, text or payload.
 */
@Entity(tableName = "request_ancestry", primaryKeys = ["sessionKey", "requestId"])
internal data class HarnessAncestryRecord(val sessionKey: String, val requestId: String, val origin: String) {
    override fun toString(): String = "HarnessAncestryRecord(***)"
}

@Dao
internal abstract class HarnessAncestryDao {
    @Query("SELECT * FROM request_ancestry WHERE sessionKey = :session AND requestId = :request")
    abstract suspend fun lookup(session: String, request: String): HarnessAncestryRecord?

    @Upsert
    protected abstract suspend fun save(record: HarnessAncestryRecord)

    /** Read, validate, strengthen and persist in one writer transaction, including concurrent callers. */
    @Transaction
    open suspend fun restrict(record: HarnessAncestryRecord) {
        val incoming = HarnessAncestryCodec.decode(record.origin)
        val existing = lookup(record.sessionKey, record.requestId)
        val merged = existing?.let { HarnessAncestryCodec.decode(it.origin).merge(incoming) } ?: incoming
        save(record.copy(origin = HarnessAncestryCodec.encode(merged)))
    }
}

@Database(entities = [HarnessAncestryRecord::class], version = 1)
@ConstructedBy(HarnessAncestryDatabaseConstructor::class)
internal abstract class HarnessAncestryDatabase : RoomDatabase() {
    abstract fun ancestry(): HarnessAncestryDao
}

@Suppress("KotlinNoActualForExpect") // Room generates platform constructors.
internal expect object HarnessAncestryDatabaseConstructor : RoomDatabaseConstructor<HarnessAncestryDatabase> {
    override fun initialize(): HarnessAncestryDatabase
}

internal val HarnessAncestryDatabaseSpec = DatabaseSpec(
    "harness_ancestry",
    HarnessAncestryDatabaseConstructor::initialize,
)
