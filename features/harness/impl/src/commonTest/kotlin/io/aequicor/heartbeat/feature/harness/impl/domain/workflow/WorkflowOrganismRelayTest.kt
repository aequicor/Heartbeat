package io.aequicor.heartbeat.feature.harness.impl.domain.workflow

import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismOptions
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismPermission
import io.aequicor.heartbeat.feature.harness.api.workflow.StepKey
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowDefinition
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration

class WorkflowOrganismRelayTest {
    private val definition: WorkflowDefinition = {
        organism("Ship it", OrganismOptions("Stage"))
        JsonNull
    }
    private val organism = organismOf("s0")
    private val builder = OrganismCellRequest("c1", "Builder", request("req-1"))
    private val shown = OrganismPermission(STEP, "c1", "Builder", builder.request)

    @Test
    fun `cells that ask the user are shown while the organism develops and hidden when it answers`() = runTest {
        val fixture = RelayFixture()
        fixture.host.views[organism] = OrganismView.Developing(listOf(builder))
        val running = async { fixture.engine().execute(definition) }
        advanceBy(poll * 2)
        assertEquals(listOf(shown), fixture.published.last())
        assertEquals(1, fixture.published.size)
        fixture.host.views[organism] = OrganismView.Developing()
        advanceBy(poll * 2)
        assertTrue(fixture.published.last().isEmpty())
        fixture.host.views[organism] = OrganismView.Answered("done")
        advanceBy(poll * 2)
        running.await()
        assertTrue(fixture.published.last().isEmpty())
        assertTrue(fixture.host.decided.isEmpty())
    }

    @Test
    fun `the answer of the user reaches the cell that asked once and the request is hidden meanwhile`() = runTest {
        val fixture = RelayFixture()
        fixture.host.views[organism] = OrganismView.Developing(listOf(builder))
        val running = async { fixture.engine().execute(definition) }
        advanceBy(poll * 2)
        assertTrue(fixture.requests.answer(STEP, "c1", ALLOW))
        advanceBy(poll * 2)
        assertEquals(listOf(Triple(organism, "c1", ALLOW)), fixture.host.decided)
        assertTrue(fixture.published.last().isEmpty())
        assertFalse(fixture.requests.answer(STEP, "c1", ALLOW))
        advanceBy(poll * 4)
        assertEquals(1, fixture.host.decided.size)
        fixture.host.views[organism] = OrganismView.Answered("done")
        advanceBy(poll * 2)
        running.await()
    }

    @Test
    fun `accepted intent without delivery restores the live request for explicit retry`() = runTest {
        val fixture = RelayFixture()
        // Acceptance does not clear the live permission, just like an asynchronous Respond that failed.
        fixture.host.views[organism] = OrganismView.Developing(listOf(builder))
        val running = launch { fixture.engine().execute(definition) }
        advanceBy(poll * 2)
        assertTrue(fixture.requests.answer(STEP, "c1", ALLOW))
        advanceBy(poll * 2)
        assertTrue(fixture.published.last().isEmpty())
        advanceBy(poll * ORGANISM_ANSWER_RETRY_POLLS)
        assertEquals(listOf(shown), fixture.published.last())
        assertEquals(1, fixture.host.decided.size)
        assertTrue(fixture.requests.answer(STEP, "c1", ALLOW))
        advanceBy(poll * 2)
        assertEquals(2, fixture.host.decided.size)
        fixture.host.views[organism] = OrganismView.Developing()
        advanceBy(poll * (ORGANISM_ANSWER_RETRY_POLLS + 1))
        assertTrue(fixture.published.last().isEmpty())
        assertFalse(fixture.requests.answer(STEP, "c1", ALLOW))
        running.cancelAndJoin()
    }

    @Test
    fun `a request that organic AI did not take stays shown to be answered again`() = runTest {
        val fixture = RelayFixture()
        fixture.host.isDecisionAccepted = false
        fixture.host.views[organism] = OrganismView.Developing(listOf(builder))
        val running = launch { fixture.engine().execute(definition) }
        advanceBy(poll * 2)
        assertTrue(fixture.requests.answer(STEP, "c1", ALLOW))
        advanceBy(poll * 2)
        assertEquals(1, fixture.host.decided.size)
        assertEquals(listOf(shown), fixture.published.last())
        assertTrue(fixture.requests.answer(STEP, "c1", ALLOW))
        advanceBy(poll * 2)
        assertEquals(2, fixture.host.decided.size)
        running.cancelAndJoin()
    }

    @Test
    fun `an answer to a cell that has stopped asking is dropped before it is sent`() = runTest {
        val fixture = RelayFixture()
        fixture.host.views[organism] = OrganismView.Developing(listOf(builder))
        val running = launch { fixture.engine().execute(definition) }
        advanceBy(poll * 2)
        assertTrue(fixture.requests.answer(STEP, "c1", ALLOW))
        fixture.host.views[organism] = OrganismView.Developing(listOf(builder.copy(cell = "c2")))
        advanceBy(poll * 2)
        assertTrue(fixture.host.decided.isEmpty())
        assertEquals(listOf(shown.copy(cell = "c2")), fixture.published.last())
        running.cancelAndJoin()
    }

    @Test
    fun `a cancelled lane and a sleeping machine leave no request shown`() = runTest {
        val fixture = RelayFixture()
        fixture.host.views[organism] = OrganismView.Developing(listOf(builder))
        val running = launch { fixture.engine().execute(definition) }
        advanceBy(poll * 2)
        assertEquals(listOf(shown), fixture.published.last())
        fixture.host.isAwake = false
        advanceBy(poll * 2)
        assertTrue(fixture.published.last().isEmpty())
        fixture.host.isAwake = true
        advanceBy(poll * 2)
        assertEquals(listOf(shown), fixture.published.last())
        running.cancelAndJoin()
        assertTrue(fixture.published.last().isEmpty())
        assertFalse(fixture.requests.answer(STEP, "c1", ALLOW))
    }

    @Test
    fun `abort hides live requests but keeps the slot until native work stops`() = runTest {
        val fixture = RelayFixture()
        fixture.host.views[organism] = OrganismView.Developing(listOf(builder))
        val running = async { fixture.engine().execute(definition) }
        advanceBy(poll * 2)
        assertEquals(listOf(shown), fixture.published.last())
        assertTrue(fixture.requests.answer(STEP, "c1", ALLOW))
        fixture.host.views[organism] = OrganismView.Aborting
        advanceBy(poll * 2)
        assertTrue(fixture.published.last().isEmpty())
        assertTrue(fixture.host.decided.isEmpty())
        assertFalse(fixture.requests.answer(STEP, "c1", ALLOW))
        assertTrue(running.isActive)
        assertEquals(1, fixture.organisms.grants.pending().size)
        fixture.host.views[organism] = OrganismView.Aborted
        advanceBy(poll * 2)
        running.await()
        assertTrue(fixture.organisms.grants.pending().isEmpty())
    }

    @Test
    fun `a lane that is no longer admitted sends no answer`() = runTest {
        val fixture = RelayFixture()
        fixture.host.views[organism] = OrganismView.Developing(listOf(builder))
        val running = launch { fixture.engine().execute(definition) }
        advanceBy(poll * 2)
        assertTrue(fixture.requests.answer(STEP, "c1", ALLOW))
        fixture.isAdmitted = false
        advanceBy(poll * 2)
        assertTrue(fixture.host.decided.isEmpty())
        running.cancelAndJoin()
    }

    private suspend fun TestScope.advanceBy(duration: Duration) {
        advanceTimeBy(duration)
        runCurrent()
    }

    private val poll = ORGANISM_POLL_INTERVAL

    private companion object {
        val STEP = StepKey("s0")
        val ALLOW = PermissionDecision(TurnId("turn"), PermissionRequestId("req-1"), PermissionOptionId("allow"))

        fun request(id: String) = PermissionRequest(
            PermissionRequestId(id),
            TurnId("turn"),
            "Run a command?",
            listOf(
                PermissionOption(PermissionOptionId("allow"), "Allow"),
                PermissionOption(PermissionOptionId("deny"), "Deny"),
            ),
        )
    }
}

private class RelayFixture {
    val workflow = WorkflowFixture()
    val organisms = OrganismTestPorts()
    val host get() = organisms.host
    val published = mutableListOf<List<OrganismPermission>>()
    val requests = WorkflowOrganismRequests { published += it }
    var isAdmitted = true

    suspend fun engine(driver: Job? = null): WorkflowEngine {
        val journal = workflow.journal()
        val agents = WorkflowAgentSteps { _, _, _, _ -> error("No helper expected") }
        val owner = WorkflowOrganismDriver(
            journal.state.value,
            journal,
            organisms.ports,
            null,
            { isAdmitted },
            driver,
            requests,
        )
        return WorkflowEngine(journal.state.value, journal, agents, owner, workflow.clock, TestDigest)
    }
}
