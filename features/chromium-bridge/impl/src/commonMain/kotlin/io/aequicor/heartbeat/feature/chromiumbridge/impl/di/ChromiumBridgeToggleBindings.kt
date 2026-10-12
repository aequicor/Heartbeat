package io.aequicor.heartbeat.feature.chromiumbridge.impl.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBridgeAgentControl
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBridgeEnabled

/** Registers the disabled-by-default Chromium bridge switches. */
@ContributesTo(AppScope::class)
@BindingContainer
public object ChromiumBridgeToggleBindings {
    /** Exact FeatureToggle wildcard joins the application registry. */
    @Provides
    @IntoSet
    public fun enabled(): FeatureToggle<*> = ChromiumBridgeEnabled

    /** Exact FeatureToggle wildcard joins the application registry. */
    @Provides
    @IntoSet
    public fun agentControl(): FeatureToggle<*> = ChromiumBridgeAgentControl
}
