package io.aequicor.heartbeat.feature.harness.impl.data.services

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.harness.api.HarnessActivationRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigins
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceTarget
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessTimerInvoker
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessTimerSlots
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessTimers
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessSchedulerHost
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptScheduler
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptSchedulerFactory
import kotlin.time.Clock

@ContributesBinding(ProfileScope::class)
@Inject
internal class StagedHarnessSchedulerFactory(
    private val host: HarnessSchedulerHost,
    private val origins: HarnessCallOrigins,
    private val slots: HarnessTimerSlots,
    private val invoker: HarnessTimerInvoker,
    private val dispatchers: DispatcherProvider,
    private val clock: Clock,
) : HarnessScriptSchedulerFactory {
    override fun create(request: HarnessActivationRequest, access: HarnessInstanceAccess): HarnessScriptScheduler {
        val owner = HarnessInstanceTarget(request, access)
        val timers = HarnessTimers(owner, slots, invoker, origins, dispatchers.default, clock)
        return HarnessScriptScheduler(owner, timers, host, origins)
    }
}
