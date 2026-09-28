package io.aequicor.heartbeat.feature.aiengine.connections.impl.di

import com.arkivanov.decompose.ComponentContext
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeFactory
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ext.retainedGraph
import io.aequicor.heartbeat.core.navigation.NavComponent
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.core.navigation.ProfileRouteBinding
import io.aequicor.heartbeat.core.navigation.RouteEntry
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.MachineLauncher
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsIntent
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsMachineSpec
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsOutput
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsRoute
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsState
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelections
import io.aequicor.heartbeat.feature.aiengine.connections.impl.di.scope.EngineConnectionsScope
import io.aequicor.heartbeat.feature.aiengine.connections.impl.domain.EngineConnectionsEffects
import io.aequicor.heartbeat.feature.aiengine.connections.impl.domain.EngineServices
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.component.EngineConnectionsComponent
import io.aequicor.heartbeat.feature.aiengine.connections.impl.ui.EngineConnectionsUiComponent

/** Settings space graph retained by its navigation entry. */
@GraphExtension(EngineConnectionsScope::class)
interface EngineConnectionsGraph {
    val factory: EngineConnectionsComponent.Factory

    /** Metro factory, generated together with the profile graph. */
    @ContributesTo(ProfileScope::class)
    @GraphExtension.Factory
    fun interface Factory {
        /** Creates a settings space owned by [scope]. */
        fun createEngineConnections(
            @Provides @ForScope(EngineConnectionsScope::class) scope: ScopeHandle,
        ): EngineConnectionsGraph
    }
}

/** Settings machine and its effects. */
@ContributesTo(EngineConnectionsScope::class)
@BindingContainer
object EngineConnectionsBindings {
    /** Launches the machine for the lifetime of the settings space. */
    @Provides
    @SingleIn(EngineConnectionsScope::class)
    fun machine(
        launcher: MachineLauncher,
        @ForScope(EngineConnectionsScope::class) scope: ScopeHandle,
        services: EngineServices,
        selections: ModelSelections,
    ): Machine<EngineConnectionsState, EngineConnectionsIntent, EngineConnectionsOutput> =
        launcher.launch(EngineConnectionsMachineSpec, scope, EngineConnectionsEffects(services, selections))
}

@ContributesIntoSet(ProfileScope::class, binding = binding<ProfileRouteBinding>())
@Inject
internal class EngineConnectionsRouteEntry(
    private val scopes: ScopeFactory,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
    private val graphs: EngineConnectionsGraph.Factory,
) : RouteEntry<EngineConnectionsRoute>(EngineConnectionsRoute::class, EngineConnectionsRoute.serializer()) {
    override fun create(route: EngineConnectionsRoute, context: ComponentContext, navigator: Navigator): NavComponent =
        context.retainedGraph(scopes, profile, name = "engineconnections") { graphs.createEngineConnections(it) }
            .factory.create(context, navigator, route).let { EngineConnectionsUiComponent(it) }
}
