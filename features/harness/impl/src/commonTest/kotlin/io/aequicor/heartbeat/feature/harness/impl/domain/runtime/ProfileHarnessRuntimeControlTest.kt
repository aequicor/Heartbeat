package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.feature.harness.api.HarnessItem
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.api.workflow.PinnedWorkflow
import io.aequicor.heartbeat.feature.harness.api.workflow.RunId
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowOrigin
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRun
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStatus
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessRemovalResult
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessRunStorage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class ProfileHarnessRuntimeControlTest {
    @Test
    fun `mobile capability never activates code but retains durable removal guard`() = runTest {
        val fixture = HarnessRuntimeFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        fixture.isHostAvailable = false
        val runs = RuntimeRunRecords()
        val control = ProfileHarnessRuntimeControl(fixture.runtime, runs) { true }
        assertFalse(control.isAvailable)
        assertFalse(control.activate(fixture.desired))
        assertTrue(fixture.code.isEmpty())
        runs.records = listOf(runtimeRun())
        assertEquals(HarnessRemovalResult.Retry, control.remove(fixture.removal(fixture.desired.harness)))
        assertTrue(fixture.removedCache.isEmpty())
    }

    @Test
    fun `running journal prevents pause stop and cache removal until native work is proven terminal`() = runTest {
        val fixture = HarnessRuntimeFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val runs = RuntimeRunRecords().apply { records = listOf(runtimeRun()) }
        val control = ProfileHarnessRuntimeControl(fixture.runtime, runs) { true }
        val effect = fixture.deactivation().copy(harnesses = setOf(fixture.desired.harness.id))
        assertFalse(control.deactivate(effect))
        assertFalse(control.deactivate(effect.copy(isStopping = true)))
        assertEquals(HarnessRemovalResult.Retry, control.remove(fixture.removal(fixture.desired.harness)))
        assertTrue(fixture.removedCache.isEmpty())
        runs.records = runs.records.map {
            it.copy(status = WorkflowStatus.Completed(JsonNull), finishedAt = it.startedAt)
        }
        assertEquals(HarnessRemovalResult.Ready, control.remove(fixture.removal(fixture.desired.harness)))
        assertEquals(listOf(fixture.desired.item.id), fixture.removedCache)
    }

    @Test
    fun `cache removal follows actual code drain and only removes executable items`() = runTest {
        val fixture = HarnessRuntimeFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val control = ProfileHarnessRuntimeControl(fixture.runtime, RuntimeRunRecords()) { true }
        val instance = fixture.activate()
        val gate = CompletableDeferred<Unit>()
        val call = async { fixture.runtime.invoke(instance, 30.seconds) { gate.await() } }
        runCurrent()
        val harness = fixture.desired.harness.let {
            it.copy(items = it.items + HarnessItem.Skill(ItemId("skill"), ItemName("skill"), "", ""))
        }
        assertEquals(HarnessRemovalResult.Retry, control.remove(fixture.removal(harness)))
        assertTrue(fixture.removedCache.isEmpty())
        gate.complete(Unit)
        call.await()
        runCurrent()
        assertEquals(HarnessRemovalResult.Ready, control.remove(fixture.removal(harness)))
        assertEquals(listOf(instance.request.item.id), fixture.removedCache)
        assertEquals(1, fixture.code.single().closes)
        assertFalse(control.activate(fixture.desired))
    }

    @Test
    fun `fence precedes suspended journal read and delayed receipt preserves newer activation`() = runTest {
        val fixture = HarnessRuntimeFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val runs = RuntimeRunRecords()
        val control = ProfileHarnessRuntimeControl(fixture.runtime, runs) { true }
        val instance = fixture.activate()
        // Drain the old generation first so the adapter can proceed to its durable running guard.
        val effect = fixture.deactivation().copy(harnesses = setOf(fixture.desired.harness.id))
        assertFalse(control.deactivate(effect))
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        runs.beforeLoad = { gate.await() }
        val oldCleanup = async { control.deactivate(effect) }
        runCurrent()
        assertNull(fixture.runtime.instance(instance.request.harness.id, instance.request.item.id))
        fixture.desired = runtimeRequest(2)
        val replacement = fixture.activate()
        gate.complete(Unit)
        assertTrue(oldCleanup.await())
        assertSame(replacement, fixture.runtime.instance(replacement.request.harness.id, replacement.request.item.id))
        assertFalse(replacement.isClosed)
    }
}

private class RuntimeRunRecords : HarnessRunStorage {
    var records = emptyList<WorkflowRun>()
    var beforeLoad: suspend () -> Unit = {}
    override suspend fun load(): List<WorkflowRun> {
        beforeLoad()
        return records
    }
    override suspend fun save(run: WorkflowRun, expectedGeneration: Long?): Boolean = error("Not used")
}

private fun runtimeRun(): WorkflowRun {
    val request = runtimeRequest(1)
    val at = request.harness.createdAt
    return WorkflowRun(
        RunId("wf_one"), request.harness.id, request.item.id,
        PinnedWorkflow("", "a".repeat(64), request.harness.revision, emptyMap()),
        JsonObject(emptyMap()), null, WorkflowOrigin.Script, at, at + 1.hours,
    )
}
