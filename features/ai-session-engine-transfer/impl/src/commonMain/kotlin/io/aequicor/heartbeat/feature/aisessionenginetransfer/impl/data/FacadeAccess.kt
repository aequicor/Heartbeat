package io.aequicor.heartbeat.feature.aisessionenginetransfer.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess

/** Unsupported and temporarily blocked capabilities are domain failures, never silent fallbacks. */
internal fun <F : EngineFeature> FeatureAccess<F>.orThrow(): F = when (this) {
    is FeatureAccess.Available -> feature
    is FeatureAccess.Unavailable -> throw EngineException(reason)
    FeatureAccess.Unsupported -> throw EngineException(EngineFailure.Engine(EngineFailureReason.UnsupportedCapability))
}
