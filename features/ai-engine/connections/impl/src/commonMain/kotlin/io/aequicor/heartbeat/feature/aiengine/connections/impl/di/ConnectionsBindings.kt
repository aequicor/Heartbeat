package io.aequicor.heartbeat.feature.aiengine.connections.impl.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSources
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsEnabled
import io.aequicor.heartbeat.feature.aiengine.connections.impl.data.UnbundledAuthSources
import io.aequicor.heartbeat.feature.aiengine.connections.impl.data.UnbundledEngineFacade
import io.aequicor.heartbeat.feature.aiengine.connections.impl.domain.EngineServices
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade

/** Profile-level contributions shared by the wizard and the settings space. */
@ContributesTo(ProfileScope::class)
@BindingContainer
object ConnectionsBindings {
    /**
     * Engine services of the profile. Both parameters are optional dependencies: until the bundle binds a facade
     * runtime and a source registry, stand-ins show an empty catalog and reject writes. The real bindings must be
     * visible from ProfileScope (contributed to it or to AppScope); a narrower scope is not seen here.
     */
    @Provides
    @SingleIn(ProfileScope::class)
    fun services(
        facade: EngineFacade = UnbundledEngineFacade(),
        sources: AuthSources = UnbundledAuthSources(),
    ): EngineServices = EngineServices(facade, sources)
}

/** Registers the feature toggle in the app-wide catalog. */
@ContributesTo(AppScope::class)
@BindingContainer
object ConnectionsToggleBindings {
    /** Connection setup screens. */
    @Provides
    @IntoSet
    fun connectionsEnabled(): FeatureToggle<*> = EngineConnectionsEnabled
}
