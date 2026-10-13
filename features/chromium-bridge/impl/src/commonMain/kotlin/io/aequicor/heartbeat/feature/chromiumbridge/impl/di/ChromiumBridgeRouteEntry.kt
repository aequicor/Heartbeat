package io.aequicor.heartbeat.feature.chromiumbridge.impl.di

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
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBridgeRoute
import io.aequicor.heartbeat.feature.chromiumbridge.impl.presentation.ChromiumBridgeComponent
import io.aequicor.heartbeat.feature.chromiumbridge.impl.ui.ChromiumBridgeUiComponent

/** Chromium browser settings, reachable as a profile route from the settings window. */
@ContributesIntoSet(ProfileScope::class, binding = binding<ProfileRouteBinding>())
@Inject
internal class ChromiumBridgeRouteEntry(
    private val factory: ChromiumBridgeComponent.Factory,
    private val scopes: ScopeFactory,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) : RouteEntry<ChromiumBridgeRoute>(ChromiumBridgeRoute::class, ChromiumBridgeRoute.serializer()) {
    override fun create(route: ChromiumBridgeRoute, context: ComponentContext, navigator: Navigator): NavComponent {
        val screen = context.retainedScope(scopes, profile, name = "chromium-bridge")
        return ChromiumBridgeUiComponent(factory.create(context, navigator, screen), route.isEmbedded)
    }
}
