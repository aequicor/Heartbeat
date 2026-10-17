package io.aequicor.heartbeat.feature.harness.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.workflow.PinnedWorkflow
import io.aequicor.heartbeat.feature.harness.api.workflow.RunId
import io.aequicor.heartbeat.feature.harness.api.workflow.StepKey
import io.aequicor.heartbeat.feature.harness.api.workflow.StepPhase
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowFailure
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowOrganism
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowOrigin
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRun
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStep
import io.aequicor.heartbeat.feature.harness.impl.data.run.KeyValueHarnessRunStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class HarnessRunStorageOrganismTest {
    private val clock = RunDeliveryClock()
    private val storage = KeyValueHarnessRunStorage(RunDeliveryTestStore(clock), clock, Dispatchers.Unconfined)
    private val run = WorkflowRun(
        RunId("wf_one"), HarnessId("harness"), ItemId("workflow"),
        PinnedWorkflow("source", "a".repeat(64), 1, emptyMap()), JsonObject(emptyMap()), null,
        WorkflowOrigin.Script, clock.now(), clock.now() + 1.hours,
    )
    private val organism = WorkflowOrganism("wf-one")
    private val bound = WorkflowStep(StepKey("s0"), "c".repeat(64), organism = organism)

    @Test
    fun `an organism step binds at creation and progresses without changing its organism`() = runTest {
        assertTrue(storage.save(run, null))
        val running = bound.copy(phase = StepPhase.Running)
        val completed = running.copy(phase = StepPhase.Completed, result = JsonPrimitive("done"))
        for (step in listOf(bound, running, completed)) {
            assertTrue(storage.save(run.copy(steps = listOf(step)), 0))
        }
        assertEquals(listOf(completed), storage.find(run.id)?.steps)
    }

    @Test
    fun `an organism cannot be bound to a step that was recorded without one`() = runTest {
        assertTrue(storage.save(run, null))
        val blank = WorkflowStep(StepKey("s0"), "c".repeat(64))
        assertTrue(storage.save(run.copy(steps = listOf(blank)), 0))
        assertFalse(storage.save(run.copy(steps = listOf(blank.copy(organism = organism))), 0))
        assertFalse(storage.save(run.copy(steps = listOf(bound)), 0))
        assertEquals(listOf(blank), storage.find(run.id)?.steps)
    }

    @Test
    fun `a bound organism step is not rebound reopened or rewritten as a helper step`() = runTest {
        assertTrue(storage.save(run.copy(steps = listOf(bound)), null))
        val other = bound.copy(organism = WorkflowOrganism("wf-two"))
        val helper = WorkflowStep(StepKey("s0"), "c".repeat(64), request = RequestId("r"), attempt = 1)
        assertFalse(storage.save(run.copy(steps = listOf(other)), 0))
        assertFalse(storage.save(run.copy(steps = listOf(helper)), 0))
        val failed = bound.copy(phase = StepPhase.Failed, failure = WorkflowFailure.Interrupted)
        assertTrue(storage.save(run.copy(steps = listOf(failed)), 0))
        assertFalse(storage.save(run.copy(steps = listOf(bound)), 0))
        assertEquals(listOf(failed), storage.find(run.id)?.steps)
    }
}
