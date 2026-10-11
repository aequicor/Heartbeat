package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HarnessExecutionLaneTest {
    @Test
    fun `fatal callback failure reaches owner after its waiter already timed out`() = runTest {
        val executor = Executors.newSingleThreadExecutor()
        val io = executor.asCoroutineDispatcher()
        val control = StandardTestDispatcher(testScheduler)
        val owner = SupervisorJob(coroutineContext[Job])
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reported = CompletableDeferred<Unit>()
        val errors = CoroutineExceptionHandler { _, error ->
            reported.completeExceptionally(error)
        }
        val invocation = HarnessInvocation(CoroutineScope(owner + control + errors), control)
        val fatal = OutOfMemoryError("test sentinel")
        try {
            val waiter = async {
                invocation.run(io, HarnessInvocationBudget.Instructions) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    throw fatal
                }
            }
            runCurrent()
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            advanceTimeBy(500)
            runCurrent()
            assertEquals(HarnessInvocationResult.TimedOut, waiter.await())
            assertEquals(1, invocation.activeCount)
            release.countDown()
            invocation.awaitIdle()
            assertFailsWith<OutOfMemoryError> { reported.await() }
            // Coroutine stack-trace recovery may copy await's exception; inspect the actual stored completion.
            assertSame(fatal, reported.getCompletionExceptionOrNull())
            assertTrue(owner.isActive)
            assertEquals(0, invocation.activeCount)
        } finally {
            release.countDown()
            owner.cancelAndJoin()
            io.close()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `four blocked callbacks keep actual capacity and resources after all waiters time out`() = runTest {
        val executor = Executors.newFixedThreadPool(8)
        val io = executor.asCoroutineDispatcher()
        val control = StandardTestDispatcher(testScheduler)
        val lane = HarnessExecutionLane(io)
        val owner = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(owner + control)
        val invocations = List(4) { HarnessInvocation(scope, control) }
        val entered = CountDownLatch(4)
        val release = CountDownLatch(1)
        val released = AtomicInteger()
        try {
            val waiters = invocations.map { invocation ->
                async {
                    invocation.run(lane.instanceDispatcher(), HarnessInvocationBudget.Instructions) {
                        entered.countDown()
                        try {
                            check(release.await(10, TimeUnit.SECONDS))
                        } finally {
                            released.incrementAndGet()
                        }
                    }
                }
            }
            runCurrent()
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertTrue(lane.isExhausted)
            advanceTimeBy(500)
            runCurrent()
            waiters.forEach { assertEquals(HarnessInvocationResult.TimedOut, it.await()) }
            assertEquals(0, released.get())
            assertTrue(lane.isExhausted)
            invocations.forEach { assertEquals(1, it.activeCount) }
            release.countDown()
            invocations.forEach { it.awaitIdle() }
            owner.cancelAndJoin()
            io.close()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
            assertEquals(4, released.get())
            invocations.forEach { assertEquals(0, it.activeCount) }
            assertFalse(lane.isExhausted)
        } finally {
            release.countDown()
            owner.cancelAndJoin()
            io.close()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }
}
