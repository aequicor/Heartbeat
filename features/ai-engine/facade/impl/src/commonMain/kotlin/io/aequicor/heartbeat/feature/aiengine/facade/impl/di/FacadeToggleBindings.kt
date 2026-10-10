package io.aequicor.heartbeat.feature.aiengine.facade.impl.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.aiengine.facade.api.AiEngines
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagementEnabled
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineUsageEnabled

/** Registers the toggles owned by the facade: the catalog gate, usage telemetry and engine management. */
@BindingContainer
@ContributesTo(AppScope::class)
public object FacadeToggleBindings {
    /** Additional gated native tools are an explicit opt-in. */
    @Provides
    @IntoSet
    public fun nativeToolsToggle(): FeatureToggle<*> =
        io.aequicor.heartbeat.feature.aiengine.facade.api.HarnessNativeTools

    /** Native delegation is an explicit opt-in; native coding tools remain isolated. */
    @Provides
    @IntoSet
    public fun subagentsToggle(): FeatureToggle<*> =
        io.aequicor.heartbeat.feature.aiengine.facade.api.EngineSubagentsEnabled

    /** Catalog gate shared by all engines. */
    @Provides
    @IntoSet
    public fun catalogToggle(): FeatureToggle<*> = AiEngines

    /** Shared native usage telemetry and Studio presentation gate. */
    @Provides
    @IntoSet
    public fun usageToggle(): FeatureToggle<*> = EngineUsageEnabled

    /** Engine management in the engine settings. */
    @Provides
    @IntoSet
    public fun managementToggle(): FeatureToggle<*> = EngineManagementEnabled
}
