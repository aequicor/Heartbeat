package io.aequicor.heartbeat.feature.aiengine.kimi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess

internal class KimiFeatures(private vararg val entries: Pair<EngineFeatureKey<*>, EngineFeature>) : EngineFeatures {
    override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> {
        val feature = entries.firstOrNull { it.first.id == key.id && it.first.type == key.type }?.second
            ?: return FeatureAccess.Unsupported
        if (!key.type.isInstance(feature)) return FeatureAccess.Unsupported
        @Suppress("UNCHECKED_CAST")
        return FeatureAccess.Available(feature as F)
    }
}
