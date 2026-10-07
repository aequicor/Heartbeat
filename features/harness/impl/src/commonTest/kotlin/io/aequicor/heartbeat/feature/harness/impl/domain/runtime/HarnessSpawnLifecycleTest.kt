package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.feature.harness.api.HarnessEffect
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRun
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessRemovalResult
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessRunStorage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class HarnessSpawnLifecycleTest {
    @Test
    fun `item removal revokes helpers during a live callback and waits for helper settlement`() = runTest {
        val fixture = HarnessRuntimeFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val calls = mutableListOf<HarnessEffect.Deactivate>()
        var areReleased = false
        val control = ProfileHarnessRuntimeControl(fixture.runtime, NoSpawnRuns) {
            calls += it
            areReleased
        }
        val instance = fixture.activate()
        val gate = CompletableDeferred<Unit>()
        val callback = async { fixture.runtime.invoke(instance, 30.seconds) { gate.await() } }
        runCurrent()
        val effect = fixture.deactivation()
        fixture.isItemPresent = false
        assertFalse(control.deactivate(effect))
        assertEquals(listOf(effect), calls)
        gate.complete(Unit)
        callback.await()
        runCurrent()
        assertFalse(control.deactivate(effect))
        assertTrue(fixture.removedCache.isEmpty())
        areReleased = true
        assertTrue(control.deactivate(effect))
        assertEquals(listOf(instance.request.item.id), fixture.removedCache)
    }

    @Test
    fun `delete revokes helpers before code drain and retries retain the original generation`() = runTest {
        val fixture = HarnessRuntimeFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val calls = mutableListOf<HarnessEffect.Deactivate>()
        var areReleased = false
        val control = ProfileHarnessRuntimeControl(fixture.runtime, NoSpawnRuns) {
            calls += it
            areReleased
        }
        fixture.activate()
        val removal = fixture.removal()
        assertEquals(HarnessRemovalResult.Retry, control.remove(removal))
        assertEquals(1, calls.size)
        assertEquals(setOf(removal.harness.id), calls.single().harnesses)
        assertTrue(calls.single().isStopping)
        assertTrue(calls.single().items.isEmpty())
        runCurrent()
        fixture.libraryGeneration = 100
        assertEquals(HarnessRemovalResult.Retry, control.remove(removal))
        assertEquals(calls.first().generation, calls.last().generation)
        assertTrue(fixture.removedCache.isEmpty())
        areReleased = true
        assertEquals(HarnessRemovalResult.Ready, control.remove(removal))
        fixture.pendingRemoval = null
        val count = calls.size
        assertEquals(HarnessRemovalResult.Obsolete, control.remove(removal))
        assertEquals(count, calls.size)
    }

    @Test
    fun `delete superseded during helper cleanup cannot remove cache of the replacement`() = runTest {
        val fixture = HarnessRuntimeFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        val control = ProfileHarnessRuntimeControl(fixture.runtime, NoSpawnRuns) {
            gate.await()
            true
        }
        val work = async { control.remove(fixture.removal()) }
        runCurrent()
        fixture.pendingRemoval = null
        fixture.desired = runtimeRequest(2)
        val replacement = fixture.activate()
        gate.complete(Unit)
        assertEquals(HarnessRemovalResult.Obsolete, work.await())
        assertTrue(fixture.removedCache.isEmpty())
        assertTrue(replacement.isActive)
    }
}

private object NoSpawnRuns : HarnessRunStorage {
    override suspend fun load(): List<WorkflowRun> = emptyList()
    override suspend fun save(run: WorkflowRun, expectedGeneration: Long?): Boolean = error("Not used")
}
