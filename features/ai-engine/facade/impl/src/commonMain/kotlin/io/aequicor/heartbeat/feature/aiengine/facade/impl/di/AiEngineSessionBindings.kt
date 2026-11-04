package io.aequicor.heartbeat.feature.aiengine.facade.impl.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSources
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindings
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineRouteAdmission
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExactRouteReadiness
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeInteractionDemand
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderUsageCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineLaunchConfig
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.SessionHooks
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.ActiveSessionAssembler
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.ActiveSessionHost
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.ActiveSessionRegistry
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.BindingRelease
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.EnabledEngines
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.EngineRouteAdmissionService
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.ExactRouteReadinessService
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.FacadeCapabilities
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.FacadeContext
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.ProfileEngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.RouteResolver
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.RuntimePool
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.RuntimePoolShutdown
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.SessionCatalogService
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.SessionLauncher
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.SessionPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Profile-owned execution: runtime pool, open handles, session creation/resumption and the facade itself. */
@ContributesTo(ProfileScope::class)
@BindingContainer
object AiEngineSessionBindings {
    /** Open handles of the profile. */
    @Provides
    @SingleIn(ProfileScope::class)
    fun handles(): ActiveSessionRegistry = ActiveSessionRegistry()

    /** Native interaction demand of the profile: open handles awaiting a user action or an authentication request. */
    @Provides
    fun interactionDemand(handles: ActiveSessionRegistry, context: FacadeContext): NativeInteractionDemand =
        object : NativeInteractionDemand {
            override val required: Flow<Boolean> =
                combine(handles.awaitingInteraction, context.authenticationRequired) { interaction, authentication ->
                    interaction || authentication
                }
        }

    /** Explicit disconnection also stops accepted work; lazy access avoids a cycle through engine management. */
    @Provides
    fun release(pool: Lazy<RuntimePool>): BindingRelease = BindingRelease { pool.value.disconnect(it) }

    /** Rules shared by every handle. */
    @Provides
    @Suppress("LongParameterList") // DI mirrors the policy dependencies; a wrapper would hide graph edges.
    fun policy(
        routes: RouteResolver,
        enabled: EnabledEngines,
        handles: ActiveSessionRegistry,
        context: FacadeContext,
        tools: ProfileAgentTools,
        hooks: SessionHooks,
        workspaces: io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces,
    ): SessionPolicy = SessionPolicy(routes, enabled, handles, context, tools, hooks, workspaces)

    /** Builds handles; native commands run in the profile scope. */
    @Provides
    fun assembler(policy: SessionPolicy, context: FacadeContext): ActiveSessionAssembler =
        ActiveSessionAssembler(policy, context.scope)

    /** Runtimes live with the profile; application shutdown awaits any unfinished profile cleanup. */
    @Provides
    @SingleIn(ProfileScope::class)
    fun pool(
        context: FacadeContext,
        handles: ActiveSessionRegistry,
        launch: EngineLaunchConfig,
        @ForScope(ProfileScope::class) profile: ScopeHandle,
        @ForScope(AppScope::class) app: ScopeHandle,
        shutdown: RuntimePoolShutdown,
    ): RuntimePool = RuntimePool(context, handles::hasActiveTurn, handles::closeHandles, launch::context).also { pool ->
        val cleanup = shutdown.register(pool::closeAll)
        profile.onClose { app.coroutineScope.launch { cleanup() } }
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

    /** Explicit admission assessment of a saved route; never opens an ordinary runtime. */
    @Provides
    fun routeAdmission(routes: RouteResolver, pool: RuntimePool, context: FacadeContext): EngineRouteAdmission =
        EngineRouteAdmissionService(routes, pool, context)

    /** Cached exact-route readiness with one cancellable installation probe per explicit owner refresh. */
    @Provides
    @SingleIn(ProfileScope::class)
    fun exactRouteReadiness(
        engines: EngineCatalog,
        bindings: EngineBindings,
        sources: AuthSources,
        pool: RuntimePool,
        context: FacadeContext,
    ): ExactRouteReadiness = ExactRouteReadinessService(
        bindings.state,
        sources.state,
        engines.state,
        pool.entries,
        context,
        { engine -> engines.refresh(engine) },
    )

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
