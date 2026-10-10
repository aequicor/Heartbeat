package io.aequicor.heartbeat.feature.harness.api.workflow

import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class WorkflowModelTest {
    @Test
    fun `journal round trips pinned code steps failures and results without persisted permissions`() {
        val run = workflowRun()
        val permission = PermissionRequest(
            PermissionRequestId("permission"),
            TurnId("turn"),
            SECRET,
            listOf(PermissionOption(PermissionOptionId("allow"), SECRET)),
        )
        for (status in listOf(WorkflowStatus.Running, WorkflowStatus.Completed(JsonPrimitive(SECRET))) +
            WorkflowFailure.entries.map(WorkflowStatus::Failed)) {
            val step = preparedStep().copy(phase = StepPhase.Completed, result = JsonPrimitive(SECRET))
            val snapshot = run.copy(
                status = status,
                steps = listOf(step),
                awaiting = mapOf(Caller to listOf(permission)),
                finishedAt = if (status == WorkflowStatus.Running) null else Now + 1.hours,
            )
            val encoded = Json.encodeToString(snapshot)
            assertFalse(encoded.contains("permission"))
            assertEquals(snapshot.copy(awaiting = emptyMap()), Json.decodeFromString<WorkflowRun>(encoded))
            assertFalse(snapshot.toString().contains(SECRET))
            assertFalse(snapshot.pinned.toString().contains(SECRET))
            assertFalse(step.toString().contains(SECRET))
            assertFalse(status.toString().contains(SECRET))
        }
    }

    @Test
    fun `workflow ids keys bounds and terminal invariants reject invalid persisted values`() {
        for (id in listOf("wf_", "foreign", "wf_Upper", "wf_" + "a".repeat(62))) {
            assertFailsWith<IllegalArgumentException> { RunId(id) }
        }
        for (key in listOf("", "step", "p0", "p0/s0/p1", "p-1/s0")) {
            assertFailsWith<IllegalArgumentException> { StepKey(key) }
        }
        assertEquals("wf_one", workflowRun().id.action.value)
        assertEquals("p0/p1/s2", StepKey("p0/p1/s2").value)
        assertFailsWith<IllegalArgumentException> { workflowRun().copy(deadline = Now + 7.hours) }
        assertFailsWith<IllegalArgumentException> { workflowRun().copy(steps = listOf(preparedStep(), preparedStep())) }
        assertFailsWith<IllegalArgumentException> {
            workflowRun().copy(
                status = WorkflowStatus.Failed(WorkflowFailure.Error),
            )
        }
        assertFailsWith<IllegalArgumentException> { preparedStep().copy(phase = StepPhase.Completed) }
        assertFailsWith<IllegalArgumentException> { preparedStep().copy(phase = StepPhase.Failed) }
        assertFailsWith<IllegalArgumentException> { preparedStep().copy(promptSha = "wrong") }
        assertFailsWith<IllegalArgumentException> {
            WorkflowStatus.Completed(JsonPrimitive("x".repeat(HarnessLimits.RESULT_CHARS)))
        }
    }

    @Test
    fun `only caller and user can inspect status including parentless script runs`() {
        val run = workflowRun()
        assertTrue(run.isVisibleTo(WorkflowViewer.User))
        assertTrue(run.isVisibleTo(WorkflowViewer.Agent(Caller)))
        assertFalse(run.isVisibleTo(WorkflowViewer.Agent(Caller.copy(nativeId = "foreign"))))
        assertFalse(run.copy(caller = null, origin = WorkflowOrigin.Script).isVisibleTo(WorkflowViewer.Agent(Caller)))
    }
}
