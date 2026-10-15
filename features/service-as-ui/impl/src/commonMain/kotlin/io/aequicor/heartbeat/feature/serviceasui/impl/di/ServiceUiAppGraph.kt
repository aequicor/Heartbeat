package io.aequicor.heartbeat.feature.serviceasui.impl.di

import com.arkivanov.decompose.ComponentContext
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides
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
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiAppRoute
import io.aequicor.heartbeat.feature.serviceasui.impl.di.scope.ServiceUiAppScope
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.component.AppHostComponent
import io.aequicor.heartbeat.feature.serviceasui.impl.ui.AppUiComponent

/** A pane's app lifecycle, independent of the transcript and of the current expanded presentation. */
@GraphExtension(ServiceUiAppScope::class)
interface ServiceUiAppGraph {
    val factory: AppHostComponent.Factory

    /** Creates the retained host graph under the current profile. */
    @ContributesTo(ProfileScope::class)
    @GraphExtension.Factory
    fun interface Factory {
        /** Binds only the host route and its lifecycle, never application data in navigation. */
        fun create(
            @Provides route: ServiceUiAppRoute,
            @Provides @ForScope(ServiceUiAppScope::class) scope: ScopeHandle,
        ): ServiceUiAppGraph
    }
}

/** One app host embedded continuously by a studio pane. */
@ContributesIntoSet(ProfileScope::class, binding = binding<ProfileRouteBinding>())
@Inject
internal class ServiceUiAppRouteEntry(
    private val scopes: ScopeFactory,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
    private val graphs: ServiceUiAppGraph.Factory,
) : RouteEntry<ServiceUiAppRoute>(ServiceUiAppRoute::class, ServiceUiAppRoute.serializer()) {
    override fun create(route: ServiceUiAppRoute, context: ComponentContext, navigator: Navigator): NavComponent {
        val graph = context.retainedGraph(scopes, profile, name = "service_ui_app") { graphs.create(route, it) }
        return AppUiComponent(graph.factory.create(context, navigator))
    }
}
