package io.aequicor.heartbeat.feature.harness.impl.domain.workflow

import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismOptions
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismOutcome
import io.aequicor.heartbeat.feature.harness.api.workflow.StepKey
import io.aequicor.heartbeat.feature.harness.api.workflow.StepPhase
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStep
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkflowAnsweredReleaseTest {
    @Test
    fun `an answer is journaled only after all native work stopped and unconfirmed release is retried`() = runTest {
        val workflow = WorkflowFixture()
        val organisms = OrganismTestPorts()
        val journal = workflow.journal()
        val id = organismOf("s0")
        organisms.host.isAbortEffective = false
        val driver = WorkflowOrganismDriver(journal.state.value, journal, organisms.ports, null, { true })
        val running = async { driver.execute(StepKey("s0"), DIGEST, "goal", OrganismOptions("stage")) }
        runCurrent()
        organisms.host.views[id] = OrganismView.Answered("done", OrganismRelease.Pending)
        advanceTimeBy(ORGANISM_POLL_INTERVAL * 2)
        runCurrent()
        assertTrue(running.isActive)
        assertEquals(StepPhase.Running, journal.steps.single().phase)
        assertTrue(organisms.host.aborted.isEmpty())
        organisms.host.views[id] = OrganismView.Answered("done", OrganismRelease.Unconfirmed)
        advanceTimeBy(ORGANISM_POLL_INTERVAL * 2)
        runCurrent()
        assertEquals(listOf(id), organisms.host.aborted)
        assertEquals(1, organisms.grants.pending().size)
        advanceTimeBy(ORGANISM_POLL_INTERVAL * ABORT_RETRY_POLLS)
        runCurrent()
        assertEquals(2, organisms.host.aborted.size)
        assertEquals(StepPhase.Running, journal.steps.single().phase)
        organisms.host.views[id] = OrganismView.Answered("done")
        advanceTimeBy(ORGANISM_POLL_INTERVAL)
        runCurrent()
        assertEquals(OrganismOutcome.Answered("done"), running.await())
        assertEquals(StepPhase.Completed, journal.steps.single().phase)
        assertTrue(organisms.grants.pending().isEmpty())
    }

    @Test
    fun `a recorded answer never settles an unconfirmed grant during replay cleanup`() = runTest {
        val workflow = WorkflowFixture()
        val organisms = OrganismTestPorts()
        val run = workflow.initial
        val id = organismOf("s0")
        val step = WorkflowStep(
            StepKey("s0"),
            DIGEST,
            organism = id,
            phase = StepPhase.Completed,
            result = OrganismOutcome.Answered("done").toJournal(),
        )
        val grant = WorkflowOrganismGrant(run.id, run.harness, step.key, DIGEST, run.caller, id)
        organisms.grants.acquire(grant)
        organisms.host.isAbortEffective = false
        organisms.host.views[id] = OrganismView.Answered("done", OrganismRelease.Unconfirmed)
        assertFalse(organisms.ports.resources.release(grant, step) { false })
        assertEquals(listOf(grant), organisms.grants.pending())
        organisms.host.views[id] = OrganismView.Answered("done")
        assertTrue(organisms.ports.resources.release(grant, step) { false })
        assertTrue(organisms.grants.pending().isEmpty())
    }

    private companion object {
        val DIGEST = "a".repeat(64)
    }
}
