package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess

internal class KoogFeatures(private vararg val entries: Pair<EngineFeatureKey<*>, EngineFeature>) : EngineFeatures {
    override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> {
        val entry = entries.firstOrNull { it.first.id == key.id && it.first.type == key.type }
            ?: return FeatureAccess.Unsupported
        // Both the stable id and runtime KClass were checked; SDK types never cross this boundary.
        @Suppress("UNCHECKED_CAST")
        return FeatureAccess.Available(entry.second as F)
    }
}
