package io.aequicor.heartbeat.feature.togglespanel.impl.domain

import io.aequicor.heartbeat.core.featuretoggles.ToggleState
import io.aequicor.heartbeat.feature.togglespanel.api.ToggleOperation
import kotlinx.coroutines.flow.Flow

/** Domain port for observing and changing local toggle overrides. */
interface TogglesRepository {
    /** Emits current values and propagates read failures. */
    fun observeStates(): Flow<List<ToggleState<*>>>

    /** Persists one mutation; failures propagate without a success acknowledgement. */
    suspend fun apply(operation: ToggleOperation)
}
