package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.api.script.ScriptRegistration
import io.aequicor.heartbeat.feature.harness.impl.data.runtime.RuntimeHarnessTimerInvoker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class HarnessTimersTest {
    @Test
    fun `past deadline waits for committed publication and runs exactly once`() = runTest {
        val fixture = HarnessTimersFixture(this)
        val evaluation = CompletableDeferred<Unit>()
        var calls = 0
        fixture.runtime.beforeEvaluation = {
            fixture.timers.last().at(Instant.fromEpochMilliseconds(-1)) { calls++ }
            evaluation.await()
        }
        val activation = async { fixture.runtime.activate() }
        runCurrent()
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(0, calls)
        evaluation.complete(Unit)
        activation.await()
        runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(60.seconds)
        runCurrent()
        assertEquals(1, calls)
    }

    @Test
    fun `failed evaluation discards staged timers without invoking callbacks`() = runTest {
        val fixture = HarnessTimersFixture(this)
        var calls = 0
        fixture.runtime.beforeEvaluation = {
            fixture.timers.last().at(Instant.fromEpochMilliseconds(-1)) { calls++ }
        }
        fixture.runtime.isEvaluationSuccessful = false
        assertFalse(fixture.runtime.runtime.activate(fixture.runtime.desired))
        runCurrent()
        assertEquals(0, calls)
        assertFailsWith<IllegalStateException> { fixture.timers.last().every(30.seconds) {} }
    }

    @Test
    fun `timeout retains actual ownership and next interval starts only after actual completion`() = runTest {
        val fixture = HarnessTimersFixture(this)
        val blocked = CompletableDeferred<Unit>()
        var calls = 0
        fixture.runtime.beforeEvaluation = {
            fixture.timers.last().every(30.seconds) {
                calls++
                if (calls == 1) withContext(NonCancellable) { blocked.await() }
            }
        }
        val instance = fixture.runtime.activate()
        runCurrent()
        advanceTimeBy(60.seconds)
        runCurrent()
        assertEquals(1, calls)
        assertEquals(1, fixture.runtime.feedback.size)
        assertEquals(1, instance.consecutiveFailures)
        advanceTimeBy(120.seconds)
        runCurrent()
        assertEquals(1, calls)
        blocked.complete(Unit)
        runCurrent()
        assertEquals(1, instance.consecutiveFailures)
        advanceTimeBy(29.seconds)
        runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(2, calls)
        assertEquals(0, instance.consecutiveFailures)
    }

    @Test
    fun `disposal preserves admitted callback but prevents subsequent execution`() = runTest {
        val fixture = HarnessTimersFixture(this)
        val blocked = CompletableDeferred<Unit>()
        var calls = 0
        var completions = 0
        val registrations = mutableListOf<ScriptRegistration>()
        fixture.runtime.beforeEvaluation = {
            registrations += fixture.timers.last().every(30.seconds) {
                calls++
                blocked.await()
                completions++
            }
        }
        fixture.runtime.activate()
        runCurrent()
        advanceTimeBy(30.seconds)
        runCurrent()
        registrations.single().dispose()
        runCurrent()
        blocked.complete(Unit)
        runCurrent()
        assertEquals(1, completions)
        advanceTimeBy(90.seconds)
        runCurrent()
        assertEquals(1, calls)
    }

    @Test
    fun `disposed pending timer frees quota immediately and never runs`() = runTest {
        val fixture = HarnessTimersFixture(this)
        fixture.runtime.activate()
        val timers = fixture.timers.single()
        var calls = 0
        val registrations = List(HarnessLimits.TIMERS) { timers.every(30.seconds) { calls++ } }
        assertFailsWith<IllegalStateException> { timers.every(30.seconds) {} }
        registrations.forEach { it.dispose() }
        timers.at(Instant.fromEpochMilliseconds(1)) { calls++ }
        runCurrent()
        advanceTimeBy(90.seconds)
        runCurrent()
        assertEquals(1, calls)
    }

    @Test
    fun `retirement drains current timer and rejects further invocations`() = runTest {
        val fixture = HarnessTimersFixture(this)
        val blocked = CompletableDeferred<Unit>()
        var calls = 0
        fixture.runtime.beforeEvaluation = {
            fixture.timers.last().every(30.seconds) {
                calls++
                blocked.await()
            }
        }
        val instance = fixture.runtime.activate()
        runCurrent()
        advanceTimeBy(30.seconds)
        runCurrent()
        assertFalse(fixture.runtime.runtime.deactivate(fixture.runtime.deactivation()))
        runCurrent()
        assertFalse(instance.isClosed)
        blocked.complete(Unit)
        runCurrent()
        assertTrue(instance.isClosed)
        advanceTimeBy(90.seconds)
        runCurrent()
        assertEquals(1, calls)
    }

    @Test
    fun `five failed timer callbacks disable the instance through shared runtime accounting`() = runTest {
        val fixture = HarnessTimersFixture(this)
        var calls = 0
        fixture.runtime.beforeEvaluation = {
            fixture.timers.last().every(30.seconds) {
                calls++
                error("private callback failure")
            }
        }
        val instance = fixture.runtime.activate()
        runCurrent()
        advanceTimeBy(180.seconds)
        runCurrent()
        assertEquals(5, calls)
        assertTrue(instance.isClosed)
        assertEquals(5, fixture.runtime.feedback.size)
        assertTrue(fixture.runtime.feedback.last().isDisabled)
    }

    @Test
    fun `old timer cannot invoke a replacement generation`() = runTest {
        val fixture = HarnessTimersFixture(this)
        fixture.runtime.activate()
        val target = fixture.targets.single()
        fixture.runtime.desired = runtimeRequest(2)
        fixture.runtime.activate()
        runCurrent()
        val callback = HarnessCallback<suspend () -> Unit>(1, HarnessCallOrigin()) { error("Old generation invoked") }
        val invoker = RuntimeHarnessTimerInvoker(lazy { fixture.runtime.runtime })
        assertFalse(invoker.awaitPublication(target))
        assertFalse(invoker.invoke(target, callback))
    }

    @Test
    fun `short and infinite intervals reject registration without consuming quota`() = runTest {
        val fixture = HarnessTimersFixture(this)
        fixture.runtime.activate()
        val timers = fixture.timers.single()
        listOf(Duration.ZERO, 29.seconds, (-1).seconds, Duration.INFINITE).forEach { interval ->
            assertFailsWith<IllegalArgumentException> { timers.every(interval) {} }
        }
        repeat(HarnessLimits.TIMERS) { timers.every(30.seconds) {} }
    }
}
