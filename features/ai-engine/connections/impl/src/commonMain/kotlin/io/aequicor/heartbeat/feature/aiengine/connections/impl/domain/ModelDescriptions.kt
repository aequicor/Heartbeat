package io.aequicor.heartbeat.feature.aiengine.connections.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import kotlinx.coroutines.flow.Flow

/** A model described for adaptive subagents; one description serves every connection of the engine. */
data class DescribedModel(val engine: EngineId, val model: ModelId)

/**
 * Which models have a description in the adaptive subagents library. Null while that feature is off: the space
 * then offers no way to the descriptions.
 */
fun interface ModelDescriptions {
    /** Current descriptions, then every change. */
    fun observe(): Flow<Set<DescribedModel>?>
}
