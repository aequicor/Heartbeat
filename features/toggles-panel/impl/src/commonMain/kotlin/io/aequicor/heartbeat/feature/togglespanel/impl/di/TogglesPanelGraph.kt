package io.aequicor.heartbeat.feature.togglespanel.impl.di

import com.arkivanov.decompose.ComponentContext
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeFactory
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ext.retainedGraph
import io.aequicor.heartbeat.core.navigation.AppRouteBinding
import io.aequicor.heartbeat.core.navigation.NavComponent
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.core.navigation.RouteEntry
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.MachineLauncher
import io.aequicor.heartbeat.feature.togglespanel.api.TogglesPanelIntent
import io.aequicor.heartbeat.feature.togglespanel.api.TogglesPanelMachineSpec
import io.aequicor.heartbeat.feature.togglespanel.api.TogglesPanelOutput
import io.aequicor.heartbeat.feature.togglespanel.api.TogglesPanelRoute
import io.aequicor.heartbeat.feature.togglespanel.api.TogglesPanelState
import io.aequicor.heartbeat.feature.togglespanel.impl.di.scope.TogglesPanelScope
import io.aequicor.heartbeat.feature.togglespanel.impl.domain.TogglesPanelEffects
import io.aequicor.heartbeat.feature.togglespanel.impl.domain.TogglesRepository
import io.aequicor.heartbeat.feature.togglespanel.impl.presentation.component.TogglesPanelComponent
import io.aequicor.heartbeat.feature.togglespanel.impl.ui.TogglesPanelUiComponent

/** Feature graph retained by its Decompose entry. */
@GraphExtension(TogglesPanelScope::class)
interface TogglesPanelGraph {
    val factory: TogglesPanelComponent.Factory

    /** Metro factory for a lifecycle-owned feature instance. */
    @ContributesTo(AppScope::class)
    @GraphExtension.Factory
    fun interface Factory {
        /** Creates an instance owned by the supplied component or scope. */
        fun createTogglesPanel(
            @Provides @ForScope(TogglesPanelScope::class) scope: ScopeHandle,
        ): TogglesPanelGraph
    }
}

/** Public Metro contributions collected by the application bundle. */
@ContributesTo(TogglesPanelScope::class)
@BindingContainer
object TogglesPanelBindings {
    /** Wires the domain handler without coupling domain to Metro. */
    @Provides
    fun effects(repository: TogglesRepository): TogglesPanelEffects = TogglesPanelEffects(repository)

    /** Launches the machine for the lifetime of this feature scope. */
    @Provides
    @SingleIn(TogglesPanelScope::class)
    fun machine(
        launcher: MachineLauncher,
        @ForScope(TogglesPanelScope::class) scope: ScopeHandle,
        effects: TogglesPanelEffects,
    ): Machine<TogglesPanelState, TogglesPanelIntent, TogglesPanelOutput> =
        launcher.launch(TogglesPanelMachineSpec, scope, effects)
}

@ContributesIntoSet(AppScope::class, binding = binding<AppRouteBinding>())
@Inject
internal class TogglesPanelRouteEntry(
    private val scopes: ScopeFactory,
    @ForScope(AppScope::class) private val app: ScopeHandle,
    private val graphs: TogglesPanelGraph.Factory,
) : RouteEntry<TogglesPanelRoute>(TogglesPanelRoute::class, TogglesPanelRoute.serializer()) {
    override fun create(route: TogglesPanelRoute, context: ComponentContext, navigator: Navigator): NavComponent =
        context.retainedGraph(scopes, app, name = "togglespanel") { graphs.createTogglesPanel(it) }
            .factory.create(context, navigator, route).let { TogglesPanelUiComponent(it) }
}
