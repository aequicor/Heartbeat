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
import io.aequicor.heartbeat.feature.aiengine.connections.impl.domain.EngineServices
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagement

/** Profile-level contributions shared by the wizard and the settings space. */
@ContributesTo(ProfileScope::class)
@BindingContainer
object ConnectionsBindings {
    /** Engine services of the profile: the facade runtime, the source registry and engine management. */
    @Provides
    @SingleIn(ProfileScope::class)
    fun services(facade: EngineFacade, sources: AuthSources, management: EngineManagement): EngineServices =
        EngineServices(facade, sources, management)
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
