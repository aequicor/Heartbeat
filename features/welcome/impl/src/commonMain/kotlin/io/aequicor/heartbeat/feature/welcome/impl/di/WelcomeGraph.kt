package io.aequicor.heartbeat.feature.welcome.impl.di

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
import io.aequicor.heartbeat.core.profilefacade.ProfileSessions
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.MachineLauncher
import io.aequicor.heartbeat.feature.welcome.api.WelcomeIntent
import io.aequicor.heartbeat.feature.welcome.api.WelcomeMachineSpec
import io.aequicor.heartbeat.feature.welcome.api.WelcomeOutput
import io.aequicor.heartbeat.feature.welcome.api.WelcomeRoute
import io.aequicor.heartbeat.feature.welcome.api.WelcomeState
import io.aequicor.heartbeat.feature.welcome.impl.di.scope.WelcomeScope
import io.aequicor.heartbeat.feature.welcome.impl.domain.WelcomeEffects
import io.aequicor.heartbeat.feature.welcome.impl.domain.WelcomeSettings
import io.aequicor.heartbeat.feature.welcome.impl.presentation.component.WelcomeComponent
import io.aequicor.heartbeat.feature.welcome.impl.ui.WelcomeUiComponent

/** Feature graph retained by its Decompose entry. */
@GraphExtension(WelcomeScope::class)
interface WelcomeGraph {
    val factory: WelcomeComponent.Factory

    /** Metro factory for a lifecycle-owned feature instance. */
    @ContributesTo(AppScope::class)
    @GraphExtension.Factory
    fun interface Factory {
        /** Creates an instance owned by the supplied component or scope. */
        fun createWelcome(
            @Provides @ForScope(WelcomeScope::class) scope: ScopeHandle,
        ): WelcomeGraph
    }
}

/** Public Metro contributions collected by the application bundle. */
@ContributesTo(WelcomeScope::class)
@BindingContainer
object WelcomeBindings {
    /** Wires the domain handler without coupling domain to Metro. */
    @Provides
    fun effects(settings: WelcomeSettings, profiles: ProfileSessions): WelcomeEffects =
        WelcomeEffects(settings, profiles)

    /** Launches the machine for the lifetime of this feature scope. */
    @Provides
    @SingleIn(WelcomeScope::class)
    fun machine(
        launcher: MachineLauncher,
        @ForScope(WelcomeScope::class) scope: ScopeHandle,
        effects: WelcomeEffects,
    ): Machine<WelcomeState, WelcomeIntent, WelcomeOutput> = launcher.launch(WelcomeMachineSpec, scope, effects)
}

@ContributesIntoSet(AppScope::class, binding = binding<AppRouteBinding>())
@Inject
internal class WelcomeRouteEntry(
    private val scopes: ScopeFactory,
    @ForScope(AppScope::class) private val app: ScopeHandle,
    private val graphs: WelcomeGraph.Factory,
) : RouteEntry<WelcomeRoute>(WelcomeRoute::class, WelcomeRoute.serializer()) {
    override fun create(route: WelcomeRoute, context: ComponentContext, navigator: Navigator): NavComponent =
        context.retainedGraph(scopes, app, name = "welcome") { graphs.createWelcome(it) }
            .factory.create(context, navigator).let { WelcomeUiComponent(it) }
}
