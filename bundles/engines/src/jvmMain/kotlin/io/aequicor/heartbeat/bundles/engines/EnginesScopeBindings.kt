package io.aequicor.heartbeat.bundles.engines

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import kotlinx.coroutines.CoroutineScope

/**
 * Coroutine scopes of the graphs of the bundle. `core:di:impl` provides them in the foundation graph; the bundle graphs
 * do not compile that module, so they derive the scopes from the handles the foundation passes in.
 */
@ContributesTo(AppScope::class)
@BindingContainer
internal object EnginesAppScopeBindings {
    /** App-lifetime coroutines. */
    @Provides
    @ForScope(AppScope::class)
    fun appCoroutineScope(
        @ForScope(AppScope::class) scope: ScopeHandle,
    ): CoroutineScope = scope.coroutineScope
}

/** Profile-level counterpart of [EnginesAppScopeBindings]. */
@ContributesTo(ProfileScope::class)
@BindingContainer
internal object EnginesProfileScopeBindings {
    /** Profile-lifetime coroutines; cancelled together with the profile. */
    @Provides
    @ForScope(ProfileScope::class)
    fun profileCoroutineScope(
        @ForScope(ProfileScope::class) scope: ScopeHandle,
    ): CoroutineScope = scope.coroutineScope
}
