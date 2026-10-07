package io.aequicor.heartbeat.feature.harness.impl.di

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessRequestOrigins
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessTimerSlots
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessWakeOperations
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessWakePort
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessWakeQuotas

/** Quotas and uncertain submissions are shared by every script in this profile. */
@ContributesTo(ProfileScope::class)
@BindingContainer
public object HarnessSchedulerBindings {
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun timers(): HarnessTimerSlots = HarnessTimerSlots()

    @Provides
    @SingleIn(ProfileScope::class)
    internal fun quotas(): HarnessWakeQuotas = HarnessWakeQuotas()

    @Provides
    @SingleIn(ProfileScope::class)
    internal fun operations(
        @ForScope(ProfileScope::class) scope: ScopeHandle,
        port: HarnessWakePort,
        quotas: HarnessWakeQuotas,
        origins: HarnessRequestOrigins,
    ): HarnessWakeOperations = HarnessWakeOperations(scope.coroutineScope, port, quotas, origins)
}
