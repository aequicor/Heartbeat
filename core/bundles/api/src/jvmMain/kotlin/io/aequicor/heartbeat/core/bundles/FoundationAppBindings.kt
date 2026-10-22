package io.aequicor.heartbeat.core.bundles

import dev.zacsweers.metro.AppScope
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.network.release.VerifiedArtifactCache

/**
 * Stateful app-level bindings the foundation graph hands to every bundle graph (`@Includes`). Stateless
 * contributions of non-impl libraries (for example the default `DispatcherProvider`) are not listed: each bundle
 * graph compiles its own copy, and a second binding of the same type would clash.
 */
public interface FoundationAppBindings {
    /** Toggles of the whole application (bundle toggles are collected into it by the foundation). */
    public val featureToggles: FeatureToggles

    /** Verified downloads shared by every bundle. */
    public val verifiedArtifacts: VerifiedArtifactCache

    /** Scope of the application: coroutines and cleanup of services that live as long as the app. */
    @ForScope(AppScope::class)
    public val appScope: ScopeHandle
}
