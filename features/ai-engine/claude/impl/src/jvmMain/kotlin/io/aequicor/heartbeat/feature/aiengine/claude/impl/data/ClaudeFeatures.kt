package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AcceptsImages
import io.aequicor.heartbeat.feature.aiengine.facade.api.AcceptsResources
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess

internal class ClaudeFeatures(
    private vararg val features: Pair<EngineFeatureKey<*>, EngineFeature>,
    private val failure: () -> io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure? = { null },
) : EngineFeatures {
    override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> {
        val feature = features.firstOrNull {
            it.first.id == key.id && it.first.type == key.type &&
                key.type.isInstance(it.second) && !it.second.hasNoSupportedInput()
        }?.second
            ?: return FeatureAccess.Unsupported
        failure()?.let { return FeatureAccess.Unavailable(it) }
        // The KClass check above proves that the value implements the exact requested contract.
        @Suppress("UNCHECKED_CAST")
        return FeatureAccess.Available(feature as F)
    }
}

private fun EngineFeature.hasNoSupportedInput(): Boolean = when (this) {
    is AcceptsImages -> mediaTypes.isEmpty()
    is AcceptsResources -> mediaTypes.isEmpty()
    else -> false
}
