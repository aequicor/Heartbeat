package io.aequicor.heartbeat.feature.autocontinue.impl.di

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
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueRoute
import io.aequicor.heartbeat.feature.autocontinue.impl.presentation.AutoContinueSettingsComponent
import io.aequicor.heartbeat.feature.autocontinue.impl.ui.AutoContinueSettingsUiComponent

/** Auto-continue settings, a profile route shown as a section of the settings window. */
@ContributesIntoSet(ProfileScope::class, binding = binding<ProfileRouteBinding>())
@Inject
internal class AutoContinueRouteEntry(
    private val factory: AutoContinueSettingsComponent.Factory,
    private val scopes: ScopeFactory,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) : RouteEntry<AutoContinueRoute>(AutoContinueRoute::class, AutoContinueRoute.serializer()) {
    override fun create(route: AutoContinueRoute, context: ComponentContext, navigator: Navigator): NavComponent {
        val screen = context.retainedScope(scopes, profile, name = "auto-continue")
        return AutoContinueSettingsUiComponent(factory.create(context, navigator, screen), route.isEmbedded)
    }
}
