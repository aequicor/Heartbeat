package io.aequicor.heartbeat.feature.aistudio.impl.di

import com.arkivanov.decompose.ComponentContext
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeFactory
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ext.retainedGraph
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.navigation.NavComponent
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.core.navigation.ProfileRouteBinding
import io.aequicor.heartbeat.core.navigation.RouteEntry
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.MachineLauncher
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioIntent
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioMachineSpec
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioOutput
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioRoute
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioState
import io.aequicor.heartbeat.feature.aistudio.impl.data.StudioWorkspaceToggle
import io.aequicor.heartbeat.feature.aistudio.impl.di.scope.AiStudioScope
import io.aequicor.heartbeat.feature.aistudio.impl.domain.EngineStudioEffects
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioAvailability
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRepository
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRuntime
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.component.AiStudioComponent
import io.aequicor.heartbeat.feature.aistudio.impl.ui.AiStudioUiComponent

/** Feature graph retained by its Decompose entry. */
@GraphExtension(AiStudioScope::class)
interface AiStudioGraph {
    val factory: AiStudioComponent.Factory

    /** Metro factory for a lifecycle-owned feature instance. */
    @ContributesTo(ProfileScope::class)
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
    /** Wires the domain handler without coupling domain to Metro. */
    @Provides
    @SingleIn(AiStudioScope::class)
    fun effects(
        repository: StudioRepository,
        runtime: StudioRuntime,
        availability: StudioAvailability,
    ): EngineStudioEffects = EngineStudioEffects(repository, runtime, availability)

    /** Launches the machine for the lifetime of this feature scope. */
    @Provides
    @SingleIn(AiStudioScope::class)
    fun machine(
        launcher: MachineLauncher,
        @ForScope(AiStudioScope::class) scope: ScopeHandle,
        effects: EngineStudioEffects,
    ): Machine<AiStudioState, AiStudioIntent, AiStudioOutput> = launcher.launch(AiStudioMachineSpec, scope, effects)
}

/** Registers the studio toggles in the app-wide toggle catalog. */
@ContributesTo(AppScope::class)
@BindingContainer
object AiStudioToggleBindings {
    /** The workspace switch shown in the toggles panel. */
    @Provides
    @IntoSet
    fun workspace(): FeatureToggle<*> = StudioWorkspaceToggle
}

@ContributesIntoSet(ProfileScope::class, binding = binding<ProfileRouteBinding>())
@Inject
internal class AiStudioRouteEntry(
    private val scopes: ScopeFactory,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
    private val graphs: AiStudioGraph.Factory,
) : RouteEntry<AiStudioRoute>(AiStudioRoute::class, AiStudioRoute.serializer()) {
    override fun create(route: AiStudioRoute, context: ComponentContext, navigator: Navigator): NavComponent =
        context.retainedGraph(scopes, profile, name = "aistudio") { graphs.createAiStudio(it) }
            .factory.create(context, navigator).let { AiStudioUiComponent(it) }
}
