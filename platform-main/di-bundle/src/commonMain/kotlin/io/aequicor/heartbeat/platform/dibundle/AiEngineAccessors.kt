package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.ContributesTo
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDefaults
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRegistration
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiEngine

/** Profile entry point for the bundled AI engines and future facade implementations. */
@ContributesTo(ProfileScope::class)
public interface AiEngineAccessors {
    /**
     * Pi workspace configuration for the upcoming engine connection UI; credential routes go through the facade.
     * Resolving it constructs the profile's Pi adapter, but never starts Pi.
     */
    public val piEngine: PiEngine

    /** Lazy registrations; metadata access never constructs an adapter or launches Pi. */
    public val engineRegistrations: Set<EngineRegistration>

    /** Default choice for new routes, respecting platform and toggles. */
    public val engineDefaults: EngineDefaults
}
