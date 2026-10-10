package io.aequicor.heartbeat.feature.harness.impl.domain.services

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperId

/** Exact script instance responsible for an acquisition; generations are interpreted within the owning profile. */
internal data class HarnessSpawnOwner(val harness: HarnessId, val item: ItemId, val generation: Long) {
    init {
        require(generation >= 0) { "Invalid spawn generation" }
    }
    override fun toString(): String = "HarnessSpawnOwner(***)"
}

/**
 * Durable proof that capacity was granted. A null helper proves that no prompt was allowed to start. This record
 * contains no executable code or prompt and can only be recovered for cleanup; script callbacks are never replayed.
 * [action] is unique to this spawn, while [reservation] is the original shared-capacity acquisition identity.
 */
internal data class HarnessSpawnRecord(
    val reservation: ActionId,
    val action: ActionId,
    val owner: HarnessSpawnOwner,
    val parent: SessionRef?,
    val request: RequestId,
    val attachRequest: RequestId,
    val origin: HarnessCallOrigin,
    val helper: HelperId? = null,
) {
    init {
        require(request.value.isNotBlank() && attachRequest.value.isNotBlank()) { "Missing spawn request identity" }
        require(!origin.isHookRestricted) { "Hooks cannot spawn helpers" }
    }
    override fun toString(): String = "HarnessSpawnRecord(***)"
}

/**
 * Profile-owned write-ahead cleanup journal. The producer writes [recordGranted] AFTER acquiring capacity and
 * BEFORE creating a helper, then commits [bindHelper] BEFORE any prompt. A failed/uncertain write forbids further
 * creation or prompt; the live producer retains the lease until release is confirmed. Recovery reads only after
 * old producers are fenced, including when the harness toggle is off. Cleanup joins every outstanding producer
 * journal write before deletion, so an ambiguous grant write cannot resurrect a settled row. Fresh reservation
 * and action identities are never reused. Pending records have no TTL.
 */
internal interface HarnessSpawnJournal {
    /** Idempotent exact identity write; replay cannot erase a previously bound helper. */
    suspend fun recordGranted(record: HarnessSpawnRecord)

    /** Atomically binds the sole helper for this acquisition; conflicting identities fail closed. */
    suspend fun bindHelper(reservation: ActionId, helper: HelperId): HarnessSpawnRecord

    /** Strict complete snapshot; any malformed record fails the entire initial capacity restore. */
    suspend fun pending(): List<HarnessSpawnRecord>

    /** Called only after the producer is fenced and the exact lease has confirmed native/hosted cleanup. */
    suspend fun settle(record: HarnessSpawnRecord)
}
