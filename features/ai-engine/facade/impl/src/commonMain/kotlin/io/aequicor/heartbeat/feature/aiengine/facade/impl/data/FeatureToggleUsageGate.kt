package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineUsageEnabled
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.EngineUsageGate
import kotlinx.coroutines.flow.Flow

/** Shared telemetry gate backed by the registered application toggle. */
class FeatureToggleUsageGate(private val toggles: FeatureToggles) : EngineUsageGate {
    override fun observe(): Flow<Boolean> = toggles.observe(EngineUsageEnabled)

    override suspend fun isEnabled(): Boolean = toggles.get(EngineUsageEnabled)
}
