package io.aequicor.heartbeat.feature.adaptivesubagents.impl.di

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
import io.aequicor.heartbeat.feature.adaptivesubagents.api.AdaptiveSubagentsRoute
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.presentation.SubagentsComponent
import io.aequicor.heartbeat.feature.adaptivesubagents.impl.ui.SubagentsUiComponent

/** The adaptive subagents section, reachable as a profile route from the settings window. */
@ContributesIntoSet(ProfileScope::class, binding = binding<ProfileRouteBinding>())
@Inject
internal class SubagentsRouteEntry(
    private val factory: SubagentsComponent.Factory,
    private val scopes: ScopeFactory,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) : RouteEntry<AdaptiveSubagentsRoute>(AdaptiveSubagentsRoute::class, AdaptiveSubagentsRoute.serializer()) {
    override fun create(route: AdaptiveSubagentsRoute, context: ComponentContext, navigator: Navigator): NavComponent {
        val screen = context.retainedScope(scopes, profile, name = "adaptive-subagents")
        return SubagentsUiComponent(factory.create(context, navigator, screen), route.isEmbedded)
    }
}
