package io.aequicor.heartbeat.feature.aiengine.connections.impl.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSources
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsEnabled
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelections
import io.aequicor.heartbeat.feature.aiengine.connections.impl.data.StoredModelCheckReports
import io.aequicor.heartbeat.feature.aiengine.connections.impl.domain.EngineServices
import io.aequicor.heartbeat.feature.aiengine.connections.impl.domain.ModelCheckBatch
import io.aequicor.heartbeat.feature.aiengine.connections.impl.domain.ModelCheckProbes
import io.aequicor.heartbeat.feature.aiengine.connections.impl.domain.ModelChecksCoordinator
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagement
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineRouteAdmission
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompts
import kotlinx.coroutines.CoroutineScope

/** Profile-level contributions shared by the wizard and the settings space. */
@ContributesTo(ProfileScope::class)
@BindingContainer
object ConnectionsBindings {
    /** Engine services of the profile: the facade runtime, the source registry, engine management and admission. */
    @Provides
    @SingleIn(ProfileScope::class)
    fun services(
        facade: EngineFacade,
        sources: AuthSources,
        management: EngineManagement,
        admission: EngineRouteAdmission,
    ): EngineServices = EngineServices(facade, sources, management, admission)

    /** The wizard and settings share run ownership, publication ordering and the profile cleanup lifetime. */
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun modelChecks(
        services: EngineServices,
        selections: ModelSelections,
        reports: StoredModelCheckReports,
        prompts: IsolatedPrompts,
        @ForScope(ProfileScope::class) scope: CoroutineScope,
    ): ModelChecksCoordinator = ModelChecksCoordinator(
        services,
        selections,
        reports,
        scope,
        ModelCheckBatch(scope, ModelCheckProbes(prompts)),
    )
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
