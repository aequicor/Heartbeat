package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.ContributesTo
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRegistration
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogConnections

/** Profile entry point for engine composition and connection setup. */
@ContributesTo(ProfileScope::class)
public interface KoogAccessors {
    /** Lazy adapter registrations collected by the application bundle. */
    public val engineRegistrations: Set<EngineRegistration>

    /** Koog connection metadata; credentials are provisioned through the profile SecretStore. */
    public val koogConnections: KoogConnections
}
