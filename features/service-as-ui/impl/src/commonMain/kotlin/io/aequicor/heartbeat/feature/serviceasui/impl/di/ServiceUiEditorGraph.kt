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
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiSurfaceRoute
import io.aequicor.heartbeat.feature.serviceasui.impl.di.scope.ServiceUiEditorScope
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.component.EditorComponent
import io.aequicor.heartbeat.feature.serviceasui.impl.ui.EditorUiComponent

/** Graph of the editor of one surface, retained by its navigation entry under the profile. */
@GraphExtension(ServiceUiEditorScope::class)
interface ServiceUiEditorGraph {
    val factory: EditorComponent.Factory

    /** Creates the graph of an editor. */
    @ContributesTo(ProfileScope::class)
    @GraphExtension.Factory
    fun interface Factory {
        /** Binds the edited surface and the lifecycle owner of the navigation entry. */
        fun create(
            @Provides route: ServiceUiSurfaceRoute,
            @Provides @ForScope(ServiceUiEditorScope::class) scope: ScopeHandle,
        ): ServiceUiEditorGraph
    }
}

/** Details, editor and preview of one library surface. */
@ContributesIntoSet(ProfileScope::class, binding = binding<ProfileRouteBinding>())
@Inject
internal class ServiceUiSurfaceRouteEntry(
    private val scopes: ScopeFactory,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
    private val graphs: ServiceUiEditorGraph.Factory,
) : RouteEntry<ServiceUiSurfaceRoute>(ServiceUiSurfaceRoute::class, ServiceUiSurfaceRoute.serializer()) {
    override fun create(route: ServiceUiSurfaceRoute, context: ComponentContext, navigator: Navigator): NavComponent {
        val graph = context.retainedGraph(scopes, profile, name = "service_ui_surface") { graphs.create(route, it) }
        return EditorUiComponent(graph.factory.create(context, navigator))
    }
}
