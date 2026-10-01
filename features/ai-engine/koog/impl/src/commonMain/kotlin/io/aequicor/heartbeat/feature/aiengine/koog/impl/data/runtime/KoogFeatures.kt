package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import io.aequicor.heartbeat.feature.aiengine.facade.api.AcceptsImages
import io.aequicor.heartbeat.feature.aiengine.facade.api.AcceptsResources
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess

internal class KoogFeatures(private vararg val entries: Pair<EngineFeatureKey<*>, EngineFeature>) : EngineFeatures {
    override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> {
        val entry = entries.firstOrNull { it.first.id == key.id && it.first.type == key.type }
            ?: return FeatureAccess.Unsupported
        val feature = entry.second
        if (feature.hasNoSupportedInput()) return FeatureAccess.Unsupported
        // Both the stable id and runtime KClass were checked; SDK types never cross this boundary.
        @Suppress("UNCHECKED_CAST")
        return FeatureAccess.Available(feature as F)
    }
}

private fun EngineFeature.hasNoSupportedInput(): Boolean = when (this) {
    is AcceptsImages -> mediaTypes.isEmpty()
    is AcceptsResources -> mediaTypes.isEmpty()
    else -> false
}
