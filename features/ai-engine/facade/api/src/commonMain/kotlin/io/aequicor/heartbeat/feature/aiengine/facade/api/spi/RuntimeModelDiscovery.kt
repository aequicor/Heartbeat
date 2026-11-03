package io.aequicor.heartbeat.feature.aiengine.facade.api.spi

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelInfo

/** Model discovery performed by the already admitted runtime, not by a newly opened unverified process. */
public interface RuntimeModelDiscovery : EngineFeature {
    /**
     * Returns models for the exact binding associated with this runtime's admitted source. Any native session
     * created for discovery must be cleaned up deterministically, including on cancellation.
     */
    public suspend fun discover(binding: EngineBindingId): List<ModelInfo>

    /** Typed runtime feature. */
    public companion object : EngineFeatureKey<RuntimeModelDiscovery>(
        EngineFeatureId("engine.runtime_models"),
        RuntimeModelDiscovery::class,
    )
}
