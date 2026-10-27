package io.aequicor.heartbeat.feature.aiengine.facade.impl.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.aiengine.facade.api.AiEngines
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigurationEnabled
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagementEnabled
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineUsageEnabled

/** Registers the facade's toggles: catalog gate, usage telemetry, engine management and configuration. */
@BindingContainer
@ContributesTo(AppScope::class)
public object FacadeToggleBindings {
    /** Shared action policy remains opt-in while its execution adapters are being verified. */
    @Provides
    @IntoSet
    public fun actionSafetyToggle(): FeatureToggle<*> =
        io.aequicor.heartbeat.feature.aiengine.facade.api.ActionSafetyEnabled

    /** Unattended confirmation requests expire; opt-in because it changes the shared security gate. */
    @Provides
    @IntoSet
    public fun unattendedApprovalTimeoutToggle(): FeatureToggle<*> =
        io.aequicor.heartbeat.feature.aiengine.facade.api.UnattendedApprovalTimeoutEnabled

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

    /** Engine configuration through the control panel. */
    @Provides
    @IntoSet
    public fun configurationToggle(): FeatureToggle<*> = EngineConfigurationEnabled
}
