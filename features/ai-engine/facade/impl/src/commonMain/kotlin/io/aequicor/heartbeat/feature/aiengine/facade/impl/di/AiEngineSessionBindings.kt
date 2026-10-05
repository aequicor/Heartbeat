package io.aequicor.heartbeat.feature.aiengine.facade.impl.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindings
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderUsageCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineLaunchConfig
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.ActiveSessionAssembler
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.ActiveSessionHost
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.ActiveSessionRegistry
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.BindingUsage
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.EnabledEngines
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.FacadeCapabilities
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.FacadeContext
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.ProfileEngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.RouteResolver
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.RuntimePool
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.SessionCatalogService
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.SessionLauncher
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.SessionPolicy
import kotlinx.coroutines.launch

/** Profile-owned execution: runtime pool, open handles, session creation/resumption and the facade itself. */
@ContributesTo(ProfileScope::class)
@BindingContainer
object AiEngineSessionBindings {
    /** Open handles of the profile. */
    @Provides
    @SingleIn(ProfileScope::class)
    fun handles(): ActiveSessionRegistry = ActiveSessionRegistry()

    /** Bindings in use cannot be disconnected. */
    @Provides
    fun usage(handles: ActiveSessionRegistry): BindingUsage = handles

    /** Rules shared by every handle. */
    @Provides
    fun policy(
        routes: RouteResolver,
        enabled: EnabledEngines,
        handles: ActiveSessionRegistry,
        context: FacadeContext,
        tools: ProfileAgentTools,
    ): SessionPolicy = SessionPolicy(routes, enabled, handles, context, tools)

    /** Builds handles; native commands run in the profile scope. */
    @Provides
    fun assembler(policy: SessionPolicy, context: FacadeContext): ActiveSessionAssembler =
        ActiveSessionAssembler(policy, context.scope)

    /** Runtimes live with the profile; closing it releases them on the app scope. */
    @Provides
    @SingleIn(ProfileScope::class)
    fun pool(
        context: FacadeContext,
        handles: ActiveSessionRegistry,
        launch: EngineLaunchConfig,
        @ForScope(ProfileScope::class) profile: ScopeHandle,
        @ForScope(AppScope::class) app: ScopeHandle,
    ): RuntimePool = RuntimePool(context, handles::hasActiveTurn, handles::closeHandles, launch::context).also { pool ->
        profile.onClose { app.coroutineScope.launch { pool.closeAll() } }
    }

    /** Session creation and resumption. */
    @Provides
    @SingleIn(ProfileScope::class)
    fun launcher(
        routes: RouteResolver,
        pool: RuntimePool,
        sessions: SessionCatalogService,
        host: ActiveSessionHost,
        context: FacadeContext,
    ): SessionLauncher = SessionLauncher(routes, pool, sessions, host, context)

    /** Facade-implemented capabilities. */
    @Provides
    fun capabilities(
        sessions: SessionCatalog,
        launcher: Lazy<SessionLauncher>,
        handles: ActiveSessionRegistry,
    ): FacadeCapabilities = FacadeCapabilities(sessions, launcher, handles)

    /** Profile-owned entry point. */
    @Provides
    @SingleIn(ProfileScope::class)
    fun facade(
        engines: EngineCatalog,
        bindings: EngineBindings,
        models: ModelCatalog,
        sessions: SessionCatalog,
        providerUsage: ProviderUsageCatalog,
    ): EngineFacade = ProfileEngineFacade(engines, bindings, models, sessions, providerUsage)
}
