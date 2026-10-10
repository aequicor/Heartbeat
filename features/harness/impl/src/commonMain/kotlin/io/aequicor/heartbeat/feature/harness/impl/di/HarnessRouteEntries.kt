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
import io.aequicor.heartbeat.feature.harness.api.HarnessDetailRoute
import io.aequicor.heartbeat.feature.harness.api.HarnessItemRoute
import io.aequicor.heartbeat.feature.harness.api.HarnessRoute
import io.aequicor.heartbeat.feature.harness.api.HarnessToolsRoute
import io.aequicor.heartbeat.feature.harness.impl.presentation.HarnessDetailComponent
import io.aequicor.heartbeat.feature.harness.impl.presentation.HarnessItemComponent
import io.aequicor.heartbeat.feature.harness.impl.presentation.HarnessLibraryComponent
import io.aequicor.heartbeat.feature.harness.impl.presentation.HarnessToolsComponent
import io.aequicor.heartbeat.feature.harness.impl.ui.HarnessDetailUiComponent
import io.aequicor.heartbeat.feature.harness.impl.ui.HarnessItemUiComponent
import io.aequicor.heartbeat.feature.harness.impl.ui.HarnessLibraryUiComponent
import io.aequicor.heartbeat.feature.harness.impl.ui.HarnessToolsUiComponent

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

/** One harness of the library. */
@ContributesIntoSet(ProfileScope::class, binding = binding<ProfileRouteBinding>())
@Inject
internal class HarnessDetailRouteEntry(
    private val factory: HarnessDetailComponent.Factory,
    private val scopes: ScopeFactory,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) : RouteEntry<HarnessDetailRoute>(HarnessDetailRoute::class, HarnessDetailRoute.serializer()) {
    override fun create(route: HarnessDetailRoute, context: ComponentContext, navigator: Navigator): NavComponent {
        val screen = context.retainedScope(scopes, profile, name = "harness-detail")
        return HarnessDetailUiComponent(factory.create(context, navigator, screen, route.harness))
    }
}

/** Editor of one harness item. */
@ContributesIntoSet(ProfileScope::class, binding = binding<ProfileRouteBinding>())
@Inject
internal class HarnessItemRouteEntry(
    private val factory: HarnessItemComponent.Factory,
    private val scopes: ScopeFactory,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) : RouteEntry<HarnessItemRoute>(HarnessItemRoute::class, HarnessItemRoute.serializer()) {
    override fun create(route: HarnessItemRoute, context: ComponentContext, navigator: Navigator): NavComponent {
        val screen = context.retainedScope(scopes, profile, name = "harness-item")
        return HarnessItemUiComponent(factory.create(context, navigator, screen, route.harness, route.item))
    }
}

/** Tool policy of one harness. */
@ContributesIntoSet(ProfileScope::class, binding = binding<ProfileRouteBinding>())
@Inject
internal class HarnessToolsRouteEntry(
    private val factory: HarnessToolsComponent.Factory,
    private val scopes: ScopeFactory,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) : RouteEntry<HarnessToolsRoute>(HarnessToolsRoute::class, HarnessToolsRoute.serializer()) {
    override fun create(route: HarnessToolsRoute, context: ComponentContext, navigator: Navigator): NavComponent {
        val screen = context.retainedScope(scopes, profile, name = "harness-tools")
        return HarnessToolsUiComponent(factory.create(context, navigator, screen, route.harness))
    }
}
