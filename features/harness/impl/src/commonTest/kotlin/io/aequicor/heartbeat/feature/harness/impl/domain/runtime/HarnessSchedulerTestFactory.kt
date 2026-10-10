package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.harness.impl.data.runtime.RuntimeHarnessTimerInvoker
import io.aequicor.heartbeat.feature.harness.impl.data.services.StagedHarnessSchedulerFactory
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessSchedulerHost
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptEvent
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptSchedulerFactory
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptWake
import io.aequicor.heartbeat.feature.scheduler.api.EventKey
import io.aequicor.heartbeat.feature.scheduler.api.WakeId
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.time.Clock

/** Real timers and context lifecycle; tests that exercise external operations supply their host explicitly. */
internal fun schedulerTestFactory(
    origins: HarnessCallOrigins,
    dispatcher: CoroutineDispatcher,
    host: HarnessSchedulerHost = UnusedSchedulerHost,
    runtime: () -> HarnessRuntime,
): HarnessScriptSchedulerFactory = StagedHarnessSchedulerFactory(
    host,
    origins,
    HarnessTimerSlots(),
    RuntimeHarnessTimerInvoker(lazy(runtime)),
    object : DispatcherProvider {
        override val main = dispatcher
        override val default = dispatcher
        override val io = dispatcher
    },
    Clock.System,
)

internal object UnusedSchedulerHost : HarnessSchedulerHost {
    override suspend fun wake(owner: HarnessInstanceTarget, request: HarnessScriptWake): WakeId =
        error("This fixture does not perform external scheduler operations")
    override suspend fun cancel(owner: HarnessInstanceTarget, id: WakeId, origin: HarnessCallOrigin): Boolean =
        error("This fixture does not perform external scheduler operations")
    override suspend fun publish(owner: HarnessInstanceTarget, event: HarnessScriptEvent): EventKey =
        error("This fixture does not perform external scheduler operations")
}
