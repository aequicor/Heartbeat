package io.aequicor.heartbeat.core.navigation.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Multibinds
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.navigation.DeepLinkConfig
import io.aequicor.heartbeat.core.navigation.DeepLinkEntry
import io.aequicor.heartbeat.core.navigation.RootNavHostFactory
import io.aequicor.heartbeat.core.navigation.RouteEntry

// Contributed interfaces must be public: the graph that implements them is generated in :platform-main:di-bundle.
// core:navigation:impl is visible only to the bundle, so this does not widen the API for features.

/** Guest registry (before sign-in): routes and deep links contributed with `AppRouteBinding` / `AppDeepLinkBinding`. */
@ContributesTo(AppScope::class)
public interface AppNavigationRegistry {
    /** App-level routes; empty until a feature contributes one. */
    @Multibinds(allowEmpty = true)
    @ForScope(AppScope::class)
    public fun appRoutes(): Set<RouteEntry<*>>

    /** App-level deep links. */
    @Multibinds(allowEmpty = true)
    @ForScope(AppScope::class)
    public fun appDeepLinks(): Set<DeepLinkEntry>

    /** Must stay empty: catches contributions that forgot the `@ForScope` qualifier. */
    @Multibinds(allowEmpty = true)
    public fun unqualifiedRoutes(): Set<RouteEntry<*>>

    /** Must stay empty: catches contributions that forgot the `@ForScope` qualifier. */
    @Multibinds(allowEmpty = true)
    public fun unqualifiedDeepLinks(): Set<DeepLinkEntry>
}

/** Profile registry: routes and deep links contributed with `ProfileRouteBinding` / `ProfileDeepLinkBinding`. */
@ContributesTo(ProfileScope::class)
public interface ProfileNavigationRegistry {
    /** Profile-level routes. */
    @Multibinds(allowEmpty = true)
    @ForScope(ProfileScope::class)
    public fun profileRoutes(): Set<RouteEntry<*>>

    /** Profile-level deep links. */
    @Multibinds(allowEmpty = true)
    @ForScope(ProfileScope::class)
    public fun profileDeepLinks(): Set<DeepLinkEntry>
}

/** Root factory of the guest tree: app routes only. */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding = binding<GuestRootFactoryBinding>())
@Inject
internal class GuestRootNavHostFactory(
    @ForScope(AppScope::class) routes: Set<RouteEntry<*>>,
    @ForScope(AppScope::class) deepLinks: Set<DeepLinkEntry>,
    unqualifiedRoutes: Set<RouteEntry<*>>,
    unqualifiedDeepLinks: Set<DeepLinkEntry>,
    config: DeepLinkConfig,
) : RootNavHostFactory by RootNavHostFactoryImpl(
        routes = registry(routes, unqualifiedRoutes, unqualifiedDeepLinks),
        deepLinks = DeepLinkRouter(config, deepLinks),
    )

/** Root factory of the profile tree: profile and app routes. */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class, binding = binding<ProfileRootFactoryBinding>())
@Inject
internal class ProfileRootNavHostFactory(
    @ForScope(AppScope::class) appRoutes: Set<RouteEntry<*>>,
    @ForScope(ProfileScope::class) profileRoutes: Set<RouteEntry<*>>,
    @ForScope(AppScope::class) appDeepLinks: Set<DeepLinkEntry>,
    @ForScope(ProfileScope::class) profileDeepLinks: Set<DeepLinkEntry>,
    unqualifiedRoutes: Set<RouteEntry<*>>,
    unqualifiedDeepLinks: Set<DeepLinkEntry>,
    config: DeepLinkConfig,
) : RootNavHostFactory by RootNavHostFactoryImpl(
        routes = registry(appRoutes + profileRoutes, unqualifiedRoutes, unqualifiedDeepLinks),
        deepLinks = DeepLinkRouter(config, appDeepLinks + profileDeepLinks),
    )

private typealias GuestRootFactoryBinding =
    @ForScope(AppScope::class)
    RootNavHostFactory

private typealias ProfileRootFactoryBinding =
    @ForScope(ProfileScope::class)
    RootNavHostFactory

/**
 * An entry contributed without `binding = binding<ProfileRouteBinding>()` (or another *Binding alias) lands in
 * the unqualified set and would be silently missing from both registries — fail fast instead.
 */
private fun registry(
    routes: Set<RouteEntry<*>>,
    unqualifiedRoutes: Set<RouteEntry<*>>,
    unqualifiedDeepLinks: Set<DeepLinkEntry>,
): RouteRegistry {
    require(unqualifiedRoutes.isEmpty() && unqualifiedDeepLinks.isEmpty()) {
        "navigation entries contributed without @ForScope qualifier: " +
            (unqualifiedRoutes.map { it::class } + unqualifiedDeepLinks.map { it::class }).joinToString()
    }
    return RouteRegistry(routes)
}

/** Default origins: `heartbeat://` only. The app overrides with a higher priority. */
@ContributesBinding(AppScope::class, priority = Int.MIN_VALUE)
@Inject
internal class DefaultDeepLinkConfig : DeepLinkConfig {
    override val schemes: Set<String> = setOf("heartbeat")
    override val webHosts: Set<String> = emptySet()
}
