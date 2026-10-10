package io.aequicor.heartbeat.feature.harness.impl.di

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
import io.aequicor.heartbeat.feature.harness.api.HarnessRoute
import io.aequicor.heartbeat.feature.harness.impl.presentation.HarnessLibraryComponent
import io.aequicor.heartbeat.feature.harness.impl.ui.HarnessLibraryUiComponent

/** The harness library, reachable as a profile route from the settings window. */
@ContributesIntoSet(ProfileScope::class, binding = binding<ProfileRouteBinding>())
@Inject
internal class HarnessRouteEntry(
    private val factory: HarnessLibraryComponent.Factory,
    private val scopes: ScopeFactory,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) : RouteEntry<HarnessRoute>(HarnessRoute::class, HarnessRoute.serializer()) {
    override fun create(route: HarnessRoute, context: ComponentContext, navigator: Navigator): NavComponent {
        val screen = context.retainedScope(scopes, profile, name = "harness")
        return HarnessLibraryUiComponent(factory.create(context, navigator, screen), route.isEmbedded)
    }
}
