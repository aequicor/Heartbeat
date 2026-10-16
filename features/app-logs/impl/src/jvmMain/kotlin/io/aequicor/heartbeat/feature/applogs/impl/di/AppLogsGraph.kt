package io.aequicor.heartbeat.feature.applogs.impl.di

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
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.DesktopLogFiles
import io.aequicor.heartbeat.core.navigation.AppRouteBinding
import io.aequicor.heartbeat.core.navigation.NavComponent
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.core.navigation.RouteEntry
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.MachineLauncher
import io.aequicor.heartbeat.feature.applogs.api.AppLogsIntent
import io.aequicor.heartbeat.feature.applogs.api.AppLogsMachineSpec
import io.aequicor.heartbeat.feature.applogs.api.AppLogsOutput
import io.aequicor.heartbeat.feature.applogs.api.AppLogsRoute
import io.aequicor.heartbeat.feature.applogs.api.AppLogsState
import io.aequicor.heartbeat.feature.applogs.impl.data.AppLogsEffects
import io.aequicor.heartbeat.feature.applogs.impl.di.scope.AppLogsScope
import io.aequicor.heartbeat.feature.applogs.impl.domain.AppLogsActions
import io.aequicor.heartbeat.feature.applogs.impl.presentation.AppLogsComponent
import io.aequicor.heartbeat.feature.applogs.impl.ui.AppLogsUiComponent

/** Desktop-only feature graph: no native bindings are requested by Android or iOS graphs. */
@GraphExtension(AppLogsScope::class)
interface AppLogsGraph {
    val factory: AppLogsComponent.Factory

    /** Creates a feature scope directly below the application, without requiring a profile. */
    @ContributesTo(AppScope::class)
    @GraphExtension.Factory
    fun interface Factory {
        /** Creates the retained graph for one logs screen. */
        fun create(
            @Provides @ForScope(AppLogsScope::class) scope: ScopeHandle,
        ): AppLogsGraph
    }
}

/** Providers required by this feature only on Desktop. */
@ContributesTo(AppLogsScope::class)
@BindingContainer
object AppLogsBindings {
    /** Uses the same path and snapshot rules as the application file sink. */
    @Provides
    fun files(): DesktopLogFiles = DesktopLogFiles()

    /** Keeps the effect adapter testable without a graph. */
    @Provides
    fun effects(actions: AppLogsActions, toggles: FeatureToggles): AppLogsEffects = AppLogsEffects(actions, toggles)

    /** The native operation is cancelled when its settings entry is closed. */
    @Provides
    @SingleIn(AppLogsScope::class)
    fun machine(
        launcher: MachineLauncher,
        @ForScope(AppLogsScope::class) scope: ScopeHandle,
        effects: AppLogsEffects,
    ): Machine<AppLogsState, AppLogsIntent, AppLogsOutput> = launcher.launch(AppLogsMachineSpec, scope, effects)
}

@ContributesIntoSet(AppScope::class, binding = binding<AppRouteBinding>())
@Inject
internal class AppLogsRouteEntry(
    private val scopes: ScopeFactory,
    @ForScope(AppScope::class) private val app: ScopeHandle,
    private val graphs: AppLogsGraph.Factory,
) : RouteEntry<AppLogsRoute>(AppLogsRoute::class, AppLogsRoute.serializer()) {
    override fun create(route: AppLogsRoute, context: ComponentContext, navigator: Navigator): NavComponent =
        context.retainedGraph(scopes, app, name = "app-logs") { graphs.create(it) }
            .factory.create(context, navigator, route).let { AppLogsUiComponent(it) }
}
