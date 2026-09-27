package io.aequicor.heartbeat.feature.aistudio.impl.di

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
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.MachineLauncher
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioIntent
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioMachineSpec
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioOutput
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioRoute
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioState
import io.aequicor.heartbeat.feature.aistudio.impl.di.scope.AiStudioScope
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.component.AiStudioComponent
import io.aequicor.heartbeat.feature.aistudio.impl.ui.AiStudioUiComponent

/** Feature graph retained by its Decompose entry. */
@GraphExtension(AiStudioScope::class)
interface AiStudioGraph {
    val factory: AiStudioComponent.Factory

    /** Metro factory for a lifecycle-owned feature instance. */
    @ContributesTo(AppScope::class)
    @GraphExtension.Factory
    fun interface Factory {
        /** Creates an instance owned by the supplied component or scope. */
        fun createAiStudio(
            @Provides @ForScope(AiStudioScope::class) scope: ScopeHandle,
        ): AiStudioGraph
    }
}

/** Public Metro contributions collected by the application bundle. */
@ContributesTo(AiStudioScope::class)
@BindingContainer
object AiStudioBindings {
    /** Launches the machine for the lifetime of this feature scope. */
    @Provides
    @SingleIn(AiStudioScope::class)
    fun machine(
        launcher: MachineLauncher,
        @ForScope(AiStudioScope::class) scope: ScopeHandle,
    ): Machine<AiStudioState, AiStudioIntent, AiStudioOutput> =
        launcher.launch(AiStudioMachineSpec, scope, EffectHandler.None)
}

@ContributesIntoSet(AppScope::class, binding = binding<AppRouteBinding>())
@Inject
internal class AiStudioRouteEntry(
    private val scopes: ScopeFactory,
    @ForScope(AppScope::class) private val app: ScopeHandle,
    private val graphs: AiStudioGraph.Factory,
) : RouteEntry<AiStudioRoute>(AiStudioRoute::class, AiStudioRoute.serializer()) {
    override fun create(route: AiStudioRoute, context: ComponentContext, navigator: Navigator): NavComponent =
        context.retainedGraph(scopes, app, name = "aistudio") { graphs.createAiStudio(it) }
            .factory.create(context, navigator).let { AiStudioUiComponent(it) }
}
