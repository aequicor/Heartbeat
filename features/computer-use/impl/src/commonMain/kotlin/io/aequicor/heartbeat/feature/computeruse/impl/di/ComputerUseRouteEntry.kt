package io.aequicor.heartbeat.feature.computeruse.impl.di

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
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseRoute
import io.aequicor.heartbeat.feature.computeruse.impl.presentation.ComputerUseComponent
import io.aequicor.heartbeat.feature.computeruse.impl.ui.ComputerUseUiComponent

/** Computer use settings, reachable as a profile route from the settings window and the studio. */
@ContributesIntoSet(ProfileScope::class, binding = binding<ProfileRouteBinding>())
@Inject
internal class ComputerUseRouteEntry(
    private val factory: ComputerUseComponent.Factory,
    private val scopes: ScopeFactory,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) : RouteEntry<ComputerUseRoute>(ComputerUseRoute::class, ComputerUseRoute.serializer()) {
    override fun create(route: ComputerUseRoute, context: ComponentContext, navigator: Navigator): NavComponent {
        val screen = context.retainedScope(scopes, profile, name = "computer-use")
        return ComputerUseUiComponent(factory.create(context, navigator, screen), route.isEmbedded)
    }
}
