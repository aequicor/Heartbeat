package io.aequicor.heartbeat.core.navigation

import dev.zacsweers.metro.AppScope
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope

// Qualified element types of the two navigation registries, for `@ContributesIntoSet(binding = binding<…>())`.
// A qualifier on the contributed class itself is ignored by @ContributesIntoSet; an unqualified entry fails the
// creation of the root factory (see core:navigation:impl).

/** A [RouteEntry] of the guest registry (available before sign-in and inside profiles). */
public typealias AppRouteBinding =
    @ForScope(AppScope::class)
    RouteEntry<*>

/** A [RouteEntry] of the profile registry. */
public typealias ProfileRouteBinding =
    @ForScope(ProfileScope::class)
    RouteEntry<*>

/** A [DeepLinkEntry] of the guest registry. */
public typealias AppDeepLinkBinding =
    @ForScope(AppScope::class)
    DeepLinkEntry

/** A [DeepLinkEntry] of the profile registry. */
public typealias ProfileDeepLinkBinding =
    @ForScope(ProfileScope::class)
    DeepLinkEntry
