package io.aequicor.heartbeat.feature.aiengine.pi.impl.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.aiengine.facade.api.AiEngines
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRegistration
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiAuthOwner
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiDescriptor
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiEnabled
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiEngine

/** Registers the built-in toggle without instantiating a profile or starting Pi. */
@BindingContainer
@ContributesTo(AppScope::class)
public object PiToggleBindings {
    /** Shared catalog gate, enabled with the bundled runtime. */
    @Provides
    @IntoSet
    public fun catalogToggle(): FeatureToggle<*> = AiEngines

    /** Pi is installed and enabled with the desktop application. */
    @Provides
    @IntoSet
    public fun toggle(): FeatureToggle<*> = PiEnabled
}

/** Profile-owned lazy adapter registration; descriptor enumeration performs no IO. */
@BindingContainer
@ContributesTo(ProfileScope::class)
public object PiRegistrationBindings {
    /** Registers one factory per profile. */
    @Provides
    @IntoSet
    public fun registration(engine: Lazy<PiEngine>): EngineRegistration =
        EngineRegistration(PiDescriptor, PiAuthOwner, engine)
}
