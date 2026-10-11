package io.aequicor.heartbeat.feature.harness.impl.domain.services

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import kotlinx.coroutines.flow.Flow

/**
 * Live host authority for a publisher, or for an owned wake's target. Implementations resolve only durable host
 * routing and current library state, never callback caches or script-supplied metadata.
 */
internal interface HarnessSchedulerAccess {
    /**
     * Current decision immediately, then each change of it. [HarnessAccess.Unknown] while library or project
     * provenance is still being restored after startup: it neither grants nor revokes.
     */
    fun permits(harness: HarnessId, target: HarnessTarget?): Flow<HarnessAccess>

    /** One synchronous view for bulk reads; never suspends, starts the library or performs IO. */
    fun snapshot(): HarnessAccessSnapshot
}

/** One authority decision of [HarnessSchedulerAccess.permits]. */
internal sealed interface HarnessAccess {
    data object Unknown : HarnessAccess
    data object Denied : HarnessAccess
    data class Permitted(val permit: HarnessDeliveryPermit) : HarnessAccess
}

/** Synchronous decision over one captured state: true or false when known, null while provenance is unknown. */
internal fun interface HarnessAccessSnapshot {
    fun allows(harness: HarnessId, target: HarnessTarget?): Boolean?
}

/**
 * Exact authority, rechecked after durable ancestry IO and before Allow is emitted. It stays current across
 * unrelated library or project changes and is revoked only when this decision or the enabled generation changes.
 */
internal data class HarnessDeliveryPermit(val isCurrent: suspend () -> Boolean) {
    override fun toString(): String = "HarnessDeliveryPermit(***)"
}

/** Trusted host target, shared by wake and helper admission; workspace is the native execution checkout. */
internal data class HarnessTarget(val session: SessionRef, val workspace: WorkspaceRef?) {
    override fun toString(): String = "HarnessTarget(***)"
}
