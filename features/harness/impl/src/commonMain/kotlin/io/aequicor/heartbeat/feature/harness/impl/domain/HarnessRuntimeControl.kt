package io.aequicor.heartbeat.feature.harness.impl.domain

import io.aequicor.heartbeat.feature.harness.api.Harness
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

    /** Revokes this immutable harness identity and confirms its entire owned-work removal barrier. */
    suspend fun remove(harness: Harness): Boolean
}
