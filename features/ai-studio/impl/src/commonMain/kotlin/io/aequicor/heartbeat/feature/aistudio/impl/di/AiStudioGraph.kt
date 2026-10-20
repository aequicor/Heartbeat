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
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.navigation.NavComponent
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.core.navigation.ProfileRouteBinding
import io.aequicor.heartbeat.core.navigation.RouteEntry
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.MachineLauncher
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioIntent
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioMachineSpec
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioOutput
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioRoute
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioState
import io.aequicor.heartbeat.feature.aistudio.api.StudioEngineRuntime
import io.aequicor.heartbeat.feature.aistudio.api.StudioEngineSwitching
import io.aequicor.heartbeat.feature.aistudio.api.StudioLocalProjects
import io.aequicor.heartbeat.feature.aistudio.impl.data.MachineStudioSurfaces
import io.aequicor.heartbeat.feature.aistudio.impl.data.StudioSafetyReviewsToggle
import io.aequicor.heartbeat.feature.aistudio.impl.data.StudioWorkspaceToggle
import io.aequicor.heartbeat.feature.aistudio.impl.di.scope.AiStudioScope
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioBackend
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioScreenHost
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSurfaces
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.component.AiStudioComponent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioModel
import io.aequicor.heartbeat.feature.aistudio.impl.ui.AiStudioUiComponent

/** Feature graph retained by its Decompose entry. */
@GraphExtension(AiStudioScope::class)
interface AiStudioGraph {
    val factory: AiStudioComponent.Factory

    /** The screen store; a remote view drives it without a window component. */
    val model: AiStudioModel

    /** Metro factory for a lifecycle-owned feature instance. */
    @ContributesTo(ProfileScope::class)
    @GraphExtension.Factory
    fun interface Factory {
        /** Creates an instance owned by the supplied component or scope. */
        fun createAiStudio(
            @Provides @ForScope(AiStudioScope::class) scope: ScopeHandle,
            @Provides host: StudioScreenHost,
        ): AiStudioGraph
    }
}

/** Public Metro contributions collected by the application bundle. */
@ContributesTo(AiStudioScope::class)
@BindingContainer
object AiStudioBindings {
    /** Only a native window renders and registers interface surfaces. */
    @Provides
    @SingleIn(AiStudioScope::class)
    fun surfaces(host: StudioScreenHost, machines: MachineRegistry, toggles: FeatureToggles): StudioSurfaces =
        if (host == StudioScreenHost.Window) MachineStudioSurfaces(machines, toggles) else StudioSurfaces.None

    /** Launches the machine for the lifetime of this feature scope. */
    @Provides
    @SingleIn(AiStudioScope::class)
    fun machine(
        launcher: MachineLauncher,
        @ForScope(AiStudioScope::class) scope: ScopeHandle,
        backend: StudioBackend,
        host: StudioScreenHost,
    ): Machine<AiStudioState, AiStudioIntent, AiStudioOutput> = launcher.launch(
        AiStudioMachineSpec,
        scope,
        EffectHandler { effect, machine -> backend.effects().handle(effect, machine) },
        // Other features address the window's studio; a remote view keeps its machine to itself.
        isAddressable = host == StudioScreenHost.Window,
    )
}

/** Registers the studio toggles in the app-wide toggle catalog. */
@ContributesTo(AppScope::class)
@BindingContainer
object AiStudioToggleBindings {
    /** The workspace switch shown in the toggles panel. */
    @Provides
    @IntoSet
    fun workspace(): FeatureToggle<*> = StudioWorkspaceToggle

    /** Engine-backed chats instead of the demo workspace. */
    @Provides
    @IntoSet
    fun engineRuntime(): FeatureToggle<*> = StudioEngineRuntime

    /** Explicit chat engine switching, disabled by default. */
    @Provides
    @IntoSet
    fun engineSwitching(): FeatureToggle<*> = StudioEngineSwitching

    /** Experimental local folders backed by real CLI workspaces. */
    @Provides
    @IntoSet
    fun localProjects(): FeatureToggle<*> = StudioLocalProjects

    /** The sidebar indicator of automatic safety reviews. */
    @Provides
    @IntoSet
    fun safetyReviews(): FeatureToggle<*> = StudioSafetyReviewsToggle
}

@ContributesIntoSet(ProfileScope::class, binding = binding<ProfileRouteBinding>())
@Inject
internal class AiStudioRouteEntry(
    private val scopes: ScopeFactory,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
    private val graphs: AiStudioGraph.Factory,
) : RouteEntry<AiStudioRoute>(AiStudioRoute::class, AiStudioRoute.serializer()) {
    override fun create(route: AiStudioRoute, context: ComponentContext, navigator: Navigator): NavComponent =
        context.retainedGraph(scopes, profile, name = "aistudio") { graphs.createAiStudio(it, StudioScreenHost.Window) }
            .factory.create(context, navigator).let { AiStudioUiComponent(it) }
}
