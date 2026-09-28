package io.aequicor.heartbeat.feature.searchengine.impl.di

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
import io.aequicor.heartbeat.feature.searchengine.api.ProfileSettingsRoute
import io.aequicor.heartbeat.feature.searchengine.impl.presentation.ProfileSettingsComponent
import io.aequicor.heartbeat.feature.searchengine.impl.ui.ProfileSettingsUiComponent

/** Profile search settings, embedded in the settings window or opened on their own. */
@ContributesIntoSet(ProfileScope::class, binding = binding<ProfileRouteBinding>())
@Inject
internal class ProfileSettingsRouteEntry(
    private val factory: ProfileSettingsComponent.Factory,
    private val scopes: ScopeFactory,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) : RouteEntry<ProfileSettingsRoute>(ProfileSettingsRoute::class, ProfileSettingsRoute.serializer()) {
    override fun create(route: ProfileSettingsRoute, context: ComponentContext, navigator: Navigator): NavComponent {
        val screen = context.retainedScope(scopes, profile, name = "profile-settings")
        return ProfileSettingsUiComponent(factory.create(context, navigator, screen, route))
    }
}
