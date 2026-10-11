package io.aequicor.heartbeat.feature.scheduler.api.spi

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperId

/**
 * Durable capacity already granted to one helper operation. [reservation] identifies the acquisition, not its
 * shared workflow [owner]. A null [helper] proves no prompt was admitted: its id must be durably stored before
 * any prompt, and the previous producer must be fenced. Such a record permits cleanup only, never creation.
 */
public data class HelperCapacityRecoveryRecord(
    val reservation: ActionId,
    val owner: ActionId,
    val parent: SessionRef?,
    val helper: HelperId?,
) {
    override fun toString(): String = "HelperCapacityRecoveryRecord(***)"
}

/**
 * Profile contribution to the shared capacity restore barrier, including while the contributing feature is off.
 * Reads only its confirmed durable journal, without calling helper services, hosts or native runtimes. Failure
 * must propagate; it is never an empty successful snapshot. Return only operations whose capacity grant was
 * durably recorded AFTER acquisition and BEFORE creation, never planned or queued operations. The previous
 * producers must be fenced before this snapshot; they cannot later submit a prompt from a null-helper record.
 * Keep unresolved records until confirmed lease release; no TTL may erase evidence of possible native work.
 */
public fun interface HelperCapacityRecoverySource {
    /** All unresolved grants with unique reservations; any non-null helper identities must also be unique. */
    public suspend fun reservations(): List<HelperCapacityRecoveryRecord>
}
