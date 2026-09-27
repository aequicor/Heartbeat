package io.aequicor.heartbeat.feature.aiengine.pi.impl.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRegistration
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiAuthOwner
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiDescriptor
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiEnabled
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiEngine
import io.aequicor.heartbeat.feature.aiengine.pi.impl.data.PiAdapter

/** Registers the built-in toggle without instantiating a profile or starting Pi. */
@BindingContainer
@ContributesTo(AppScope::class)
public object PiToggleBindings {
    /** Pi is installed and enabled with the desktop application. */
    @Provides
    @IntoSet
    public fun toggle(): FeatureToggle<*> = PiEnabled
}

/** Profile-owned lazy adapter registration and the public Pi configuration, both backed by one [PiAdapter]. */
@BindingContainer
@ContributesTo(ProfileScope::class)
public object PiRegistrationBindings {
    /** One registration per profile; the adapter is created only when the facade first uses the factory. */
    @Provides
    @IntoSet
    @SingleIn(ProfileScope::class)
    internal fun registration(adapter: Lazy<PiAdapter>): EngineRegistration =
        EngineRegistration(PiDescriptor, PiAuthOwner, adapter)

    /** Exposes only the configuration contract of the profile's adapter; the SPI stays internal. */
    @Provides
    internal fun engine(adapter: PiAdapter): PiEngine = adapter
}
