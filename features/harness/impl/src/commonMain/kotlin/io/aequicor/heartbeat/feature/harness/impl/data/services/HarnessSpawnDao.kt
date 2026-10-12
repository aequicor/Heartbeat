package io.aequicor.heartbeat.feature.harness.impl.data.services

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

/** Unresolved cleanup proof has no retention deadline; only confirmed cleanup may delete it. */
@Entity(tableName = "spawn_journal")
internal data class HarnessSpawnEntity(
    @PrimaryKey val reservation: String,
    val identity: String,
    val helper: String?,
) {
    override fun toString(): String = "HarnessSpawnEntity(***)"
}

@Dao
internal abstract class HarnessSpawnDao {
    @Query("SELECT * FROM spawn_journal ORDER BY reservation")
    abstract suspend fun pending(): List<HarnessSpawnEntity>

    @Query("SELECT * FROM spawn_journal WHERE reservation = :reservation")
    protected abstract suspend fun lookup(reservation: String): HarnessSpawnEntity?

    @Insert
    protected abstract suspend fun insert(record: HarnessSpawnEntity)

    @Update
    protected abstract suspend fun update(record: HarnessSpawnEntity)

    @Query("DELETE FROM spawn_journal WHERE reservation = :reservation")
    protected abstract suspend fun delete(reservation: String)

    /** The initial identity is immutable, while null replay never erases a committed helper. */
    @Transaction
    open suspend fun recordGranted(record: HarnessSpawnEntity) {
        require(record.helper == null) { "Initial spawn grant cannot include a helper" }
        val incoming = HarnessSpawnCodec.decode(record)
        val current = lookup(record.reservation)
        if (current == null) {
            insert(record)
        } else {
            check(HarnessSpawnCodec.decode(current).copy(helper = null) == incoming) { "Spawn identity conflict" }
        }
    }

    /** An identity can name exactly one helper, including concurrent writers and lost acknowledgements. */
    @Transaction
    open suspend fun bindHelper(reservation: String, helper: String): HarnessSpawnEntity {
        val current = checkNotNull(lookup(reservation)) { "Spawn grant is missing" }
        HarnessSpawnCodec.decode(current)
        check(current.helper == null || current.helper == helper) { "Spawn helper conflict" }
        val bound = current.copy(helper = helper)
        if (bound != current) update(bound)
        return bound
    }

    /** Deletion is idempotent but never accepts another acquisition's immutable ownership proof. */
    @Transaction
    open suspend fun settle(record: HarnessSpawnEntity) {
        val expected = HarnessSpawnCodec.decode(record).copy(helper = null)
        val current = lookup(record.reservation) ?: return
        check(HarnessSpawnCodec.decode(current).copy(helper = null) == expected) { "Spawn identity conflict" }
        delete(record.reservation)
    }
}
