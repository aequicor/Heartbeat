package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.navigation.RootNavHostFactory
import io.aequicor.heartbeat.core.profilefacade.ProfileSessions

/**
 * Application-wide dependency graph (AppScope) as seen by the platform entry points.
 *
 * Deliberately not annotated with `@DependencyGraph`: Metro requires the final graph to live in a
 * platform source set when contributions come from platform source sets (`androidMain`, `jvmMain`, `iosMain`).
 * Each platform declares `<Platform>HeartbeatGraph : HeartbeatGraph` with the annotation.
 */
interface HeartbeatGraph {
    /** Dispatchers for the entry points (e.g. creating the root component on the main thread). */
    val dispatchers: DispatcherProvider

    /** Host OS: the entry point chooses the UI kit by it. */
    val platformInfo: PlatformInfo

    /** Toggle values: the platform entry chooses start routes by them. */
    val featureToggles: FeatureToggles

    /** Profile sessions: the platform root restores the active profile on cold start and renders by it. */
    val profileSessions: ProfileSessions

    /** Root of the guest navigation tree (before sign-in): only routes registered with `@ForScope(AppScope::class)`. */
    @ForScope(AppScope::class)
    val guestNavigation: RootNavHostFactory
}

/**
 * Accessor of the profile navigation tree: `(session.graph as ProfileNavigation).navigation` — the root
 * with profile and app routes. Recreated with the profile graph on sign-in / profile switch.
 */
@ContributesTo(ProfileScope::class)
interface ProfileNavigation {
    /** Root factory of the profile tree. */
    @ForScope(ProfileScope::class)
    val navigation: RootNavHostFactory
}
