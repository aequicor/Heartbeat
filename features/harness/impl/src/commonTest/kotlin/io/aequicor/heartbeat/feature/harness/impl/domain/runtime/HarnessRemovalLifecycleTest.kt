package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.feature.harness.api.HarnessId
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

class HarnessRemovalLifecycleTest {
    @Test
    fun `rollback permits fresh activation while stale removal touches neither cache nor journals`() = runTest {
        val fixture = HarnessRuntimeFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val runs = RemovalTestRuns()
        val control = ProfileHarnessRuntimeControl(fixture.runtime, runs) { true }
        fixture.activate()
        val removal = fixture.removal()
        assertEquals(HarnessRemovalResult.Retry, control.remove(removal))
        runCurrent()
        assertEquals(HarnessRemovalResult.Ready, control.remove(removal))
        fixture.pendingRemoval = null
        fixture.desired = runtimeRequest(2)
        val restored = fixture.activate()
        val loads = runs.loads
        val removed = fixture.removedCache.toList()
        assertEquals(HarnessRemovalResult.Obsolete, control.remove(removal))
        assertTrue(restored.isActive)
        assertEquals(loads, runs.loads)
        assertEquals(removed, fixture.removedCache)
    }

    @Test
    fun `retry never widens captured fence when unrelated library generations advance`() = runTest {
        val fixture = HarnessRuntimeFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val control = ProfileHarnessRuntimeControl(fixture.runtime, RemovalTestRuns()) { true }
        fixture.activate()
        val removal = fixture.removal()
        assertEquals(HarnessRemovalResult.Retry, control.remove(removal))
        runCurrent()
        fixture.libraryGeneration = 100
        assertEquals(HarnessRemovalResult.Ready, control.remove(removal))
        fixture.pendingRemoval = null
        // Probe the core's captured upper bound independently of how the library allocates its next generation.
        fixture.desired = runtimeRequest(2)
        assertTrue(fixture.activate().isActive)
    }

    @Test
    fun `unrelated retired execution does not prevent another item cleanup receipt`() = runTest {
        val fixture = HarnessRuntimeFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val first = fixture.activate()
        val gate = CompletableDeferred<Unit>()
        val call = async { fixture.runtime.invoke(first, 30.seconds) { gate.await() } }
        runCurrent()
        assertFalse(fixture.runtime.deactivate(fixture.deactivation()))
        val other = runtimeRequest(2)
        fixture.desired = other.copy(harness = other.harness.copy(id = HarnessId("other")))
        val second = fixture.activate()
        val effect = fixture.deactivation()
        assertFalse(fixture.runtime.deactivate(effect))
        runCurrent()
        assertTrue(second.isClosed)
        assertFalse(first.isClosed)
        assertTrue(fixture.runtime.deactivate(effect))
        gate.complete(Unit)
        call.await()
        runCurrent()
        assertTrue(first.isClosed)
    }

    @Test
    fun `deletion becoming obsolete during journal read never begins cache IO`() = runTest {
        val fixture = HarnessRuntimeFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val runs = RemovalTestRuns()
        val control = ProfileHarnessRuntimeControl(fixture.runtime, runs) { true }
        val gate = CompletableDeferred<Unit>()
        runs.beforeLoad = { gate.await() }
        val removal = fixture.removal()
        val work = async { control.remove(removal) }
        runCurrent()
        fixture.pendingRemoval = null
        fixture.desired = runtimeRequest(2)
        val restored = fixture.activate()
        gate.complete(Unit)
        assertEquals(HarnessRemovalResult.Obsolete, work.await())
        assertTrue(fixture.removedCache.isEmpty())
        assertTrue(restored.isActive)
    }
}

private class RemovalTestRuns : HarnessRunStorage {
    var loads = 0
    var beforeLoad: suspend () -> Unit = {}
    override suspend fun load(): List<WorkflowRun> {
        loads++
        beforeLoad()
        return emptyList()
    }
    override suspend fun save(run: WorkflowRun, expectedGeneration: Long?): Boolean = error("Not used")
}
