package io.aequicor.heartbeat.feature.worktreemode.impl.di

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
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildsRoute
import io.aequicor.heartbeat.feature.worktreemode.impl.presentation.BuildCapacityComponent
import io.aequicor.heartbeat.feature.worktreemode.impl.ui.BuildCapacityUiComponent

/** Build queue settings, a profile route shown as a section of the settings window. */
@ContributesIntoSet(ProfileScope::class, binding = binding<ProfileRouteBinding>())
@Inject
internal class WorktreeBuildsRouteEntry(
    private val factory: BuildCapacityComponent.Factory,
    private val scopes: ScopeFactory,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) : RouteEntry<WorktreeBuildsRoute>(WorktreeBuildsRoute::class, WorktreeBuildsRoute.serializer()) {
    override fun create(route: WorktreeBuildsRoute, context: ComponentContext, navigator: Navigator): NavComponent {
        val screen = context.retainedScope(scopes, profile, name = "worktree-builds")
        return BuildCapacityUiComponent(factory.create(context, navigator, screen), route.isEmbedded)
    }
}
