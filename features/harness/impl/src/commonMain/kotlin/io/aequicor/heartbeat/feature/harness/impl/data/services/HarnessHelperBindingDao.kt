package io.aequicor.heartbeat.feature.harness.impl.data.services

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import io.aequicor.heartbeat.core.datastore.RecordRetention

/**
 * Ownership proof with a sliding retention renewed by every owned helper admission; contains no source, prompt,
 * payload or human-readable title.
 */
@Entity(tableName = "helper_bindings", indices = [Index(RecordRetention.EXPIRES_AT_COLUMN)])
internal data class HarnessHelperBindingRecord(
    @PrimaryKey val helper: String,
    val owner: String,
    val harness: String,
    val attachRequest: String,
    val session: String?,
    @Embedded val retention: RecordRetention,
) {
    /** Ownership identity; retention columns never participate in conflict checks. */
    fun identity(): List<String> = listOf(helper, owner, harness, attachRequest)

    override fun toString(): String = "HarnessHelperBindingRecord(***)"
}

@Dao
internal abstract class HarnessHelperBindingDao {
    @Query("SELECT * FROM helper_bindings WHERE helper = :helper")
    abstract suspend fun lookup(helper: String): HarnessHelperBindingRecord?

    @Upsert
    protected abstract suspend fun save(record: HarnessHelperBindingRecord)

    /** Compare and insert atomically; replay of the initial null session cannot erase a bound reference. */
    @Transaction
    open suspend fun bind(record: HarnessHelperBindingRecord) {
        val current = lookup(record.helper)
        if (current == null) {
            save(record)
        } else {
            check(current.identity() == record.identity()) { "Helper ownership conflict" }
            check(record.session == null || record.session == current.session) { "Helper session conflict" }
        }
    }

    /**
     * The owner must match before binding or returning the already committed exact session. Every call renews
     * [retention], so a helper that still receives prompts never loses its proof.
     */
    @Transaction
    open suspend fun bindSession(
        helper: String,
        owner: String,
        session: String,
        retention: RecordRetention,
    ): HarnessHelperBindingRecord {
        val current = checkNotNull(lookup(helper)) { "Helper ownership is missing" }
        check(current.owner == owner) { "Helper owner mismatch" }
        check(current.session == null || current.session == session) { "Helper session conflict" }
        val bound = current.copy(session = session, retention = retention)
        save(bound)
        return bound
    }
}
