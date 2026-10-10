package io.aequicor.heartbeat.feature.harness.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.api.HarnessName
import kotlinx.serialization.Serializable

/** Minimal accepted context identity, retaining the immutable slug for a later disabled notice. */
@Serializable
internal data class HarnessDeliveryMarker(val harness: HarnessId, val name: HarnessName, val revision: Long) {
    init {
        require(revision >= 0)
    }

    override fun toString(): String = "HarnessDeliveryMarker(***)"
}

/** A generation fences Accepted feedback after compaction or deletion; source and rendered text are never stored. */
internal data class HarnessDeliverySnapshot(
    val generation: String,
    val activeSetSha: String?,
    val markers: List<HarnessDeliveryMarker>,
    val pendingDisabled: Set<HarnessName>,
    val isDeliveryPending: Boolean = false,
) {
    override fun toString(): String = "HarnessDeliverySnapshot(***)"
}

/**
 * Bounded profile delivery receipts for frozen engine instructions. At most 256 sessions survive for 30 days,
 * with at most [HarnessLimits.ACTIVE_PER_SESSION] delivered markers plus pending disabled names per session.
 * Eviction means content can be delivered again, never that a turn was accepted. All calls are main-safe.
 */
internal interface HarnessDeliveryStorage {
    /** Creates a fresh durable generation when absent; callers capture it before composing the prompt. */
    suspend fun snapshot(session: SessionRef): HarnessDeliverySnapshot

    /**
     * Records possible delivery (not acceptance), retained across lost feedback and profile restarts.
     * Invalidates the previously accepted hash before a different block may reach native context. Returns a fresh
     * durable generation only if the composing snapshot is still current; null requests recomposition. Names
     * survive uncertain submissions. Cancellation or failure must prevent the caller from returning its block.
     */
    suspend fun begin(session: SessionRef, expectedGeneration: String): HarnessDeliverySnapshot?

    /**
     * Called only after native Accepted. Installs the hash and marker set if the captured generation still matches
     * and every pending disabled name was included in that prompt. Success rotates the generation, so duplicate
     * or late feedback has no effect. False requires a fresh snapshot; it does not undo native acceptance.
     */
    suspend fun accepted(
        session: SessionRef,
        expectedGeneration: String,
        activeSetSha: String,
        markers: List<HarnessDeliveryMarker>,
        coveredDisabled: Set<HarnessName>,
    ): Boolean

    /**
     * Invalidates hash/generation while retaining names and pending state. Unconfirmed native acceptance sets
     * [isDeliveryPending] to true, creating a receipt if eviction removed it; ordinary compaction only retains it.
     */
    suspend fun reset(session: SessionRef, isDeliveryPending: Boolean = false)

    /** Invalidates affected hashes/generations and moves the delivered immutable name into pending disabled. */
    suspend fun removeHarness(harness: HarnessId)
}
