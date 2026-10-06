package io.aequicor.heartbeat.feature.harness.impl.data

import io.aequicor.heartbeat.feature.harness.api.HarnessActivationRequest
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.workflow.PinnedWorkflow
import io.aequicor.heartbeat.feature.harness.api.workflow.RunId
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowOrigin
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRun
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStatus
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessRunStorage
import io.aequicor.heartbeat.feature.harness.impl.domain.code
import io.aequicor.heartbeat.feature.harness.impl.domain.harness
import io.aequicor.heartbeat.feature.harness.impl.domain.now
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class UnavailableHarnessRuntimeTest {
    @Test
    fun `unavailable code never activates and stored running work blocks removal`() = runTest {
        val run = WorkflowRun(
            RunId("wf_one"), harness.id, ItemId("workflow"), PinnedWorkflow("", "a".repeat(64), 0, emptyMap()),
            JsonObject(emptyMap()), null, WorkflowOrigin.Script, now, now + 1.hours,
        )
        val storage = object : HarnessRunStorage {
            var records = listOf(run)
            override suspend fun load(): List<WorkflowRun> = records
            override suspend fun save(run: WorkflowRun, expectedGeneration: Long?): Boolean = error("unused")
        }
        val runtime = UnavailableHarnessRuntime(storage)
        assertFalse(runtime.isAvailable)
        assertFalse(runtime.activate(HarnessActivationRequest(harness, code, 1)))
        assertFalse(runtime.remove(harness))
        assertTrue(runtime.remove(harness.copy(id = HarnessId("other"))))
        storage.records = listOf(run.copy(status = WorkflowStatus.Completed(JsonNull), finishedAt = now))
        assertTrue(runtime.remove(harness))
    }
}
