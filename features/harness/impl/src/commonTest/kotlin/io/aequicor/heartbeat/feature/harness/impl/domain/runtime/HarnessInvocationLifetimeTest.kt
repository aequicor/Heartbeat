package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class HarnessInvocationLifetimeTest {
    @Test
    fun `cancelling lifetime refuses entry before blocked child completes`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val lifetime = Job()
        val cleanup = CompletableDeferred<Unit>()
        val child = CoroutineScope(lifetime + dispatcher).launch {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { cleanup.await() }
            }
        }
        runCurrent()
        lifetime.cancel()
        runCurrent()
        assertFalse(lifetime.isCompleted)
        val invocation = HarnessInvocation(backgroundScope, dispatcher)
        var didEnter = false
        try {
            val result = invocation.run(dispatcher, 10.seconds, HarnessInvocationOptions(lifetime = lifetime)) {
                didEnter = true
            }
            assertEquals(HarnessInvocationResult.Cancelled(isExpected = true), result)
            assertFalse(didEnter)
            assertFalse(lifetime.isCompleted)
        } finally {
            cleanup.complete(Unit)
            child.join()
            lifetime.cancelAndJoin()
        }
    }

    @Test
    fun `handle retains actual completion after timeout and completion observer fires once`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val invocation = HarnessInvocation(backgroundScope, dispatcher)
        val gate = CompletableDeferred<Unit>()
        var handle: HarnessInvocationHandle? = null
        var stopped = 0
        val result = async {
            invocation.run(
                dispatcher,
                1.seconds,
                HarnessInvocationOptions(onStarted = {
                    handle = it
                    it.invokeOnStopped { stopped++ }
                }),
            ) {
                withContext(NonCancellable) { gate.await() }
            }
        }
        runCurrent()
        val actual = assertNotNull(handle)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(HarnessInvocationResult.TimedOut, result.await())
        assertFalse(actual.isStopped)
        assertEquals(0, stopped)
        gate.complete(Unit)
        actual.awaitStopped()
        assertTrue(actual.isStopped)
        assertEquals(1, stopped)
    }

    @Test
    fun `revoked lifetime releases logical waiter while actual cleanup remains owned`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val invocation = HarnessInvocation(backgroundScope, dispatcher)
        val lifetime = Job()
        val gate = CompletableDeferred<Unit>()
        var handle: HarnessInvocationHandle? = null
        val result = async {
            invocation.run(
                dispatcher,
                60.seconds,
                HarnessInvocationOptions(lifetime = lifetime, onStarted = {
                    handle = it
                }),
            ) {
                withContext(NonCancellable) { gate.await() }
            }
        }
        runCurrent()
        lifetime.cancel()
        runCurrent()
        assertEquals(HarnessInvocationResult.Cancelled(isExpected = true), result.await())
        assertFalse(assertNotNull(handle).isStopped)
        assertEquals(1, invocation.activeCount)
        gate.complete(Unit)
        assertNotNull(handle).awaitStopped()
        lifetime.cancelAndJoin()
    }

    @Test
    fun `explicit normal origin cannot relax caller hook restriction in actual job`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val invocation = HarnessInvocation(backgroundScope, dispatcher)
        val result = withContext(HarnessOriginContext(HarnessCallOrigin(isHookRestricted = true))) {
            invocation.run(dispatcher, 1.seconds) {
                currentCoroutineContext()[HarnessOriginContext]?.origin?.isHookRestricted
            }
        }
        assertEquals(true, assertIs<HarnessInvocationResult.Completed<Boolean?>>(result).value)
    }

    @Test
    fun `expected lifetime cancellation does not reset prior runtime failures`() = runTest {
        val fixture = HarnessRuntimeFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val instance = fixture.activate()
        fixture.runtime.invoke(instance, 1.seconds) { error("private") }
        val lifetime = Job().apply { cancel() }
        val result = fixture.runtime.invoke(instance, 1.seconds, HarnessInvocationOptions(lifetime = lifetime)) {
            error("Ended lifetime entered")
        }
        assertEquals(HarnessInvocationResult.Cancelled(isExpected = true), result)
        assertEquals(1, instance.consecutiveFailures)
        assertTrue(instance.isActive)
    }
}
