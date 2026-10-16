package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.di

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
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlIntent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlMachineSpec
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlOutput
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlPanelRoute
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlRoute
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlState
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.di.scope.EngineControlScope
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.domain.EngineControlEffects
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.domain.EngineControlServices
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.component.EngineControlListComponent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.component.EngineControlPanelComponent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineControlTarget
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.ui.EngineControlListUiComponent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.ui.EngineControlPanelUiComponent
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagement
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces

/** Graph of one engine control screen (the engine list or one engine's panel), retained by its navigation entry. */
@GraphExtension(EngineControlScope::class)
interface EngineControlGraph {
    val list: EngineControlListComponent.Factory
    val panel: EngineControlPanelComponent.Factory

    /** Metro factory, generated together with the profile graph. */
    @ContributesTo(ProfileScope::class)
    @GraphExtension.Factory
    fun interface Factory {
        /** Creates a screen graph owned by [scope] for [target]. */
        fun createEngineControl(
            @Provides @ForScope(EngineControlScope::class) scope: ScopeHandle,
            @Provides target: EngineControlTarget,
        ): EngineControlGraph
    }
}

/** The panel machine and the services it works through. */
@ContributesTo(EngineControlScope::class)
@BindingContainer
object EngineControlBindings {
    /** Launches the machine for the lifetime of the screen. */
    @Provides
    @SingleIn(EngineControlScope::class)
    fun machine(
        launcher: MachineLauncher,
        @ForScope(EngineControlScope::class) scope: ScopeHandle,
        management: EngineManagement,
        configuration: EngineConfiguration,
        workspaces: LocalWorkspaces,
    ): Machine<EngineControlState, EngineControlIntent, EngineControlOutput> = launcher.launch(
        EngineControlMachineSpec,
        scope,
        EngineControlEffects(EngineControlServices(management, configuration, workspaces)),
    )
}

@ContributesIntoSet(ProfileScope::class, binding = binding<ProfileRouteBinding>())
@Inject
internal class EngineControlRouteEntry(
    private val scopes: ScopeFactory,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
    private val graphs: EngineControlGraph.Factory,
) : RouteEntry<EngineControlRoute>(EngineControlRoute::class, EngineControlRoute.serializer()) {
    override fun create(route: EngineControlRoute, context: ComponentContext, navigator: Navigator): NavComponent =
        context.retainedGraph(scopes, profile, name = "enginecontrol") {
            graphs.createEngineControl(it, EngineControlTarget(null))
        }.list.create(context, navigator, route.isEmbedded).let { EngineControlListUiComponent(it) }
}

@ContributesIntoSet(ProfileScope::class, binding = binding<ProfileRouteBinding>())
@Inject
internal class EngineControlPanelRouteEntry(
    private val scopes: ScopeFactory,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
    private val graphs: EngineControlGraph.Factory,
) : RouteEntry<EngineControlPanelRoute>(EngineControlPanelRoute::class, EngineControlPanelRoute.serializer()) {
    override fun create(route: EngineControlPanelRoute, context: ComponentContext, navigator: Navigator): NavComponent =
        context.retainedGraph(scopes, profile, name = "enginecontrolpanel") {
            graphs.createEngineControl(it, EngineControlTarget(route.engine))
        }.panel.create(context, navigator).let { EngineControlPanelUiComponent(it) }
}
