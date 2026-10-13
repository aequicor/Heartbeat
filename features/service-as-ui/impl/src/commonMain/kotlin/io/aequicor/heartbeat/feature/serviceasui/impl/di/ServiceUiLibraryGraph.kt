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
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiLibraryRoute
import io.aequicor.heartbeat.feature.serviceasui.impl.di.scope.ServiceUiLibraryScope
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.component.LibraryComponent
import io.aequicor.heartbeat.feature.serviceasui.impl.ui.LibraryUiComponent

/** Graph of the library screen, retained by its navigation entry under the profile. */
@GraphExtension(ServiceUiLibraryScope::class)
interface ServiceUiLibraryGraph {
    val factory: LibraryComponent.Factory

    /** Creates the graph of the library screen. */
    @ContributesTo(ProfileScope::class)
    @GraphExtension.Factory
    fun interface Factory {
        /** Binds the shown route and the lifecycle owner of the navigation entry. */
        fun create(
            @Provides route: ServiceUiLibraryRoute,
            @Provides @ForScope(ServiceUiLibraryScope::class) scope: ScopeHandle,
        ): ServiceUiLibraryGraph
    }
}

/** The surface library of the profile, standalone or inside the settings window. */
@ContributesIntoSet(ProfileScope::class, binding = binding<ProfileRouteBinding>())
@Inject
internal class ServiceUiLibraryRouteEntry(
    private val scopes: ScopeFactory,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
    private val graphs: ServiceUiLibraryGraph.Factory,
) : RouteEntry<ServiceUiLibraryRoute>(ServiceUiLibraryRoute::class, ServiceUiLibraryRoute.serializer()) {
    override fun create(route: ServiceUiLibraryRoute, context: ComponentContext, navigator: Navigator): NavComponent {
        val graph = context.retainedGraph(scopes, profile, name = "service_ui_library") { graphs.create(route, it) }
        return LibraryUiComponent(graph.factory.create(context, navigator), route.isEmbedded)
    }
}
