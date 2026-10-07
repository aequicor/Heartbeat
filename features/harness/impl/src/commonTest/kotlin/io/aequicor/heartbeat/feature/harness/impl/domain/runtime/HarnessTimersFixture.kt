package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRegistration
import io.aequicor.heartbeat.feature.harness.impl.data.runtime.RuntimeHarnessTimerInvoker
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.Instant

internal class HarnessTimersFixture(scope: TestScope, callOrigins: HarnessCallOrigins? = null) {
    private val dispatcher = StandardTestDispatcher(scope.testScheduler)
    val runtime = HarnessRuntimeFixture(scope.backgroundScope, dispatcher, callOrigins)
    private val invoker = RuntimeHarnessTimerInvoker(lazy { runtime.runtime })
    private val slots = HarnessTimerSlots()
    var origin = HarnessCallOrigin()
    val timers = mutableListOf<HarnessTimers>()
    val targets = mutableListOf<HarnessTimerTarget>()
    private val origins = callOrigins ?: object : HarnessCallOrigins {
        override fun current(): HarnessCallOrigin = origin
        override fun context(origin: HarnessCallOrigin): CoroutineContext = HarnessOriginContext(origin)
    }
    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(scope.testScheduler.currentTime)
    }

    init {
        runtime.customContext = { request, access ->
            val target = HarnessTimerTarget(request, access).also(targets::add)
            val services = HarnessTimers(target, slots, invoker, origins, dispatcher, clock).also(timers::add)
            object : HarnessRuntimeContext {
                override val evaluation = HarnessEvaluationContext.Workflow(WorkflowRegistration {})
                override fun tryCommitPublication(): Boolean = services.tryCommitPublication()
                override fun close() = services.close()
            }
        }
    }
}
