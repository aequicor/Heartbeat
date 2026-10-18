package io.aequicor.heartbeat.feature.harness.impl.domain.workflow

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismEnd
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismOptions
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismOutcome
import io.aequicor.heartbeat.feature.harness.api.workflow.StepKey
import io.aequicor.heartbeat.feature.harness.api.workflow.StepPhase
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowOrganism
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStep
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorkflowOrganismConceptionTest {
    @Test
    fun `sleep immediately after conception keeps ownership until the organism answers`() = runTest {
        val fixture = ConceptionFixture()
        val running = async { fixture.execute() }
        runCurrent()
        assertEquals(StepPhase.Running, fixture.workflow.journal().steps.single().phase)
        assertEquals(1, fixture.organisms.host.conceived.size)
        fixture.organisms.host.isAwake = false
        advanceTimeBy(ORGANISM_POLL_INTERVAL * (ORGANISM_UNAVAILABLE_POLLS + 2))
        runCurrent()
        assertTrue(running.isActive)
        assertEquals(1, fixture.organisms.grants.pending().size)
        fixture.organisms.host.isAwake = true
        fixture.organisms.host.views[organismOf("s0")] = OrganismView.Answered("done")
        advanceTimeBy(ORGANISM_POLL_INTERVAL)
        runCurrent()
        assertEquals(OrganismOutcome.Answered("done"), running.await())
        assertTrue(fixture.organisms.grants.pending().isEmpty())
    }

    @Test
    fun `lost conception acknowledgement restores ownership while organic AI sleeps`() = runTest {
        val fixture = ConceptionFixture()
        val host = fixture.organisms.host
        val interrupted = object : OrganismHost by host {
            override suspend fun conceive(
                organism: WorkflowOrganism,
                goal: String,
                options: OrganismOptions,
                workspace: WorkspaceRef?,
            ): OrganismConception {
                host.conceive(organism, goal, options, workspace)
                throw WorkflowExecutionUnavailable()
            }
        }
        assertFailsWith<WorkflowExecutionUnavailable> { fixture.execute(interrupted) }
        assertEquals(StepPhase.Running, fixture.workflow.journal().steps.single().phase)
        host.isAwake = false
        val restored = async { fixture.execute() }
        advanceTimeBy(ORGANISM_POLL_INTERVAL * (ORGANISM_UNAVAILABLE_POLLS + 2))
        runCurrent()
        assertTrue(restored.isActive)
        assertEquals(1, fixture.organisms.grants.pending().size)
        restored.cancelAndJoin()
        assertEquals(1, host.conceived.size)
        assertTrue(host.aborted.isEmpty())
    }

    @Test
    fun `a saved conception attempt is not repeated when its organism is absent`() = runTest {
        val fixture = ConceptionFixture()
        val journal = fixture.workflow.journal()
        val step = WorkflowStep(StepKey("s0"), fixture.digest, organism = organismOf("s0"))
        journal.record(step)
        journal.record(step.copy(phase = StepPhase.Running))
        assertEquals(OrganismOutcome.Ended(OrganismEnd.Failed), fixture.execute())
        assertTrue(fixture.organisms.host.conceived.isEmpty())
        assertTrue(fixture.organisms.grants.pending().isEmpty())
    }

    @Test
    fun `legacy prepared step cannot be refused while its conceived organism sleeps`() = runTest {
        val fixture = ConceptionFixture()
        fixture.workflow.journal().record(
            WorkflowStep(StepKey("s0"), fixture.digest, organism = organismOf("s0")),
        )
        val host = fixture.organisms.host
        host.views[organismOf("s0")] = OrganismView.Developing
        host.isAwake = false
        val restored = async { fixture.execute() }
        advanceTimeBy(ORGANISM_POLL_INTERVAL * (ORGANISM_UNAVAILABLE_POLLS + 2))
        runCurrent()
        assertTrue(restored.isActive)
        assertEquals(1, fixture.organisms.grants.pending().size)
        host.isAwake = true
        host.views[organismOf("s0")] = OrganismView.Answered("legacy")
        advanceTimeBy(ORGANISM_POLL_INTERVAL)
        runCurrent()
        assertEquals(OrganismOutcome.Answered("legacy"), restored.await())
        assertTrue(host.conceived.isEmpty())
        assertTrue(fixture.organisms.grants.pending().isEmpty())
    }
}

private class ConceptionFixture {
    val workflow = WorkflowFixture()
    val organisms = OrganismTestPorts()
    private val options = OrganismOptions("Stage")
    val digest = TestDigest(Json.encodeToString(listOf<String?>("organism", "Ship it", "Stage", null, null)))

    suspend fun execute(host: OrganismHost = organisms.host): OrganismOutcome {
        val journal = workflow.journal()
        val ports = organisms.ports.copy(host = host)
        val driver = WorkflowOrganismDriver(journal.state.value, journal, ports, null, { true })
        return driver.execute(StepKey("s0"), digest, "Ship it", options)
    }
}
