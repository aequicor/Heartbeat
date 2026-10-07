package io.aequicor.heartbeat.feature.harness.impl.domain

import io.aequicor.heartbeat.feature.harness.api.HarnessActivationRequest
import io.aequicor.heartbeat.feature.harness.api.HarnessEffect

/**
 * Profile runtime boundary. Availability describes executable code, independently of library storage.
 * Implementations retain generation fences before awaiting IO and never publish an older activation afterward.
 * Cleanup returns true only once owned registrations, calls and drivers are quiescent; false requires retry.
 * A future runtime must replace the unavailable binding before any script or workflow can execute.
 */
internal interface HarnessRuntimeControl {
    val isAvailable: Boolean

    /** Atomically publishes this exact generation, or leaves the previous instance intact on false. */
    suspend fun activate(request: HarnessActivationRequest): Boolean

    /** Applies item and harness generation fences, preserving the effect's stopping versus pausing semantics. */
    suspend fun deactivate(effect: HarnessEffect.Deactivate): Boolean

    /** Revokes this exact deletion attempt; obsolete effects must stop without touching storage or feedback. */
    suspend fun remove(effect: HarnessEffect.Remove): HarnessRemovalResult
}

/** Ready proves cleanup only for the exact still-pending receipt; Retry retains the same generation fence. */
internal enum class HarnessRemovalResult { Ready, Retry, Obsolete }
