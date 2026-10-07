package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class HarnessRuntimeFailureTest {
    @Test
    fun `five consecutive failures retire exact instance while a success resets the streak`() = runTest {
        val fixture = HarnessRuntimeFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val instance = fixture.activate()
        repeat(4) { fixture.runtime.invoke(instance, 30.seconds) { error("private") } }
        fixture.runtime.invoke(instance, 30.seconds) { "success" }
        repeat(4) { fixture.runtime.invoke(instance, 30.seconds) { error("private") } }
        assertFalse(instance.isClosed)
        assertTrue(instance.isActive)
        fixture.runtime.invoke(instance, 30.seconds) { error("private") }
        runCurrent()
        assertTrue(instance.isClosed)
        assertEquals(9, fixture.feedback.size)
        assertEquals(1, fixture.feedback.count { it.isDisabled })
        assertTrue(fixture.feedback.all { it.generation == instance.request.generation })
        assertNull(fixture.runtime.invoke(instance, 30.seconds) { "late" })
    }

    @Test
    fun `timeout counts once and late successful return neither resets nor releases code early`() = runTest {
        val fixture = HarnessRuntimeFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val instance = fixture.activate()
        val gate = CompletableDeferred<Unit>()
        val call = async {
            fixture.runtime.invoke(instance, 1.seconds) {
                withContext(NonCancellable) {
                    gate.await()
                    "late"
                }
            }
        }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(HarnessInvocationResult.TimedOut, call.await())
        assertEquals(1, instance.consecutiveFailures)
        assertEquals(1, fixture.feedback.size)
        assertFalse(fixture.runtime.deactivate(fixture.deactivation()))
        runCurrent()
        assertEquals(0, fixture.code.single().closes)
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, instance.consecutiveFailures)
        assertEquals(1, fixture.feedback.size)
        assertEquals(1, fixture.code.single().closes)
    }

    @Test
    fun `caller cancellation is not an execution failure`() = runTest {
        val fixture = HarnessRuntimeFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val instance = fixture.activate()
        val call = async { fixture.runtime.invoke(instance, 30.seconds) { awaitCancellation() } }
        runCurrent()
        call.cancelAndJoin()
        assertEquals(0, instance.consecutiveFailures)
        assertTrue(fixture.feedback.isEmpty())
        assertTrue(instance.isActive)
    }

    @Test
    fun `background failure during candidate initialization prevents publication`() = runTest {
        val fixture = HarnessRuntimeFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val failed = CompletableDeferred<Unit>()
        fixture.onCreate = { access ->
            access.scope.launch {
                try {
                    error("private initialization failure")
                } finally {
                    failed.complete(Unit)
                }
            }
        }
        fixture.beforeEvaluation = { failed.await() }
        assertFalse(fixture.runtime.activate(fixture.desired))
        runCurrent()
        assertNull(fixture.runtime.instance(fixture.desired.harness.id, fixture.desired.item.id))
        assertEquals(1, fixture.code.single().closes)
        assertEquals(1, fixture.contextCloses)
    }

    @Test
    fun `successful evaluation cannot publish an incomplete host context`() = runTest {
        val fixture = HarnessRuntimeFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        fixture.isContextReady = false
        assertFalse(fixture.runtime.activate(fixture.desired))
        runCurrent()
        assertEquals(1, fixture.code.single().closes)
        assertNull(fixture.runtime.instance(fixture.desired.harness.id, fixture.desired.item.id))
    }

    @Test
    fun `disabled feedback belongs to profile after the invocation caller cancels`() = runTest {
        val fixture = HarnessRuntimeFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val instance = fixture.activate()
        repeat(4) { fixture.runtime.invoke(instance, 30.seconds) { error("private") } }
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        fixture.beforeFeedback = { gate.await() }
        val caller = launch {
            fixture.runtime.invoke(instance, 30.seconds) { error("private") }
            awaitCancellation()
        }
        runCurrent()
        assertTrue(instance.isClosed)
        assertEquals(4, fixture.feedback.size)
        caller.cancelAndJoin()
        gate.complete(Unit)
        runCurrent()
        assertEquals(5, fixture.feedback.size)
        assertTrue(fixture.feedback.last().isDisabled)
    }
}
