package io.aequicor.heartbeat.feature.harness.impl.domain.workflow

import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismOptions
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowDefinition
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowFailure
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStepFailed
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorkflowOrganismBranchQuotaTest {
    @Test
    fun `a caught parallel failure returns the abandoned slot after asynchronous abort completes`() = runTest {
        verifyRecovery(retryUnconfirmed = false)
    }

    @Test
    fun `a caught parallel failure retries an unconfirmed abort at a pace before returning its slot`() = runTest {
        verifyRecovery(retryUnconfirmed = true)
    }

    private suspend fun TestScope.verifyRecovery(retryUnconfirmed: Boolean) {
        val workflow = WorkflowFixture()
        val organisms = OrganismTestPorts()
        val host = organisms.host
        host.abortLeaves = OrganismView.Aborting
        val failSibling = CompletableDeferred<Unit>()
        val first = organismOf("p0/p0/s0")
        val next = organismOf("s1")
        val running = async { engine(workflow, organisms).execute(recovering(failSibling)) }
        runCurrent()
        assertEquals(listOf(first), host.conceived.map { it.first })
        failSibling.complete(Unit)
        runCurrent()
        assertEquals(listOf(first), host.aborted)
        assertEquals(1, organisms.grants.pending().size)
        advanceTimeBy(ORGANISM_POLL_INTERVAL * 4)
        runCurrent()
        assertEquals(listOf(first), host.conceived.map { it.first })
        if (retryUnconfirmed) {
            host.views[first] = OrganismView.AbortUnconfirmed
            host.isAbortEffective = false
            advanceTimeBy(ORGANISM_POLL_INTERVAL * (ABORT_RETRY_POLLS * 2))
            runCurrent()
            assertEquals(3, host.aborted.size)
            assertEquals(listOf(first), host.conceived.map { it.first })
            host.abortLeaves = OrganismView.Aborted
            host.isAbortEffective = true
            advanceTimeBy(ORGANISM_POLL_INTERVAL * ABORT_RETRY_POLLS)
        } else {
            host.views[first] = OrganismView.Aborted
        }
        advanceTimeBy(ORGANISM_POLL_INTERVAL * 4)
        runCurrent()
        assertEquals(listOf(first, next), host.conceived.map { it.first })
        host.views[next] = OrganismView.Answered("done")
        advanceTimeBy(ORGANISM_POLL_INTERVAL * 2)
        runCurrent()
        running.await()
        assertTrue(organisms.grants.pending().isEmpty())
    }

    private suspend fun engine(workflow: WorkflowFixture, organisms: OrganismTestPorts): WorkflowEngine {
        val journal = workflow.journal()
        val owner = WorkflowOrganismDriver(
            journal.state.value,
            journal,
            organisms.ports,
            null,
            { true },
            currentCoroutineContext()[Job],
        )
        val agents = WorkflowAgentSteps { _, _, _, _ -> error("No helper expected") }
        return WorkflowEngine(journal.state.value, journal, agents, owner, workflow.clock, TestDigest)
    }

    private fun recovering(failSibling: CompletableDeferred<Unit>): WorkflowDefinition = {
        val failure = assertFailsWith<WorkflowStepFailed> {
            parallel(
                {
                    organism("first", OrganismOptions("first"))
                    JsonNull
                },
                {
                    failSibling.await()
                    error("Sibling failed")
                },
            )
        }
        assertEquals(WorkflowFailure.Error, failure.reason)
        organism("next", OrganismOptions("next"))
        JsonNull
    }
}
