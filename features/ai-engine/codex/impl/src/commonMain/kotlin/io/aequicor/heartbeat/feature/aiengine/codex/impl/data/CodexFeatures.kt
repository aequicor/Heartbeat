package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess

internal class CodexFeatures(
    private vararg val entries: EngineFeature,
    private val blocked: () -> EngineFailure? = { null },
) : EngineFeatures {
    override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> {
        val failure = blocked()
        if (failure != null) return FeatureAccess.Unavailable(failure)
        val entry = entries.firstOrNull { key.type.isInstance(it) } ?: return FeatureAccess.Unsupported
        // The runtime type check above proves this cast, including keys with a colliding textual id.
        @Suppress("UNCHECKED_CAST")
        return FeatureAccess.Available(entry as F)
    }
}
