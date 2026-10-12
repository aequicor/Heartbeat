package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.harness.api.HarnessActivationRequest
import io.aequicor.heartbeat.feature.harness.api.HarnessEffect
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import io.aequicor.heartbeat.feature.harness.api.HarnessItem
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
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
import kotlinx.coroutines.launch
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
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.TimeSource

@OptIn(ExperimentalCoroutinesApi::class)
class HarnessRuntimeCapacityTest {
    @Test
    fun `fifth activation waits without compiling until four timed out callbacks really exit`() = runTest {
        val executor = Executors.newFixedThreadPool(4)
        val io = CapacityTestDispatcher(executor)
        val control = StandardTestDispatcher(testScheduler)
        val owner = SupervisorJob(coroutineContext[Job])
        val fixture = CapacityRuntimeFixture(CoroutineScope(owner + control), control, io, testScheduler.timeSource)
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
            val waiting = async { fixture.runtime.activate(fifth) }
            runCurrent()
            assertFalse(waiting.isCompleted)
            assertEquals(4, fixture.compilations.get())
            advanceTimeBy(500)
            runCurrent()
            waiters.forEach { assertEquals(HarnessInvocationResult.TimedOut, it.await()) }
            assertTrue(fixture.lane.isExhausted)
            instances.forEach { assertEquals(1, it.calls.activeCount) }
            assertFalse(waiting.isCompleted)
            assertEquals(4, fixture.compilations.get())
            release.countDown()
            // This latch observes the outer executor task exit, after the lane's occupancy finally block.
            assertTrue(drained.await(10, TimeUnit.SECONDS))
            assertFalse(fixture.lane.isExhausted)
            val evaluated = CountDownLatch(1)
            io.completions.set(evaluated)
            runCurrent()
            assertTrue(evaluated.await(10, TimeUnit.SECONDS))
            runCurrent()
            assertTrue(waiting.await())
            assertEquals(5, fixture.compilations.get())
        } finally {
            release.countDown()
            owner.cancelAndJoin()
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `activation waiting for lane capacity fails once its request is fenced`() = runTest {
        val executor = Executors.newFixedThreadPool(4)
        val io = CapacityTestDispatcher(executor)
        val control = StandardTestDispatcher(testScheduler)
        val owner = SupervisorJob(coroutineContext[Job])
        val fixture = CapacityRuntimeFixture(CoroutineScope(owner + control), control, io, testScheduler.timeSource)
        val release = CountDownLatch(1)
        try {
            val instances = fixture.requests.take(4).map { request -> activate(fixture, io, request) }
            val entered = CountDownLatch(4)
            instances.forEach { instance ->
                async {
                    fixture.runtime.invoke(instance, HarnessInvocationBudget.Tool) {
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                    }
                }
            }
            runCurrent()
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertTrue(fixture.lane.isExhausted)
            val fifth = fixture.requests.last()
            val waiting = async { fixture.runtime.activate(fifth) }
            runCurrent()
            assertFalse(waiting.isCompleted)
            fixture.runtime.deactivate(HarnessEffect.Deactivate(listOf(fifth), false, generation = fifth.generation))
            advanceTimeBy(1_001)
            runCurrent()
            assertTrue(waiting.isCompleted)
            assertFalse(waiting.await())
            assertTrue(fixture.lane.isExhausted)
            assertEquals(4, fixture.compilations.get())
        } finally {
            release.countDown()
            owner.cancelAndJoin()
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `instance blocked by its own callback times out its queued calls and is disabled`() = runTest {
        val executor = Executors.newFixedThreadPool(4)
        val io = CapacityTestDispatcher(executor)
        val control = StandardTestDispatcher(testScheduler)
        val owner = SupervisorJob(coroutineContext[Job])
        val fixture = CapacityRuntimeFixture(CoroutineScope(owner + control), control, io, testScheduler.timeSource)
        val release = CountDownLatch(1)
        try {
            val request = fixture.requests.first()
            val instance = activate(fixture, io, request)
            val entered = CountDownLatch(1)
            val blocked = async {
                fixture.runtime.invoke(instance, HarnessInvocationBudget.Instructions) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            }
            runCurrent()
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            // Only one of four shared workers is held: nothing foreign starves this instance.
            assertFalse(fixture.lane.isExhausted)
            val queued = List(HarnessLimits.FAILURES) {
                async { fixture.runtime.invoke(instance, HarnessInvocationBudget.Instructions) { "never" } }
            }
            runCurrent()
            advanceTimeBy(500)
            runCurrent()
            assertEquals(HarnessInvocationResult.TimedOut, blocked.await())
            queued.forEach { assertEquals(HarnessInvocationResult.TimedOut, it.await()) }
            runCurrent()
            assertTrue(fixture.failures.last().isDisabled)
            assertEquals(null, fixture.runtime.instance(request.harness.id, request.item.id))
            assertFalse(instance.isActive)
        } finally {
            release.countDown()
            owner.cancelAndJoin()
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `short calls queued behind an in-budget long call are expected cancellations, not failures`() = runTest {
        val executor = Executors.newFixedThreadPool(4)
        val io = CapacityTestDispatcher(executor)
        val control = StandardTestDispatcher(testScheduler)
        val owner = SupervisorJob(coroutineContext[Job])
        val fixture = CapacityRuntimeFixture(CoroutineScope(owner + control), control, io, testScheduler.timeSource)
        val release = CountDownLatch(1)
        try {
            val request = fixture.requests.first()
            val instance = activate(fixture, io, request)
            val entered = CountDownLatch(1)
            // Captured by the long call's worker dispatch: it counts down once that worker really exits.
            val completed = CountDownLatch(1)
            io.completions.set(completed)
            val longCall = async {
                fixture.runtime.invoke(instance, HarnessInvocationBudget.Tool) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    "done"
                }
            }
            runCurrent()
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            repeat(HarnessLimits.FAILURES + 1) {
                val queued = async { fixture.runtime.invoke(instance, HarnessInvocationBudget.Instructions) { "late" } }
                runCurrent()
                advanceTimeBy(500)
                runCurrent()
                assertEquals(HarnessInvocationResult.Cancelled(isExpected = true), queued.await())
            }
            runCurrent()
            assertTrue(fixture.failures.isEmpty())
            assertTrue(instance.isActive)
            release.countDown()
            assertTrue(completed.await(10, TimeUnit.SECONDS))
            runCurrent()
            val value = assertIs<HarnessInvocationResult.Completed<HarnessAttempt<String>>>(longCall.await()).value
            assertEquals("done", assertIs<HarnessAttempt.Success<String>>(value).value)
            assertTrue(instance.isActive)
        } finally {
            release.countDown()
            owner.cancelAndJoin()
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `long call blocked past its own budget fails the calls queued behind it and is disabled`() = runTest {
        val executor = Executors.newFixedThreadPool(4)
        val io = CapacityTestDispatcher(executor)
        val control = StandardTestDispatcher(testScheduler)
        val owner = SupervisorJob(coroutineContext[Job])
        val fixture = CapacityRuntimeFixture(CoroutineScope(owner + control), control, io, testScheduler.timeSource)
        val release = CountDownLatch(1)
        try {
            val request = fixture.requests.first()
            val instance = activate(fixture, io, request)
            val entered = CountDownLatch(1)
            val blocked = async {
                fixture.runtime.invoke(instance, HarnessInvocationBudget.Tool) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            }
            runCurrent()
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            advanceTimeBy(HarnessInvocationBudget.Tool.inWholeMilliseconds)
            runCurrent()
            assertEquals(HarnessInvocationResult.TimedOut, blocked.await())
            val queued = List(HarnessLimits.FAILURES - 1) {
                async { fixture.runtime.invoke(instance, HarnessInvocationBudget.Instructions) { "never" } }
            }
            runCurrent()
            advanceTimeBy(500)
            runCurrent()
            queued.forEach { assertEquals(HarnessInvocationResult.TimedOut, it.await()) }
            runCurrent()
            assertTrue(fixture.failures.last().isDisabled)
            assertFalse(instance.isActive)
        } finally {
            release.countDown()
            owner.cancelAndJoin()
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `background work holding the worker counts only once it outlives the background budget`() = runTest {
        val executor = Executors.newFixedThreadPool(4)
        val io = CapacityTestDispatcher(executor)
        val control = StandardTestDispatcher(testScheduler)
        val owner = SupervisorJob(coroutineContext[Job])
        val fixture = CapacityRuntimeFixture(CoroutineScope(owner + control), control, io, testScheduler.timeSource)
        val release = CountDownLatch(1)
        try {
            val request = fixture.requests.first()
            val instance = activate(fixture, io, request)
            val entered = CountDownLatch(1)
            instance.scope.launch {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val early = async { fixture.runtime.invoke(instance, HarnessInvocationBudget.Instructions) { "late" } }
            runCurrent()
            advanceTimeBy(500)
            runCurrent()
            assertEquals(HarnessInvocationResult.Cancelled(isExpected = true), early.await())
            assertTrue(fixture.failures.isEmpty())
            advanceTimeBy(HarnessInvocationBudget.Background.inWholeMilliseconds)
            val late = async { fixture.runtime.invoke(instance, HarnessInvocationBudget.Instructions) { "never" } }
            runCurrent()
            advanceTimeBy(500)
            runCurrent()
            assertEquals(HarnessInvocationResult.TimedOut, late.await())
            runCurrent()
            assertFalse(fixture.failures.last().isDisabled)
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

private class CapacityRuntimeFixture(
    scope: CoroutineScope,
    control: CoroutineDispatcher,
    io: CoroutineDispatcher,
    timeSource: TimeSource,
) {
    val requests: List<HarnessActivationRequest> = capacityRequests()
    val compilations = AtomicInteger()
    val failures = mutableListOf<HarnessIntent.Internal.ItemRuntimeFailed>()
    val lane = HarnessExecutionLane(io, timeSource)
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
        HarnessRuntimeEnvironment(scope, dispatchers, admission, contexts) { failures += it },
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
