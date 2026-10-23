package io.aequicor.heartbeat.bundles.shell

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Includes
import dev.zacsweers.metro.Provides
import io.aequicor.heartbeat.core.bundles.FoundationAppBindings
import io.aequicor.heartbeat.core.di.AppStartup
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.navigation.DeepLinkEntry
import io.aequicor.heartbeat.core.navigation.RouteEntry
import io.aequicor.heartbeat.core.profilefacade.ProfileGraph

/**
 * What the `shell` bundle publishes to the foundation. The routes and deep links go out as lists: bundles are matched
 * by the type of an accessor, and a set of routes would collide with the profile routes of the `web` bundle.
 */
public interface ShellAppExports {
    /** Toggles of the bundle's features; the foundation merges them into the application's set. */
    public val featureToggles: Set<FeatureToggle<*>>

    /** Guest-tree routes (settings, welcome, flags panel, logs); the foundation merges them into the app routes. */
    public val appRoutes: List<RouteEntry<*>>

    /** Guest-tree deep links (`heartbeat://settings[/<section>]`); the foundation merges them into the app ones. */
    public val appDeepLinks: List<DeepLinkEntry>

    /** Work that runs for the whole launch, such as the app update check; the foundation starts it. */
    public val appStartups: Set<AppStartup>
}

/** Lists of the exports, taken from the sets the member features contribute to. */
@ContributesTo(AppScope::class)
@BindingContainer
internal object ShellExportBindings {
    @Provides
    fun appRoutes(
        @ForScope(AppScope::class) routes: Set<RouteEntry<*>>,
    ): List<RouteEntry<*>> = routes.toList()

    @Provides
    fun appDeepLinks(
        @ForScope(AppScope::class) links: Set<DeepLinkEntry>,
    ): List<DeepLinkEntry> = links.toList()
}

/**
 * App-level graph of the `shell` bundle: the contributions of its member features, compiled statically. It has no
 * profile graph: all its features live above profiles.
 */
@DependencyGraph(AppScope::class, excludes = [ProfileGraph.Factory::class])
internal interface ShellAppGraph : ShellAppExports {
    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Includes foundation: FoundationAppBindings,
        ): ShellAppGraph
    }
}
