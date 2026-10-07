package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.feature.harness.api.HarnessEffect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds

class HarnessPublishedInstancesTest {
    @Test
    fun `publication exposes exact replacement and removes deactivated generation`() = runTest {
        val fixture = HarnessRegistrationFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val snapshots = mutableListOf<List<HarnessInstance>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.runtime.publishedInstances.collect { snapshots += it }
        }
        val first = fixture.activate()
        fixture.desired = scriptRequest(2)
        val replacement = fixture.activate()
        assertEquals(listOf(emptyList(), listOf(first), listOf(replacement)), snapshots)
        assertFalse(first.isActive)
        assertSame(replacement, fixture.runtime.publishedInstances.value.single())
        fixture.runtime.deactivate(HarnessEffect.Deactivate(listOf(fixture.desired), false, generation = 2))
        assertEquals(emptyList(), fixture.runtime.publishedInstances.value)
        assertEquals(emptyList(), snapshots.last())
    }

    @Test
    fun `staged and failed candidate never replace observable published instance`() = runTest {
        val fixture = HarnessRegistrationFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val first = fixture.activate()
        val snapshots = mutableListOf<List<HarnessInstance>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.runtime.publishedInstances.collect { snapshots += it }
        }
        val release = CompletableDeferred<Unit>()
        fixture.desired = scriptRequest(2)
        fixture.isEvaluationSuccessful = false
        fixture.evaluate = { release.await() }
        val candidate = async { fixture.runtime.activate(fixture.desired) }
        runCurrent()
        assertFalse(candidate.isCompleted)
        assertEquals(listOf(first), fixture.runtime.publishedInstances.value)
        release.complete(Unit)
        assertFalse(candidate.await())
        runCurrent()
        assertEquals(listOf(listOf(first)), snapshots)
    }

    @Test
    fun `observed handle never grants authority after committed admission changes`() = runTest {
        val fixture = HarnessRegistrationFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val instance = fixture.activate()
        val retained = fixture.runtime.publishedInstances.value.single()
        fixture.isEnabled = false
        assertSame(instance, retained)
        assertNull(fixture.runtime.invoke(retained, 1.seconds) { error("Must not execute") })
        assertEquals(emptyList(), fixture.runtime.published())
        fixture.runtime.deactivate(HarnessEffect.Deactivate(listOf(fixture.desired), false, generation = 1))
        assertEquals(emptyList(), fixture.runtime.publishedInstances.value)
    }

    @Test
    fun `removal fence immediately removes published handle before drain completes`() = runTest {
        val fixture = HarnessRuntimeFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        fixture.activate()
        assertEquals(1, fixture.runtime.publishedInstances.value.size)
        fixture.runtime.revokeHarness(fixture.removal())
        assertEquals(emptyList(), fixture.runtime.publishedInstances.value)
    }
}
