package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.harness.api.HarnessActivationRequest
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessItem
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRegistration
import io.aequicor.heartbeat.feature.harness.impl.domain.script.CompiledHarnessCode
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationResult
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationContext
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationResult
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessScriptHost
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HarnessRuntimeCapacityTest {
    @Test
    fun `fifth activation is rejected before compilation until four timed out callbacks really exit`() = runTest {
        val executor = Executors.newFixedThreadPool(4)
        val io = CapacityTestDispatcher(executor)
        val control = StandardTestDispatcher(testScheduler)
        val owner = SupervisorJob(coroutineContext[Job])
        val fixture = CapacityRuntimeFixture(CoroutineScope(owner + control), control, io)
        val release = CountDownLatch(1)
        try {
            val instances = fixture.requests.take(4).map { request -> activate(fixture, io, request) }
            assertEquals(4, fixture.compilations.get())
            val entered = CountDownLatch(4)
            val drained = CountDownLatch(4)
            io.completions.set(drained)
            val waiters = instances.map { instance ->
                async {
                    fixture.runtime.invoke(instance, HarnessInvocationBudget.Instructions) {
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                    }
                }
            }
            runCurrent()
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertTrue(fixture.lane.isExhausted)
            val fifth = fixture.requests.last()
            assertFalse(fixture.runtime.activate(fifth))
            assertEquals(4, fixture.compilations.get())
            advanceTimeBy(500)
            runCurrent()
            waiters.forEach { assertEquals(HarnessInvocationResult.TimedOut, it.await()) }
            assertTrue(fixture.lane.isExhausted)
            instances.forEach { assertEquals(1, it.calls.activeCount) }
            assertFalse(fixture.runtime.activate(fifth))
            assertEquals(4, fixture.compilations.get())
            release.countDown()
            // This latch observes the outer executor task exit, after the lane's occupancy finally block.
            assertTrue(drained.await(10, TimeUnit.SECONDS))
            instances.forEach { it.calls.awaitIdle() }
            assertFalse(fixture.lane.isExhausted)
            assertTrue(activate(fixture, io, fifth).isActive)
            assertEquals(5, fixture.compilations.get())
        } finally {
            release.countDown()
            owner.cancelAndJoin()
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private suspend fun TestScope.activate(
        fixture: CapacityRuntimeFixture,
        io: CapacityTestDispatcher,
        request: HarnessActivationRequest,
    ): HarnessInstance {
        val evaluated = CountDownLatch(1)
        io.completions.set(evaluated)
        val activation = async { fixture.runtime.activate(request) }
        runCurrent()
        // Wait for actual IO completion without letting virtual time jump to the evaluation deadline.
        assertTrue(evaluated.await(10, TimeUnit.SECONDS))
        runCurrent()
        assertTrue(activation.isCompleted)
        assertTrue(activation.await())
        return checkNotNull(fixture.runtime.instance(request.harness.id, request.item.id))
    }
}

private class CapacityRuntimeFixture(scope: CoroutineScope, control: CoroutineDispatcher, io: CoroutineDispatcher) {
    val requests: List<HarnessActivationRequest> = capacityRequests()
    val compilations = AtomicInteger()
    val lane = HarnessExecutionLane(io)
    private val host = object : HarnessScriptHost {
        override val isAvailable = true
        override suspend fun compile(request: HarnessCompilationRequest): HarnessCompilationResult {
            compilations.incrementAndGet()
            return HarnessCompilationResult.Success(RuntimeTestCode(), emptyList())
        }
        override suspend fun evaluate(
            code: CompiledHarnessCode,
            context: HarnessEvaluationContext,
        ): HarnessEvaluationResult = HarnessEvaluationResult.Success
        override suspend fun removeCached(harness: HarnessId, item: ItemId) = Unit
    }
    private val admission = object : HarnessRuntimeAdmission {
        override fun canPublish(request: HarnessActivationRequest): Boolean = request in requests
        override fun canInvoke(request: HarnessActivationRequest): Boolean = request in requests
    }
    private val contexts = HarnessRuntimeContextFactory { _, _ ->
        object : HarnessRuntimeContext {
            override val evaluation = HarnessEvaluationContext.Workflow(WorkflowRegistration {})
            override fun close() = Unit
        }
    }
    private val dispatchers = object : DispatcherProvider {
        override val main = control
        override val default = control
        override val io = io
    }
    val runtime = HarnessRuntime(
        host,
        lane,
        HarnessRuntimeEnvironment(scope, dispatchers, admission, contexts) {},
    )
}

private fun capacityRequests(): List<HarnessActivationRequest> {
    val template = runtimeRequest(1)
    val items = List(5) { index ->
        (template.item as HarnessItem.Workflow).copy(id = ItemId("code$index"), name = ItemName("code$index"))
    }
    val harness = template.harness.copy(items = items)
    return items.map { HarnessActivationRequest(harness, it, template.generation) }
}

/** Captures each dispatch's completion latch so earlier evaluation tasks cannot satisfy a later drain barrier. */
private class CapacityTestDispatcher(private val executor: ExecutorService) : CoroutineDispatcher() {
    val completions = AtomicReference<CountDownLatch?>()
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val completion = completions.get()
        executor.execute {
            try {
                block.run()
            } finally {
                completion?.countDown()
            }
        }
    }
}
