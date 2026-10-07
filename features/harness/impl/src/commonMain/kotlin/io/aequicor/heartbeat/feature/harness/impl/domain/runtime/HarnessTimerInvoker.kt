package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.feature.harness.api.HarnessActivationRequest

/** Host-only execution port. Returning from invoke means the actual callback has stopped, even after timeout. */
internal interface HarnessTimerInvoker {
    /** Waits for this exact instance in the runtime's committed publication snapshot. */
    suspend fun awaitPublication(target: HarnessTimerTarget): Boolean

    /** Rechecks the exact instance, accounts failures and enforces the shared event budget; false ends the timer. */
    suspend fun invoke(target: HarnessTimerTarget, callback: HarnessCallback<suspend () -> Unit>): Boolean
}

internal data class HarnessTimerTarget(val request: HarnessActivationRequest, val access: HarnessInstanceAccess) {
    override fun toString(): String = "HarnessTimerTarget(***)"
}
