package io.aequicor.heartbeat.feature.scheduler.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindings
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderUsageCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionCatalog

/** Unsupported and temporarily blocked capabilities are domain failures, never silent fallbacks. */
internal fun <F : EngineFeature> FeatureAccess<F>.orThrow(): F = when (this) {
    is FeatureAccess.Available -> feature
    is FeatureAccess.Unavailable -> throw EngineException(reason)
    FeatureAccess.Unsupported -> throw EngineException(EngineFailure.Engine(EngineFailureReason.UnsupportedCapability))
}

/**
 * Default of the optional `EngineFacade` parameter until an application bundle installs an engine runtime. Metro
 * treats `EngineFacade?` as a separate key, so a nullable parameter would never receive the real binding.
 */
internal object MissingEngineFacade : EngineFacade {
    override val engines: EngineCatalog get() = unavailable()
    override val bindings: EngineBindings get() = unavailable()
    override val providerUsage: ProviderUsageCatalog get() = unavailable()
    override val models: ModelCatalog get() = unavailable()
    override val sessions: SessionCatalog get() = unavailable()

    private fun unavailable(): Nothing = throw EngineException(EngineFailure.Engine(EngineFailureReason.Unavailable))
}
