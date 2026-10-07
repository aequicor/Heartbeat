package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRun
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessRemovalResult
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessRunStorage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class HarnessRuntimeCacheTest {
    @Test
    fun `item deletion removes cache after drain while a late cleanup preserves a recreated item`() = runTest {
        val fixture = HarnessRuntimeFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val control = ProfileHarnessRuntimeControl(fixture.runtime, EmptyRuntimeRuns())
        val old = fixture.activate()
        val gate = CompletableDeferred<Unit>()
        val call = async { fixture.runtime.invoke(old, 30.seconds) { gate.await() } }
        runCurrent()
        val effect = fixture.deactivation()
        fixture.isItemPresent = false
        assertFalse(control.deactivate(effect))
        assertTrue(fixture.removedCache.isEmpty())
        gate.complete(Unit)
        call.await()
        runCurrent()
        assertTrue(control.deactivate(effect))
        assertEquals(listOf(old.request.item.id), fixture.removedCache)
        fixture.isItemPresent = true
        fixture.desired = runtimeRequest(2)
        val current = fixture.activate()
        assertTrue(control.deactivate(effect))
        assertTrue(current.isActive)
        assertEquals(listOf(old.request.item.id), fixture.removedCache)
    }

    @Test
    fun `new compiler admission waits for an already authorized old cache removal`() = runTest {
        val fixture = HarnessRuntimeFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val control = ProfileHarnessRuntimeControl(fixture.runtime, EmptyRuntimeRuns())
        fixture.activate()
        val effect = fixture.deactivation()
        fixture.isItemPresent = false
        assertFalse(control.deactivate(effect))
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        fixture.beforeCacheRemoval = { gate.await() }
        val removal = async { control.deactivate(effect) }
        runCurrent()
        fixture.isItemPresent = true
        fixture.desired = runtimeRequest(2)
        val activation = async { fixture.activate() }
        runCurrent()
        assertFalse(activation.isCompleted)
        assertEquals(listOf("revision1"), fixture.cacheOperations)
        gate.complete(Unit)
        assertTrue(removal.await())
        assertTrue(activation.await().isActive)
        assertEquals(listOf("revision1", "remove", "revision2"), fixture.cacheOperations)
    }

    @Test
    fun `full removal cannot overtake compiler admission after preparation cancellation`() = runTest {
        val fixture = HarnessRuntimeFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val control = ProfileHarnessRuntimeControl(fixture.runtime, EmptyRuntimeRuns())
        val gate = CompletableDeferred<Unit>()
        fixture.beforeCompile = { withContext(NonCancellable) { gate.await() } }
        val activation = async { control.activate(fixture.desired) }
        runCurrent()
        val removal = async { control.remove(fixture.removal()) }
        runCurrent()
        assertFalse(activation.await())
        assertFalse(removal.isCompleted)
        assertTrue(fixture.removedCache.isEmpty())
        gate.complete(Unit)
        assertEquals(HarnessRemovalResult.Ready, removal.await())
        runCurrent()
        assertEquals(listOf("revision1", "remove"), fixture.cacheOperations)
        assertEquals(1, fixture.code.single().closes)
    }

    @Test
    fun `recoverable cache removal failure retains fence and retries without a false completion`() = runTest {
        val fixture = HarnessRuntimeFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val control = ProfileHarnessRuntimeControl(fixture.runtime, EmptyRuntimeRuns())
        var attempts = 0
        fixture.beforeCacheRemoval = {
            attempts++
            if (attempts == 1) error("private cache path")
        }
        assertEquals(HarnessRemovalResult.Retry, control.remove(fixture.removal(fixture.desired.harness)))
        assertTrue(fixture.removedCache.isEmpty())
        assertFalse(control.activate(fixture.desired))
        assertEquals(HarnessRemovalResult.Ready, control.remove(fixture.removal(fixture.desired.harness)))
        assertEquals(2, attempts)
        assertEquals(listOf(fixture.desired.item.id), fixture.removedCache)
    }
}

private class EmptyRuntimeRuns : HarnessRunStorage {
    override suspend fun load(): List<WorkflowRun> = emptyList()
    override suspend fun save(run: WorkflowRun, expectedGeneration: Long?): Boolean = error("Not used")
}
