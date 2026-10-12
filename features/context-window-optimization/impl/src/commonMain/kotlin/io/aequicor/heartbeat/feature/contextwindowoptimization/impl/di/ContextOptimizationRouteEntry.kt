package io.aequicor.heartbeat.feature.contextwindowoptimization.impl.di

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
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationRoute
import io.aequicor.heartbeat.feature.contextwindowoptimization.impl.presentation.ContextSettingsComponent
import io.aequicor.heartbeat.feature.contextwindowoptimization.impl.ui.ContextSettingsUiComponent

/** Context settings, a profile route shown as a section of the settings window. */
@ContributesIntoSet(ProfileScope::class, binding = binding<ProfileRouteBinding>())
@Inject
internal class ContextOptimizationRouteEntry(
    private val factory: ContextSettingsComponent.Factory,
    private val scopes: ScopeFactory,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) : RouteEntry<ContextOptimizationRoute>(ContextOptimizationRoute::class, ContextOptimizationRoute.serializer()) {
    override fun create(
        route: ContextOptimizationRoute,
        context: ComponentContext,
        navigator: Navigator,
    ): NavComponent {
        val screen = context.retainedScope(scopes, profile, name = "context-optimization")
        return ContextSettingsUiComponent(factory.create(context, navigator, screen), route.isEmbedded)
    }
}
