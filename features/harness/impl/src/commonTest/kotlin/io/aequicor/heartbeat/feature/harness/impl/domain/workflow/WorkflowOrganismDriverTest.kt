package io.aequicor.heartbeat.feature.harness.impl.domain.workflow

import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismEnd
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismOptions
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismOutcome
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismRefusal
import io.aequicor.heartbeat.feature.harness.api.workflow.StepKey
import io.aequicor.heartbeat.feature.harness.api.workflow.StepPhase
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowDefinition
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowFailure
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowOrganism
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStep
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStepFailed
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration

class WorkflowOrganismDriverTest {
    private val options = OrganismOptions("Stage")
    private val seen = mutableListOf<OrganismOutcome>()
    private val definition: WorkflowDefinition = {
        seen += organism("Ship it", options)
        JsonNull
    }

    @Test
    fun `a step conceives its organism once and replay returns the answer without another`() = runTest {
        val fixture = OrganismFixture()
        val running = async { fixture.engine().execute(definition) }
        advanceBy(poll * 2)
        assertEquals(listOf(organismOf("s0") to "Ship it"), fixture.organisms.host.conceived)
        assertEquals(StepPhase.Running, fixture.step("s0").phase)
        assertEquals(organismOf("s0"), fixture.step("s0").organism)
        fixture.organisms.host.views[organismOf("s0")] = OrganismView.Answered("done")
        advanceBy(poll * 2)
        running.await()
        assertEquals(StepPhase.Completed, fixture.step("s0").phase)
        assertTrue(fixture.organisms.grants.pending().isEmpty())
        fixture.engine().execute(definition)
        assertEquals(1, fixture.organisms.host.conceived.size)
        assertEquals(listOf<OrganismOutcome>(OrganismOutcome.Answered("done"), OrganismOutcome.Answered("done")), seen)
    }

    @Test
    fun `a revoked driver leaves the organism running and the next owner reconciles it`() = runTest {
        val fixture = OrganismFixture()
        val first = launch { fixture.engine().execute(definition) }
        advanceBy(poll * 3)
        first.cancelAndJoin()
        assertEquals(1, fixture.organisms.grants.pending().size)
        assertTrue(fixture.organisms.host.aborted.isEmpty())
        assertEquals(StepPhase.Running, fixture.step("s0").phase)
        fixture.organisms.host.views[organismOf("s0")] = OrganismView.Answered("late")
        val second = async { fixture.engine().execute(definition) }
        advanceBy(poll)
        second.await()
        assertEquals(1, fixture.organisms.host.conceived.size)
        assertEquals(listOf<OrganismOutcome>(OrganismOutcome.Answered("late")), seen)
        assertTrue(fixture.organisms.grants.pending().isEmpty())
    }

    @Test
    fun `a restored step whose conception was accepted is not conceived again`() = runTest {
        val fixture = OrganismFixture()
        fixture.organisms.host.views[organismOf("s0")] = OrganismView.Developing
        val digest = TestDigest(Json.encodeToString(listOf<String?>("organism", "Ship it", "Stage", null, null)))
        fixture.workflow.journal().record(WorkflowStep(StepKey("s0"), digest, organism = organismOf("s0")))
        val running = async { fixture.engine().execute(definition) }
        advanceBy(poll * 2)
        assertEquals(StepPhase.Running, fixture.step("s0").phase)
        fixture.organisms.host.views[organismOf("s0")] = OrganismView.Answered("done")
        advanceBy(poll * 2)
        running.await()
        assertTrue(fixture.organisms.host.conceived.isEmpty())
        assertEquals(listOf<OrganismOutcome>(OrganismOutcome.Answered("done")), seen)
    }

    @Test
    fun `a branch cancelled while its driver owns the run stops the organism before its slot returns`() = runTest {
        val fixture = OrganismFixture()
        val driver = Job()
        val running = launch { fixture.engine(driver).execute(definition) }
        advanceBy(poll * 3)
        running.cancelAndJoin()
        assertEquals(listOf(organismOf("s0")), fixture.organisms.host.aborted)
        assertTrue(fixture.organisms.grants.pending().isEmpty())
        driver.cancel()
    }

    @Test
    fun `organic AI that never awakens refuses an organism that was never conceived`() = runTest {
        val fixture = OrganismFixture()
        fixture.organisms.host.isAwake = false
        val running = async { fixture.engine().execute(definition) }
        advanceBy(poll * (ORGANISM_UNAVAILABLE_POLLS + 2))
        running.await()
        assertEquals(listOf<OrganismOutcome>(OrganismOutcome.Refused(OrganismRefusal.Unavailable)), seen)
        assertTrue(fixture.organisms.host.conceived.isEmpty())
        assertTrue(fixture.organisms.grants.pending().isEmpty())
    }

    @Test
    fun `a rejected conception is a typed refusal and returns the slot`() = runTest {
        val fixture = OrganismFixture()
        fixture.organisms.host.conception = OrganismConception.Rejected
        val running = async { fixture.engine().execute(definition) }
        advanceBy(poll)
        running.await()
        assertEquals(listOf<OrganismOutcome>(OrganismOutcome.Refused(OrganismRefusal.Rejected)), seen)
        assertTrue(fixture.organisms.grants.pending().isEmpty())
    }

    @Test
    fun `an organism asleep after it was seen alive keeps waiting instead of being refused`() = runTest {
        val fixture = OrganismFixture()
        val running = async { fixture.engine().execute(definition) }
        advanceBy(poll * 3)
        fixture.organisms.host.isAwake = false
        advanceBy(poll * (ORGANISM_UNAVAILABLE_POLLS + 20))
        assertTrue(running.isActive)
        fixture.organisms.host.isAwake = true
        fixture.organisms.host.views[organismOf("s0")] = OrganismView.Answered("revived")
        advanceBy(poll * 2)
        running.await()
        assertEquals(listOf<OrganismOutcome>(OrganismOutcome.Answered("revived")), seen)
        assertEquals(1, fixture.organisms.host.conceived.size)
    }

    @Test
    fun `an organism seen alive that vanishes ends as failed and is never replaced`() = runTest {
        val fixture = OrganismFixture()
        val running = async { fixture.engine().execute(definition) }
        advanceBy(poll * 3)
        fixture.organisms.host.views.remove(organismOf("s0"))
        advanceBy(poll * 2)
        running.await()
        assertEquals(listOf<OrganismOutcome>(OrganismOutcome.Ended(OrganismEnd.Failed)), seen)
        assertEquals(1, fixture.organisms.host.conceived.size)
        assertTrue(fixture.organisms.grants.pending().isEmpty())
    }

    @Test
    fun `a stalled organism ends the step and is aborted before its slot returns`() = runTest {
        val fixture = OrganismFixture()
        val running = async { fixture.engine().execute(definition) }
        advanceBy(poll * 3)
        fixture.organisms.host.views[organismOf("s0")] = OrganismView.Stalled(OrganismEnd.NoModel)
        advanceBy(poll * 2)
        running.await()
        assertEquals(listOf<OrganismOutcome>(OrganismOutcome.Ended(OrganismEnd.NoModel)), seen)
        assertEquals(listOf(organismOf("s0")), fixture.organisms.host.aborted)
        assertTrue(fixture.organisms.grants.pending().isEmpty())
    }

    @Test
    fun `an abort that organic AI does not confirm keeps the slot and the call open`() = runTest {
        val fixture = OrganismFixture()
        fixture.organisms.host.isAbortEffective = false
        val running = async { fixture.engine().execute(definition) }
        advanceBy(poll * 3)
        fixture.organisms.host.views[organismOf("s0")] = OrganismView.Stalled(OrganismEnd.Failed)
        advanceBy(poll * 40)
        assertTrue(running.isActive)
        assertEquals(1, fixture.organisms.grants.pending().size)
        assertTrue(fixture.organisms.host.aborted.size > 1)
        fixture.organisms.host.views[organismOf("s0")] = OrganismView.Aborted
        advanceBy(poll * 2)
        running.await()
        assertTrue(fixture.organisms.grants.pending().isEmpty())
    }

    @Test
    fun `an organism that is only marked aborted keeps its slot until its sessions are released`() = runTest {
        val fixture = OrganismFixture()
        val running = async { fixture.engine().execute(definition) }
        advanceBy(poll * 3)
        fixture.organisms.host.views[organismOf("s0")] = OrganismView.Aborting
        advanceBy(poll * 40)
        // The step's outcome is known and recorded, but the lane waits for the release before it returns.
        assertEquals(StepPhase.Completed, fixture.step("s0").phase)
        assertTrue(running.isActive)
        assertEquals(1, fixture.organisms.grants.pending().size)
        assertTrue(fixture.organisms.host.aborted.isEmpty())
        fixture.organisms.host.views[organismOf("s0")] = OrganismView.Aborted
        advanceBy(poll * 2)
        running.await()
        assertEquals(listOf<OrganismOutcome>(OrganismOutcome.Ended(OrganismEnd.Aborted)), seen)
        assertTrue(fixture.organisms.grants.pending().isEmpty())
    }

    @Test
    fun `a stalled organism keeps its slot while the sessions of its abort are being released`() = runTest {
        val fixture = OrganismFixture()
        fixture.organisms.host.abortLeaves = OrganismView.Aborting
        val running = async { fixture.engine().execute(definition) }
        advanceBy(poll * 3)
        fixture.organisms.host.views[organismOf("s0")] = OrganismView.Stalled(OrganismEnd.Failed)
        advanceBy(poll * 40)
        assertTrue(running.isActive)
        assertEquals(1, fixture.organisms.host.aborted.size)
        assertEquals(1, fixture.organisms.grants.pending().size)
        fixture.organisms.host.views[organismOf("s0")] = OrganismView.Aborted
        advanceBy(poll * 2)
        running.await()
        assertTrue(fixture.organisms.grants.pending().isEmpty())
    }

    @Test
    fun `an abort that organic AI could not confirm is asked again at a pace until it is released`() = runTest {
        val fixture = OrganismFixture()
        val host = fixture.organisms.host
        host.isAbortEffective = false
        val running = async { fixture.engine().execute(definition) }
        advanceBy(poll * 3)
        host.views[organismOf("s0")] = OrganismView.AbortUnconfirmed
        advanceBy(poll * (ABORT_RETRY_POLLS * 2 + 5))
        assertTrue(running.isActive)
        assertEquals(1, fixture.organisms.grants.pending().size)
        // Not at every poll: the first ask and one more after each pause.
        assertEquals(3, host.aborted.size)
        host.isAbortEffective = true
        host.abortLeaves = OrganismView.Aborting
        advanceBy(poll * ABORT_RETRY_POLLS)
        assertEquals(OrganismView.Aborting, host.views[organismOf("s0")])
        assertTrue(running.isActive)
        host.views[organismOf("s0")] = OrganismView.Aborted
        advanceBy(poll * 2)
        running.await()
        assertTrue(fixture.organisms.grants.pending().isEmpty())
    }

    @Test
    fun `the second organism of a run waits for the quota of the first`() = runTest {
        assertEquals(1, HarnessLimits.ORGANISMS_PER_RUN)
        val fixture = OrganismFixture()
        val first = organismOf("p0/p0/s0")
        val second = organismOf("p0/p1/s0")
        val running = async {
            fixture.engine().execute {
                val branches = parallel(
                    { organism("first", options).let { JsonNull } },
                    { organism("second", options).let { JsonNull } },
                )
                JsonArray(branches)
            }
        }
        advanceBy(poll * 20)
        assertEquals(listOf(first), fixture.organisms.host.conceived.map { it.first })
        fixture.organisms.host.views[first] = OrganismView.Answered("one")
        advanceBy(poll * 4)
        assertEquals(listOf(first, second), fixture.organisms.host.conceived.map { it.first })
        fixture.organisms.host.views[second] = OrganismView.Answered("two")
        advanceBy(poll * 4)
        running.await()
        assertTrue(fixture.organisms.grants.pending().isEmpty())
    }

    @Test
    fun `a run that is no longer admitted conceives nothing and takes no slot`() = runTest {
        val fixture = OrganismFixture()
        fixture.isAdmitted = false
        assertFailsWith<WorkflowExecutionUnavailable> { fixture.engine().execute(definition) }
        assertTrue(fixture.organisms.host.conceived.isEmpty())
        assertTrue(fixture.organisms.grants.pending().isEmpty())
    }

    @Test
    fun `an answer beyond the result bound fails the step as an error after the organism ended`() = runTest {
        val fixture = OrganismFixture()
        fixture.organisms.host.views[organismOf("s0")] = OrganismView.Answered("x".repeat(HarnessLimits.RESULT_CHARS))
        val failure = assertFailsWith<WorkflowStepFailed> { fixture.engine().execute(definition) }
        assertEquals(WorkflowFailure.Error, failure.reason)
        assertEquals(StepPhase.Failed, fixture.step("s0").phase)
        assertTrue(fixture.organisms.grants.pending().isEmpty())
    }

    @Test
    fun `a step bound to another organism is divergent and conceives nothing`() = runTest {
        val fixture = OrganismFixture()
        val journal = fixture.workflow.journal()
        val digest = "a".repeat(64)
        journal.record(WorkflowStep(StepKey("s0"), digest, organism = WorkflowOrganism("wf-other")))
        val driver = WorkflowOrganismDriver(journal.state.value, journal, fixture.organisms.ports, null, { true })
        val failure = assertFailsWith<WorkflowStepFailed> { driver.execute(StepKey("s0"), digest, "Ship it", options) }
        assertEquals(WorkflowFailure.Diverged, failure.reason)
        assertTrue(fixture.organisms.host.conceived.isEmpty())
        assertTrue(fixture.organisms.grants.pending().isEmpty())
    }

    private suspend fun TestScope.advanceBy(duration: Duration) {
        advanceTimeBy(duration)
        runCurrent()
    }

    private val poll = ORGANISM_POLL_INTERVAL
}

private class OrganismFixture {
    val workflow = WorkflowFixture()
    val organisms = OrganismTestPorts()
    var isAdmitted = true

    suspend fun engine(driver: Job? = null): WorkflowEngine {
        val journal = workflow.journal()
        val agents = WorkflowAgentSteps { _, _, _, _ -> error("No helper expected") }
        val owner = WorkflowOrganismDriver(journal.state.value, journal, organisms.ports, null, { isAdmitted }, driver)
        return WorkflowEngine(journal.state.value, journal, agents, owner, workflow.clock, TestDigest)
    }

    suspend fun step(key: String): WorkflowStep = workflow.storage.load().single().steps.single { it.key.value == key }
}
