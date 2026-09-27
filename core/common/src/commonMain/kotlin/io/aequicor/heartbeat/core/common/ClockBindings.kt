package io.aequicor.heartbeat.core.common

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import kotlin.time.Clock

// Binding containers are public: the graph that includes them is generated in :platform-main:di-bundle.

/** Wall clock of the app. Always injected — never call `Clock.System` directly, so tests can control time. */
@ContributesTo(AppScope::class)
@BindingContainer
public object ClockBindings {

    /** The system clock. */
    @Provides
    public fun clock(): Clock = Clock.System
}
