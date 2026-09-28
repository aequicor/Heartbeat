package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess

internal class ClaudeFeatures(
    private vararg val features: Pair<EngineFeatureKey<*>, EngineFeature>,
    private val failure: () -> io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure? = { null },
) : EngineFeatures {
    override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> {
        val feature = features.firstOrNull { it.first.id == key.id && it.first.type == key.type }?.second
            ?: return FeatureAccess.Unsupported
        if (!key.type.isInstance(feature)) return FeatureAccess.Unsupported
        failure()?.let { return FeatureAccess.Unavailable(it) }
        // The KClass check above proves that the value implements the exact requested contract.
        @Suppress("UNCHECKED_CAST")
        return FeatureAccess.Available(feature as F)
    }
}
