package io.aequicor.heartbeat.feature.researchchat.impl.di

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
import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatEnabled
import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatIntent
import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatMachineSpec
import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatOutput
import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatRoute
import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatState
import io.aequicor.heartbeat.feature.researchchat.impl.data.ResearchChatEffects
import io.aequicor.heartbeat.feature.researchchat.impl.di.scope.ResearchChatScope
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.component.ResearchComponent
import io.aequicor.heartbeat.feature.researchchat.impl.ui.ResearchUiComponent

/** Retains the research machine and screen inputs under the active profile. */
@GraphExtension(ResearchChatScope::class)
interface ResearchChatGraph {
    val factory: ResearchComponent.Factory

    /** Creates a workspace graph with its selected model route. */
    @ContributesTo(ProfileScope::class)
    @GraphExtension.Factory
    fun interface Factory {
        /** Binds the model route and lifecycle owner of the navigation entry. */
        fun create(
            @Provides route: ResearchChatRoute,
            @Provides @ForScope(ResearchChatScope::class) scope: ScopeHandle,
        ): ResearchChatGraph
    }
}

/** Business machine binding for the retained feature graph. */
@ContributesTo(ResearchChatScope::class)
@BindingContainer
object ResearchChatBindings {
    @Provides
    @SingleIn(ResearchChatScope::class)
    internal fun machine(
        launcher: MachineLauncher,
        @ForScope(ResearchChatScope::class) scope: ScopeHandle,
        effects: ResearchChatEffects,
    ): Machine<ResearchChatState, ResearchChatIntent, ResearchChatOutput> =
        launcher.launch(ResearchChatMachineSpec, scope, effects)
}

/** Registers the research flag in the existing feature toggle panel. */
@ContributesTo(AppScope::class)
@BindingContainer
object ResearchChatToggleBindings {
    /** Off by default; the panel is the only configuration writer. */
    @Provides
    @IntoSet
    fun research(): FeatureToggle<*> = ResearchChatEnabled
}

@ContributesIntoSet(ProfileScope::class, binding = binding<ProfileRouteBinding>())
@Inject
internal class ResearchChatRouteEntry(
    private val scopes: ScopeFactory,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
    private val graphs: ResearchChatGraph.Factory,
) : RouteEntry<ResearchChatRoute>(ResearchChatRoute::class, ResearchChatRoute.serializer()) {
    override fun create(route: ResearchChatRoute, context: ComponentContext, navigator: Navigator): NavComponent =
        context.retainedGraph(scopes, profile, name = "researchchat") { graphs.create(route, it) }
            .factory.create(context, navigator).let { ResearchUiComponent(it) }
}
