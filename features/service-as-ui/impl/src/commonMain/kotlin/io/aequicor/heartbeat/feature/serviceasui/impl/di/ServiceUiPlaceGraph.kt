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
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiPanelRoute
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiSlotRoute
import io.aequicor.heartbeat.feature.serviceasui.impl.di.scope.ServiceUiPlaceScope
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.component.PlaceComponent
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place.ServiceUiPlace
import io.aequicor.heartbeat.feature.serviceasui.impl.ui.PlaceUiComponent

/** Graph of one place of surfaces (a slot or a dock panel), retained by its navigation entry under the profile. */
@GraphExtension(ServiceUiPlaceScope::class)
interface ServiceUiPlaceGraph {
    val factory: PlaceComponent.Factory

    /** Creates the graph of a place. */
    @ContributesTo(ProfileScope::class)
    @GraphExtension.Factory
    fun interface Factory {
        /** Binds the place a route names and the lifecycle owner of the navigation entry. */
        fun create(
            @Provides place: ServiceUiPlace,
            @Provides @ForScope(ServiceUiPlaceScope::class) scope: ScopeHandle,
        ): ServiceUiPlaceGraph
    }
}

/** Inline surfaces and launchers of a slot of a host place. */
@ContributesIntoSet(ProfileScope::class, binding = binding<ProfileRouteBinding>())
@Inject
internal class ServiceUiSlotRouteEntry(
    private val scopes: ScopeFactory,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
    private val graphs: ServiceUiPlaceGraph.Factory,
) : RouteEntry<ServiceUiSlotRoute>(ServiceUiSlotRoute::class, ServiceUiSlotRoute.serializer()) {
    override fun create(route: ServiceUiSlotRoute, context: ComponentContext, navigator: Navigator): NavComponent {
        val graph = context.retainedGraph(scopes, profile, name = "service_ui_slot") {
            graphs.create(ServiceUiPlace.of(route), it)
        }
        return PlaceUiComponent(graph.factory.create(context, navigator))
    }
}

/** The surface open in the panel of a dock. */
@ContributesIntoSet(ProfileScope::class, binding = binding<ProfileRouteBinding>())
@Inject
internal class ServiceUiPanelRouteEntry(
    private val scopes: ScopeFactory,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
    private val graphs: ServiceUiPlaceGraph.Factory,
) : RouteEntry<ServiceUiPanelRoute>(ServiceUiPanelRoute::class, ServiceUiPanelRoute.serializer()) {
    override fun create(route: ServiceUiPanelRoute, context: ComponentContext, navigator: Navigator): NavComponent {
        val graph = context.retainedGraph(scopes, profile, name = "service_ui_panel") {
            graphs.create(ServiceUiPlace.of(route), it)
        }
        return PlaceUiComponent(graph.factory.create(context, navigator))
    }
}
