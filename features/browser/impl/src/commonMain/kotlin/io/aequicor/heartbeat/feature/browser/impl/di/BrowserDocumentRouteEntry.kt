package io.aequicor.heartbeat.feature.browser.impl.di

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
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentRoute
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserDocumentNatives
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserDocumentRegistry
import io.aequicor.heartbeat.feature.browser.impl.presentation.component.BrowserDocumentComponent
import io.aequicor.heartbeat.feature.browser.impl.ui.BrowserDocumentUiComponent

/** Draws an open document of the profile wherever its opener embeds the route. */
@ContributesIntoSet(ProfileScope::class, binding = binding<ProfileRouteBinding>())
@Inject
internal class BrowserDocumentRouteEntry(
    private val scopes: ScopeFactory,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
    private val registry: BrowserDocumentRegistry,
    private val natives: BrowserDocumentNatives = BrowserDocumentNatives.None,
) : RouteEntry<BrowserDocumentRoute>(BrowserDocumentRoute::class, BrowserDocumentRoute.serializer()) {
    override fun create(route: BrowserDocumentRoute, context: ComponentContext, navigator: Navigator): NavComponent {
        val scope = context.retainedScope(scopes, profile, name = "browser-document")
        val component = BrowserDocumentComponent(
            context,
            route.id,
            registry,
            scope.coroutineScope,
            natives,
            route.presentation,
        )
        return BrowserDocumentUiComponent(component)
    }
}
