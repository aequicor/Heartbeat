package io.aequicor.heartbeat.feature.harness.impl.data.services

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/** Permanent ownership proof; contains no source, prompt, payload or human-readable title. */
@Entity(tableName = "helper_bindings")
internal data class HarnessHelperBindingRecord(
    @PrimaryKey val helper: String,
    val owner: String,
    val harness: String,
    val attachRequest: String,
    val session: String?,
) {
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
            check(current.copy(session = null) == record.copy(session = null)) { "Helper ownership conflict" }
            check(record.session == null || record.session == current.session) { "Helper session conflict" }
        }
    }

    /** The owner must match before binding or returning the already committed exact session. */
    @Transaction
    open suspend fun bindSession(helper: String, owner: String, session: String): HarnessHelperBindingRecord {
        val current = checkNotNull(lookup(helper)) { "Helper ownership is missing" }
        check(current.owner == owner) { "Helper owner mismatch" }
        check(current.session == null || current.session == session) { "Helper session conflict" }
        val bound = current.copy(session = session)
        if (bound != current) save(bound)
        return bound
    }
}
