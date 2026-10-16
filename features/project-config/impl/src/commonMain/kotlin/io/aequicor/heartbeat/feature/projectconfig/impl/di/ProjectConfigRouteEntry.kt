package io.aequicor.heartbeat.feature.projectconfig.impl.di

import com.arkivanov.decompose.ComponentContext
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeFactory
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ext.retainedScope
import io.aequicor.heartbeat.core.navigation.NavComponent
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.core.navigation.ProfileRouteBinding
import io.aequicor.heartbeat.core.navigation.RouteEntry
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigRoute
import io.aequicor.heartbeat.feature.projectconfig.impl.presentation.ProjectConfigComponent
import io.aequicor.heartbeat.feature.projectconfig.impl.ui.ProjectConfigUiComponent

/** The configuration screen, reachable as a profile route from the settings window. */
@ContributesIntoSet(ProfileScope::class, binding = binding<ProfileRouteBinding>())
@Inject
internal class ProjectConfigRouteEntry(
    private val factory: ProjectConfigComponent.Factory,
    private val scopes: ScopeFactory,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) : RouteEntry<ProjectConfigRoute>(ProjectConfigRoute::class, ProjectConfigRoute.serializer()) {
    override fun create(route: ProjectConfigRoute, context: ComponentContext, navigator: Navigator): NavComponent {
        val screen = context.retainedScope(scopes, profile, name = "project-config")
        return ProjectConfigUiComponent(factory.create(context, navigator, screen), route.isEmbedded)
    }
}
