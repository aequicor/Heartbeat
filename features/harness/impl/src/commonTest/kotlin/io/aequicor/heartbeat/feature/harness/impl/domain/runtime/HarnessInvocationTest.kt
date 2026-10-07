package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class HarnessInvocationTest {
    @Test
    fun `retirement sees registered work before control dispatcher begins invocation`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = SupervisorJob(coroutineContext[Job])
        val invocation = HarnessInvocation(CoroutineScope(owner + dispatcher), dispatcher)
        var didEnter = false
        try {
            val waiting = async(start = CoroutineStart.UNDISPATCHED) {
                invocation.run(dispatcher, 10.seconds) { didEnter = true }
            }
            assertEquals(1, invocation.activeCount)
            invocation.cancelAll()
            invocation.awaitIdle()
            runCurrent()
            assertFailsWith<CancellationException> { waiting.await() }
            assertFalse(didEnter)
            assertEquals(0, invocation.activeCount)
        } finally {
            owner.cancelAndJoin()
        }
    }

    @Test
    fun `suspended callbacks release lane capacity while remaining owned`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val lane = HarnessExecutionLane(dispatcher)
        val owner = SupervisorJob(coroutineContext[Job])
        val invocation = HarnessInvocation(CoroutineScope(owner + dispatcher), dispatcher)
        val gate = CompletableDeferred<Unit>()
        try {
            val waiting = List(4) {
                async { invocation.run(lane.instanceDispatcher(), 10.seconds) { gate.await() } }
            }
            runCurrent()
            assertEquals(4, invocation.activeCount)
            assertFalse(lane.isExhausted)
            val fifth = async { invocation.run(lane.instanceDispatcher(), 10.seconds) { "available" } }
            runCurrent()
            assertEquals("available", assertIs<HarnessInvocationResult.Completed<String>>(fifth.await()).value)
            gate.complete(Unit)
            waiting.forEach { assertIs<HarnessInvocationResult.Completed<Unit>>(it.await()) }
            invocation.awaitIdle()
            assertEquals(0, invocation.activeCount)
        } finally {
            owner.cancelAndJoin()
        }
    }

    @Test
    fun `deadline cancels actual suspend call without cancelling supervised owner`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = SupervisorJob(coroutineContext[Job])
        val invocation = HarnessInvocation(CoroutineScope(owner + dispatcher), dispatcher)
        var didRelease = false
        try {
            val waiting = async {
                invocation.run(dispatcher, HarnessInvocationBudget.Instructions) {
                    try {
                        CompletableDeferred<Unit>().await()
                    } finally {
                        didRelease = true
                    }
                }
            }
            runCurrent()
            advanceTimeBy(500)
            runCurrent()
            assertEquals(HarnessInvocationResult.TimedOut, waiting.await())
            invocation.awaitIdle()
            assertTrue(didRelease)
            assertTrue(owner.isActive)
            assertEquals(0, invocation.activeCount)
        } finally {
            owner.cancelAndJoin()
        }
    }

    @Test
    fun `caller and callback cancellation propagate rather than become timeout or success`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = SupervisorJob(coroutineContext[Job])
        val invocation = HarnessInvocation(CoroutineScope(owner + dispatcher), dispatcher)
        try {
            val selfCancelled = async {
                invocation.run(dispatcher, 10.seconds) { throw CancellationException("private") }
            }
            runCurrent()
            assertFailsWith<CancellationException> { selfCancelled.await() }
            val gate = CompletableDeferred<Unit>()
            val waiting = async { invocation.run(dispatcher, 10.seconds) { gate.await() } }
            runCurrent()
            waiting.cancelAndJoin()
            invocation.awaitIdle()
            assertTrue(waiting.isCancelled)
            assertEquals(0, invocation.activeCount)
            assertTrue(owner.isActive)
        } finally {
            owner.cancelAndJoin()
        }
    }

    @Test
    fun `owned callback failure propagates but does not cancel sibling executions`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = SupervisorJob(coroutineContext[Job])
        val reported = CompletableDeferred<Unit>()
        val errors = CoroutineExceptionHandler { _, error ->
            reported.completeExceptionally(error)
        }
        val invocation = HarnessInvocation(CoroutineScope(owner + dispatcher + errors), dispatcher)
        try {
            val failure = IllegalStateException("private")
            assertFailsWith<IllegalStateException> {
                invocation.run(dispatcher, 10.seconds) { throw failure }
            }
            assertFailsWith<IllegalStateException> { reported.await() }
            // await may recover coroutine stack traces into a copy; stored completion retains the exact throwable.
            assertSame(failure, reported.getCompletionExceptionOrNull())
            assertEquals(
                HarnessInvocationResult.Completed("next"),
                invocation.run(dispatcher, 10.seconds) { "next" },
            )
            assertFalse(HarnessInvocationResult.Completed("private").toString().contains("private"))
            invocation.awaitIdle()
            assertEquals(0, invocation.activeCount)
        } finally {
            owner.cancelAndJoin()
        }
    }
}
