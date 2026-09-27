package io.aequicor.heartbeat.feature.aiengine.facade.impl.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Multibinds
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthChecks
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSources
import io.aequicor.heartbeat.feature.aiengine.facade.api.AiEngines
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindings
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePlatform
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRegistration
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.BindingStorage
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.EngineSettingsSpec
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.FeatureToggleEngineGate
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.ModelCacheSpec
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.ModelCacheStorage
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.BindingUsage
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.EngineBindingsService
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.EngineCatalogService
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.EngineGate
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.EngineRegistry
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.EngineToggles
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.FacadeContext
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.ModelCatalogService
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.NoEngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.RouteResolver
import kotlin.time.Clock
import kotlin.uuid.Uuid

// Binding containers and contributed interfaces are public: the graph is generated in :platform-main:di-bundle.

/** Registers the global AI-engine toggle for the control panel. */
@ContributesTo(AppScope::class)
interface AiEngineToggleContribution {
    /** The global flag; engine flags are registered by their adapters. */
    @Provides
    @IntoSet
    fun aiEngines(): FeatureToggle<*> = AiEngines
}

/** Engine registrations contributed by adapters through the application bundle. */
@ContributesTo(ProfileScope::class)
interface EngineRegistrationBindings {
    /** Every registration; empty while no adapter is bundled. */
    @Multibinds(allowEmpty = true)
    fun registrations(): Set<EngineRegistration>
}

/** Profile-owned facade services. */
@ContributesTo(ProfileScope::class)
@BindingContainer
object AiEngineFacadeBindings {
    /** Validated registrations for this host. */
    @Provides
    @SingleIn(ProfileScope::class)
    fun registry(registrations: Set<EngineRegistration>, platform: PlatformInfo): EngineRegistry =
        EngineRegistry(registrations, platform.host.enginePlatform())

    /** Toggle gate of engines. */
    @Provides
    fun toggles(toggles: FeatureToggles): EngineToggles = FeatureToggleEngineGate(toggles)

    /** Shared toggle/registration check. */
    @Provides
    fun gate(registry: EngineRegistry, toggles: EngineToggles): EngineGate = EngineGate(registry, toggles)

    /** Profile environment of the services. */
    @Provides
    @SingleIn(ProfileScope::class)
    fun context(
        @ForScope(ProfileScope::class) scope: ScopeHandle,
        clock: Clock,
        dispatchers: DispatcherProvider,
    ): FacadeContext = FacadeContext(scope.coroutineScope, clock, dispatchers.io) {
        Uuid.random().toHexString().take(TOKEN_LENGTH)
    }

    /** Saved engine/source relationships. */
    @Provides
    @SingleIn(ProfileScope::class)
    fun bindingsService(
        gate: EngineGate,
        @ForScope(ProfileScope::class) stores: DataStores,
        sources: AuthSources,
        checks: AuthChecks,
        context: FacadeContext,
    ): EngineBindingsService = EngineBindingsService(
        gate,
        BindingStorage(stores.keyValue(EngineSettingsSpec)),
        sources,
        checks,
        BindingUsage { false },
        context,
    )

    /** Public binding API. */
    @Provides
    fun bindings(service: EngineBindingsService): EngineBindings = service

    /** Engine catalog with cached availability. */
    @Provides
    @SingleIn(ProfileScope::class)
    fun catalogService(
        registry: EngineRegistry,
        toggles: EngineToggles,
        gate: EngineGate,
        bindings: EngineBindingsService,
        context: FacadeContext,
    ): EngineCatalogService = EngineCatalogService(registry, toggles, gate, bindings.state, context) {
        NoEngineFeatures
    }

    /** Public catalog API. */
    @Provides
    fun catalog(service: EngineCatalogService): EngineCatalog = service

    /** Explicit route checks shared by models and sessions. */
    @Provides
    fun routes(registry: EngineRegistry, gate: EngineGate, bindings: EngineBindingsService): RouteResolver =
        RouteResolver(registry, gate, bindings)

    /** Cached model discovery. */
    @Provides
    @SingleIn(ProfileScope::class)
    fun models(
        @ForScope(ProfileScope::class) stores: DataStores,
        routes: RouteResolver,
        context: FacadeContext,
    ): ModelCatalog = ModelCatalogService(ModelCacheStorage(stores.keyValue(ModelCacheSpec)), routes, context)

    private const val TOKEN_LENGTH = 20
}

private fun HostPlatform.enginePlatform(): EnginePlatform? = when (this) {
    HostPlatform.Android -> EnginePlatform.Android
    HostPlatform.Ios -> EnginePlatform.Ios
    HostPlatform.MacOs -> EnginePlatform.DesktopMacOs
    HostPlatform.Windows -> EnginePlatform.DesktopWindows
    HostPlatform.Linux -> null
}
