package io.aequicor.heartbeat.feature.harness.impl.domain.services

import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.scheduler.api.WakeRequest
import kotlinx.coroutines.flow.Flow

/**
 * Live host authority for a publisher, or for an owned wake's target. Null revokes admission. Implementations
 * resolve only durable host routing and current library state, never callback caches or script-supplied metadata.
 */
internal fun interface HarnessSchedulerAccess {
    fun permits(harness: HarnessId, target: WakeRequest?): Flow<HarnessDeliveryPermit?>
}

/** Exact authority snapshot, rechecked after durable ancestry IO and before Allow is emitted. */
internal data class HarnessDeliveryPermit(val isCurrent: suspend () -> Boolean) {
    override fun toString(): String = "HarnessDeliveryPermit(***)"
}
