package io.aequicor.heartbeat.feature.aiengine.facade.impl.di

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.ModelCacheSpec
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.ModelCacheStorage
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.RoomSessionIndex
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.SessionCursorCodec
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.SessionIndexDatabaseSpec
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.EnabledEngines
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.EngineBindingsService
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.EngineCatalogService
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.EngineGate
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.FacadeCapabilities
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.FacadeContext
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.ModelCatalogService
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.RouteResolver
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.SessionCatalogService
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.SessionCursors
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.SessionIndex

/** Profile-owned catalogs of engines, models and sessions. */
@ContributesTo(ProfileScope::class)
@BindingContainer
object AiEngineCatalogBindings {
    /** Engine catalog with cached availability and facade-implemented engine-wide capabilities. */
    @Provides
    @SingleIn(ProfileScope::class)
    fun catalogService(
        gate: EngineGate,
        enabled: EnabledEngines,
        bindings: EngineBindingsService,
        capabilities: FacadeCapabilities,
        context: FacadeContext,
    ): EngineCatalogService = EngineCatalogService(gate, enabled, bindings.state, context, capabilities::engine)

    /** Public catalog API. */
    @Provides
    fun catalog(service: EngineCatalogService): EngineCatalog = service

    /** Explicit route checks shared by models and sessions. */
    @Provides
    fun routes(gate: EngineGate, bindings: EngineBindingsService): RouteResolver =
        RouteResolver(gate.registry, gate, bindings)

    /** Cached model discovery. */
    @Provides
    @SingleIn(ProfileScope::class)
    fun models(
        @ForScope(ProfileScope::class) stores: DataStores,
        routes: RouteResolver,
        context: FacadeContext,
    ): ModelCatalog = ModelCatalogService(ModelCacheStorage(stores.keyValue(ModelCacheSpec)), routes, context)

    /** Session index in the profile database. */
    @Provides
    fun sessionIndex(
        @ForScope(ProfileScope::class) stores: DataStores,
    ): SessionIndex = RoomSessionIndex(stores.database(SessionIndexDatabaseSpec))

    /** Session cursors bound to this profile. */
    @Provides
    fun cursors(profile: ProfileId): SessionCursors = SessionCursorCodec(profile.value)

    /** Unified session catalog over the profile index. */
    @Provides
    @SingleIn(ProfileScope::class)
    fun sessionService(
        enabled: EnabledEngines,
        index: SessionIndex,
        cursors: SessionCursors,
        capabilities: Lazy<FacadeCapabilities>,
        context: FacadeContext,
    ): SessionCatalogService = SessionCatalogService(enabled, index, cursors, context) { registration, stored ->
        capabilities.value.stored(registration, stored)
    }

    /** Public session catalog API. */
    @Provides
    fun sessions(service: SessionCatalogService): SessionCatalog = service
}
