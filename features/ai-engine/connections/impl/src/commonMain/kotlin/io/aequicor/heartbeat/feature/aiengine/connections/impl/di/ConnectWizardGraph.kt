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
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectEngineRoute
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardIntent
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardMachineSpec
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardOutput
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardState
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelections
import io.aequicor.heartbeat.feature.aiengine.connections.impl.di.scope.ConnectWizardScope
import io.aequicor.heartbeat.feature.aiengine.connections.impl.domain.ConnectWizardEffects
import io.aequicor.heartbeat.feature.aiengine.connections.impl.domain.EngineServices
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.component.ConnectWizardComponent
import io.aequicor.heartbeat.feature.aiengine.connections.impl.ui.ConnectWizardUiComponent

/** Wizard graph retained by its navigation entry. */
@GraphExtension(ConnectWizardScope::class)
interface ConnectWizardGraph {
    val factory: ConnectWizardComponent.Factory

    /** Metro factory, generated together with the profile graph. */
    @ContributesTo(ProfileScope::class)
    @GraphExtension.Factory
    fun interface Factory {
        /** Creates a wizard for [route], owned by [scope]. */
        fun createConnectWizard(
            @Provides route: ConnectEngineRoute,
            @Provides @ForScope(ConnectWizardScope::class) scope: ScopeHandle,
        ): ConnectWizardGraph
    }
}

/** Wizard machine and its effects. */
@ContributesTo(ConnectWizardScope::class)
@BindingContainer
object ConnectWizardBindings {
    /** Launches the machine for the lifetime of the wizard. */
    @Provides
    @SingleIn(ConnectWizardScope::class)
    fun machine(
        launcher: MachineLauncher,
        @ForScope(ConnectWizardScope::class) scope: ScopeHandle,
        services: EngineServices,
        selections: ModelSelections,
    ): Machine<ConnectWizardState, ConnectWizardIntent, ConnectWizardOutput> =
        launcher.launch(ConnectWizardMachineSpec, scope, ConnectWizardEffects(services, selections))
}

@ContributesIntoSet(ProfileScope::class, binding = binding<ProfileRouteBinding>())
@Inject
internal class ConnectEngineRouteEntry(
    private val scopes: ScopeFactory,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
    private val graphs: ConnectWizardGraph.Factory,
) : RouteEntry<ConnectEngineRoute>(ConnectEngineRoute::class, ConnectEngineRoute.serializer()) {
    override fun create(route: ConnectEngineRoute, context: ComponentContext, navigator: Navigator): NavComponent =
        context.retainedGraph(scopes, profile, name = "connectwizard") { graphs.createConnectWizard(route, it) }
            .factory.create(context, navigator).let { ConnectWizardUiComponent(it) }
}
