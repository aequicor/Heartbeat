package io.aequicor.heartbeat.feature.aiengine.facade.impl.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.aiengine.facade.api.AiEngines
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineUsageEnabled

/** Registers the engine catalog gate owned by the facade. */
@BindingContainer
@ContributesTo(AppScope::class)
public object FacadeToggleBindings {
    /** Catalog gate shared by all engines. */
    @Provides
    @IntoSet
    public fun catalogToggle(): FeatureToggle<*> = AiEngines

    /** Shared native usage telemetry and Studio presentation gate. */
    @Provides
    @IntoSet
    public fun usageToggle(): FeatureToggle<*> = EngineUsageEnabled
}
