package io.aequicor.heartbeat.core.di.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Multibinds
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.SharedFactory
import kotlinx.coroutines.CoroutineScope

// Binding containers must be public: the graph that includes them is generated in another module
// (:platform-main:di-bundle), and `generateContributionProviders` does not cover containers.
// core:di:impl is visible only to the bundle, so this does not widen the API for features.

/** App-level scope bindings. */
@ContributesTo(AppScope::class)
@BindingContainer
public object AppScopeBindings {

    /** Root of the scope tree. */
    @Provides
    @SingleIn(AppScope::class)
    @ForScope(AppScope::class)
    public fun appScope(dispatchers: DispatcherProvider): ScopeHandle = createRootScope(dispatchers)

    /** App-lifetime coroutines (state machines, background sync). */
    @Provides
    @ForScope(AppScope::class)
    public fun appCoroutineScope(
        @ForScope(AppScope::class) scope: ScopeHandle,
    ): CoroutineScope = scope.coroutineScope
}

/** Profile-level scope bindings. */
@ContributesTo(ProfileScope::class)
@BindingContainer
public interface ProfileScopeBindings {

    /** Shared object factories; empty until a feature contributes one. */
    @Multibinds(allowEmpty = true)
    public fun sharedFactories(): Map<String, SharedFactory<*>>

    /** Static providers of the container. */
    public companion object {
        /** Profile-lifetime coroutines; cancelled on sign-out or profile switch. */
        @Provides
        @ForScope(ProfileScope::class)
        public fun profileCoroutineScope(
            @ForScope(ProfileScope::class) scope: ScopeHandle,
        ): CoroutineScope = scope.coroutineScope
    }
}
