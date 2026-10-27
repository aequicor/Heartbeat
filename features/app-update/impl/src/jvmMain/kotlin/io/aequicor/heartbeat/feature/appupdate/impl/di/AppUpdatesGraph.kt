package io.aequicor.heartbeat.feature.appupdate.impl.di

import com.arkivanov.decompose.ComponentContext
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeFactory
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ext.retainedGraph
import io.aequicor.heartbeat.core.navigation.AppRouteBinding
import io.aequicor.heartbeat.core.navigation.NavComponent
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.core.navigation.RouteEntry
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdatesRoute
import io.aequicor.heartbeat.feature.appupdate.impl.di.scope.AppUpdatesScope
import io.aequicor.heartbeat.feature.appupdate.impl.presentation.AppUpdatesComponent
import io.aequicor.heartbeat.feature.appupdate.impl.ui.AppUpdatesUiComponent

/** Desktop-only feature graph: the update machine and the launch properties exist only there. */
@GraphExtension(AppUpdatesScope::class)
interface AppUpdatesGraph {
    val factory: AppUpdatesComponent.Factory

    /** Creates a feature scope directly below the application, without requiring a profile. */
    @ContributesTo(AppScope::class)
    @GraphExtension.Factory
    fun interface Factory {
        /** Creates the retained graph for one "Updates" page. */
        fun create(
            @Provides @ForScope(AppUpdatesScope::class) scope: ScopeHandle,
        ): AppUpdatesGraph
    }
}

@ContributesIntoSet(AppScope::class, binding = binding<AppRouteBinding>())
@Inject
internal class AppUpdatesRouteEntry(
    private val scopes: ScopeFactory,
    @ForScope(AppScope::class) private val app: ScopeHandle,
    private val graphs: AppUpdatesGraph.Factory,
) : RouteEntry<AppUpdatesRoute>(AppUpdatesRoute::class, AppUpdatesRoute.serializer()) {
    override fun create(route: AppUpdatesRoute, context: ComponentContext, navigator: Navigator): NavComponent =
        context.retainedGraph(scopes, app, name = "app-updates") { graphs.create(it) }
            .factory.create(context, navigator, route).let { AppUpdatesUiComponent(it) }
}
