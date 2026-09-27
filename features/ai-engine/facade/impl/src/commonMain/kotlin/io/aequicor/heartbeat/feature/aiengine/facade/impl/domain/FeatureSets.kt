package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import kotlin.reflect.safeCast

/** No optional operations. */
object NoEngineFeatures : EngineFeatures {
    override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> = FeatureAccess.Unsupported
}

/** Every operation is blocked by [reason], e.g. a disabled engine or a closed handle. */
class BlockedEngineFeatures(private val reason: EngineFailure) : EngineFeatures {
    override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> =
        FeatureAccess.Unavailable(reason)
}

/**
 * Capability table resolved without IO. An entry is returned only when it implements the key's contract, so a
 * matching id alone never leads to an unchecked cast; ids absent from the table fall back to [fallback].
 */
class FeatureTable(
    private val entries: Map<EngineFeatureId, () -> FeatureAccess<EngineFeature>>,
    private val fallback: EngineFeatures = NoEngineFeatures,
) : EngineFeatures {
    override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> {
        val entry = entries[key.id] ?: return fallback.resolve(key)
        return when (val access = entry()) {
            is FeatureAccess.Available -> key.type.safeCast(access.feature)
                ?.let { FeatureAccess.Available(it) }
                ?: FeatureAccess.Unsupported

            is FeatureAccess.Unavailable -> access

            FeatureAccess.Unsupported -> FeatureAccess.Unsupported
        }
    }
}

/** Table entry for a feature that is always available. */
fun available(feature: EngineFeature): () -> FeatureAccess<EngineFeature> = { FeatureAccess.Available(feature) }
