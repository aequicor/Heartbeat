package io.aequicor.heartbeat.feature.harness.impl.domain

import io.aequicor.heartbeat.feature.harness.api.Harness
import io.aequicor.heartbeat.feature.harness.api.HarnessActivationRequest
import io.aequicor.heartbeat.feature.harness.api.HarnessEffect
import io.aequicor.heartbeat.feature.harness.api.HarnessValidationCode

/**
 * Profile runtime boundary. Availability describes executable code, independently of library storage.
 * Implementations retain generation fences before awaiting IO and never publish an older activation afterward.
 * Cleanup returns true only once owned registrations, calls and drivers are quiescent; false requires retry.
 * A future runtime must replace the unavailable binding before any script or workflow can execute.
 */
internal interface HarnessRuntimeControl {
    val isAvailable: Boolean

    /** Atomically publishes this exact generation; a failed candidate leaves the previous instance intact. */
    suspend fun activate(request: HarnessActivationRequest): HarnessActivationResult

    /** Applies item and harness generation fences, preserving the effect's stopping versus pausing semantics. */
    suspend fun deactivate(effect: HarnessEffect.Deactivate): Boolean

    /** Revokes this exact deletion attempt; obsolete effects must stop without touching storage or feedback. */
    suspend fun remove(effect: HarnessEffect.Remove): HarnessRemovalResult

    /**
     * Completes cleanup of a deletion committed before its code drained, behind the fence captured by [remove]:
     * true once code, helpers and workflows are quiescent and the removed items' caches are gone; false to retry.
     */
    suspend fun cleanupRemoved(effect: HarnessEffect.Remove): Boolean

    /** Sweeps cached code of items absent from the freshly loaded committed [library]; never throws. */
    suspend fun retainCached(library: List<Harness>)
}

/** Ready proves cleanup only for the exact still-pending receipt; Retry retains the same generation fence. */
internal enum class HarnessRemovalResult { Ready, Retry, Obsolete }

/** A result belongs to its exact activation request; only safe host validation codes cross this boundary. */
internal sealed interface HarnessActivationResult {
    data object Active : HarnessActivationResult
    data class Failed(val reason: HarnessValidationCode? = null) : HarnessActivationResult
}
