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
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiCardRoute
import io.aequicor.heartbeat.feature.serviceasui.impl.di.scope.ServiceUiCardScope
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.component.CardComponent
import io.aequicor.heartbeat.feature.serviceasui.impl.ui.CardUiComponent

/** Graph of one card shown in a response, retained by its navigation entry under the profile. */
@GraphExtension(ServiceUiCardScope::class)
interface ServiceUiCardGraph {
    val factory: CardComponent.Factory

    /** Creates the graph of a card. */
    @ContributesTo(ProfileScope::class)
    @GraphExtension.Factory
    fun interface Factory {
        /** Binds the shown card and the lifecycle owner of the navigation entry. */
        fun create(
            @Provides route: ServiceUiCardRoute,
            @Provides @ForScope(ServiceUiCardScope::class) scope: ScopeHandle,
        ): ServiceUiCardGraph
    }
}

/** A card the agent showed in a response. */
@ContributesIntoSet(ProfileScope::class, binding = binding<ProfileRouteBinding>())
@Inject
internal class ServiceUiCardRouteEntry(
    private val scopes: ScopeFactory,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
    private val graphs: ServiceUiCardGraph.Factory,
) : RouteEntry<ServiceUiCardRoute>(ServiceUiCardRoute::class, ServiceUiCardRoute.serializer()) {
    override fun create(route: ServiceUiCardRoute, context: ComponentContext, navigator: Navigator): NavComponent {
        val graph = context.retainedGraph(scopes, profile, name = "service_ui_card") { graphs.create(route, it) }
        return CardUiComponent(graph.factory.create(context, navigator))
    }
}
