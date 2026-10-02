package io.aequicor.heartbeat.feature.aiengine.facade.impl.di

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagement
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineLaunchConfig
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ReleaseFeeds
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.ToggleEngineFlags
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.install.HttpReleaseFeeds
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.ActiveSessionRegistry
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.EngineBindingsService
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.EngineFlags
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.EngineManagementService
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.EnginePreferences
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.EngineRegistry
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.EngineRuntimes
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.FacadeContext
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.ManagedInstallStore
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.PooledEngineRuntimes
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.RuntimePool
import io.ktor.client.HttpClient

/** Engine management of the profile: the service, the flags it reads and the release feeds adapters use. */
@ContributesTo(ProfileScope::class)
@BindingContainer
object AiEngineManagementBindings {
    /** Release metadata over the app's HTTP client. */
    @Provides
    fun feeds(client: HttpClient): ReleaseFeeds = HttpReleaseFeeds(client)

    /** Developer flags engine management reads. */
    @Provides
    fun flags(toggles: FeatureToggles): EngineFlags = ToggleEngineFlags(toggles)

    /** Pooled runtimes and open handles of the profile. */
    @Provides
    fun runtimes(pool: RuntimePool, handles: ActiveSessionRegistry): EngineRuntimes = PooledEngineRuntimes(
        pool,
        handles,
    )

    /** The management service; it observes and reconciles for the profile's lifetime. */
    @Provides
    @SingleIn(ProfileScope::class)
    @Suppress("LongParameterList") // a DI provider mirrors the service constructor; grouping would only hide deps
    fun service(
        registry: EngineRegistry,
        flags: EngineFlags,
        preferences: EnginePreferences,
        catalog: EngineCatalog,
        bindings: EngineBindingsService,
        runtimes: EngineRuntimes,
        installs: ManagedInstallStore,
        launch: EngineLaunchConfig,
        feeds: ReleaseFeeds,
        context: FacadeContext,
    ): EngineManagementService = EngineManagementService(
        registry,
        flags,
        preferences,
        catalog,
        bindings.state,
        runtimes,
        installs,
        launch,
        feeds,
        context,
    )

    /** Public management contract. */
    @Provides
    fun management(service: EngineManagementService): EngineManagement = service
}
