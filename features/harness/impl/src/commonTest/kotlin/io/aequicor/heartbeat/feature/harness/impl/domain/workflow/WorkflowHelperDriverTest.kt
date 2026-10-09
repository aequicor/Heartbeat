package io.aequicor.heartbeat.feature.harness.impl.domain.workflow

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.harness.api.workflow.AgentOptions
import io.aequicor.heartbeat.feature.harness.api.workflow.StepKey
import io.aequicor.heartbeat.feature.harness.api.workflow.StepPhase
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStep
import io.aequicor.heartbeat.feature.harness.impl.data.RunDeliveryTestStore
import io.aequicor.heartbeat.feature.harness.impl.data.run.StoredWorkflowHelperJournal
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessHelperBinding
import io.aequicor.heartbeat.feature.harness.impl.domain.services.SpawnTestBindings
import io.aequicor.heartbeat.feature.harness.impl.domain.services.SpawnTestHelpers
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperAgents
import io.aequicor.heartbeat.feature.scheduler.api.HelperCancellation
import io.aequicor.heartbeat.feature.scheduler.api.HelperHandoff
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperOutcome
import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt
import io.aequicor.heartbeat.feature.scheduler.api.HelperReleaseResult
import io.aequicor.heartbeat.feature.scheduler.api.HelperResult
import io.aequicor.heartbeat.feature.scheduler.api.HelperSubmission
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

internal class WorkflowHelperDriverTest {
    @Test
    fun `fresh helper identities commit before prompt and terminal result before release`() = runTest {
        val fixture = WorkflowHelpersFixture()
        val progress = fixture.workflow.journal()
        fixture.beforePrompt = { prompt ->
            val durable = fixture.workflow.storage.load().single().steps.single()
            assertEquals(prompt.request, durable.request)
            assertEquals(fixture.base.helper, durable.helper)
            assertEquals(StepPhase.Prepared, durable.phase)
            assertEquals(fixture.base.helper, fixture.grants.pending().single().helper)
            assertEquals(fixture.base.helper, fixture.bindings.bound.single().helper)
        }
        fixture.base.release = {
            assertEquals(StepPhase.Completed, fixture.workflow.storage.load().single().steps.single().phase)
            HelperReleaseResult.Released
        }
        assertEquals("Done", fixture.driver(progress).execute(KEY, DIGEST, "Task", OPTIONS))
        assertTrue(fixture.grants.pending().isEmpty())
        assertEquals(1, fixture.base.creates)
        assertFalse(fixture.base.prompts.single().isRecovery)
    }

    @Test
    fun `null result and unconfirmed cancellation never send recovery or return capacity`() = runTest {
        val fixture = WorkflowHelpersFixture()
        val progress = fixture.workflow.journal()
        val old = fixture.restore(progress)
        fixture.cancel = { HelperCancellation.Unconfirmed(it) }
        val job = backgroundScope.async { fixture.driver(progress).execute(KEY, DIGEST, "Task", OPTIONS) }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(fixture.base.prompts.isEmpty())
        assertEquals(0, fixture.base.releases)
        assertEquals(old.request, progress.steps.single().request)
        job.cancelAndJoin()
        assertEquals(old.helper, fixture.grants.pending().single().helper)
    }

    @Test
    fun `confirmed recovery writes distinct request before send and retains original helper`() = runTest {
        val fixture = WorkflowHelpersFixture()
        val progress = fixture.workflow.journal()
        val old = fixture.restore(progress)
        fixture.beforePrompt = { prompt ->
            val durable = fixture.workflow.storage.load().single().steps.single()
            assertNotEquals(old.request, prompt.request)
            assertEquals(prompt.request, durable.request)
            assertEquals(1, durable.attempt)
            assertEquals(old.helper, durable.helper)
            assertTrue(prompt.isRecovery)
            assertTrue(prompt.text.endsWith("Task"))
        }
        assertEquals("Done", fixture.driver(progress).execute(KEY, DIGEST, "Task", OPTIONS))
        assertEquals(0, fixture.base.creates)
        assertEquals(1, fixture.base.prompts.size)
        assertEquals(1, fixture.cancellations)
    }

    @Test
    fun `previous terminal outcome is consumed without another prompt`() = runTest {
        val fixture = WorkflowHelpersFixture()
        val progress = fixture.workflow.journal()
        val old = fixture.restore(progress)
        fixture.results[old.request] = HelperResult(old.request, HelperOutcome.Completed, "Existing answer")
        assertEquals("Existing answer", fixture.driver(progress).execute(KEY, DIGEST, "Task", OPTIONS))
        assertTrue(fixture.base.prompts.isEmpty())
        assertEquals(0, fixture.cancellations)
        assertEquals(0, fixture.base.creates)
    }

    @Test
    fun `restored preprompt capacity is released before a fresh reservation creates a chat`() = runTest {
        val fixture = WorkflowHelpersFixture()
        val progress = fixture.workflow.journal()
        val grant = fixture.grant()
        fixture.grants.granted(grant)
        fixture.base.beforeCreate = {
            assertEquals(1, fixture.base.releases)
            assertNotEquals(grant.reservation, fixture.grants.pending().single().reservation)
        }
        assertEquals("Done", fixture.driver(progress).execute(KEY, DIGEST, "Task", OPTIONS))
        assertEquals(2, fixture.base.acquisitions.size)
        assertEquals(1, fixture.base.creates)
    }

    @Test
    fun `submission exception leaves captured request for reconciliation and never retries it`() = runTest {
        val fixture = WorkflowHelpersFixture()
        val progress = fixture.workflow.journal()
        fixture.beforePrompt = { throw IllegalStateException("private transport message") }
        val first = backgroundScope.async { fixture.driver(progress).execute(KEY, DIGEST, "Task", OPTIONS) }
        runCurrent()
        assertTrue(first.isCancelled)
        val previous = progress.steps.single()
        assertEquals(StepPhase.Prepared, previous.phase)
        assertEquals(1, fixture.grants.pending().size)
        fixture.beforePrompt = { assertNotEquals(previous.request, it.request) }
        assertEquals("Done", fixture.driver(progress).execute(KEY, DIGEST, "Task", OPTIONS))
        assertEquals(1, fixture.base.creates)
        assertTrue(fixture.base.prompts.single().isRecovery)
    }
}

private class WorkflowHelpersFixture {
    val workflow = WorkflowFixture()
    val grants = StoredWorkflowHelperJournal(RunDeliveryTestStore(workflow.clock), workflow.clock)
    val trace = mutableListOf<String>()
    val base = SpawnTestHelpers(trace)
    val bindings = SpawnTestBindings(trace)
    val results = mutableMapOf<RequestId, HelperResult>()
    var beforePrompt: suspend (HelperPrompt) -> Unit = {}
    var cancel: (RequestId) -> HelperCancellation = { HelperCancellation.NotSubmitted(it) }
    var cancellations = 0
    private var sequence = 0
    private val helpers = object : HelperAgents by base {
        override suspend fun prompt(helper: HelperId, prompt: HelperPrompt): HelperSubmission {
            beforePrompt(prompt)
            val accepted = base.prompt(helper, prompt)
            results[prompt.request] = HelperResult(prompt.request, HelperOutcome.Completed, "Done")
            return accepted
        }
        override suspend fun result(helper: HelperId, request: RequestId): HelperResult? = results[request]
        override suspend fun cancel(helper: HelperId, request: RequestId): HelperCancellation {
            cancellations++
            return cancel(request)
        }
    }
    private val resources = WorkflowHelperResources(helpers, grants)

    fun driver(progress: WorkflowRunJournal): WorkflowHelperDriver = WorkflowHelperDriver(
        workflow.initial,
        progress,
        WorkflowHelperPorts(grants, resources, helpers, bindings),
        null,
        HelperHandoff(),
        { true },
    ) { "id_${sequence++}" }

    fun grant(): WorkflowHelperGrant = WorkflowHelperGrant(
        ActionId("old-slot"),
        workflow.initial.id,
        workflow.initial.harness,
        KEY,
        DIGEST,
        null,
        RequestId("old-request"),
        RequestId("old-attach"),
    )

    suspend fun restore(progress: WorkflowRunJournal): WorkflowHelperGrant {
        val grant = grant()
        grants.granted(grant)
        val bound = grants.bind(grant.reservation, base.helper)
        bindings.bind(HarnessHelperBinding(base.helper, ActionId(grant.run.value), grant.harness, grant.attachRequest))
        progress.record(WorkflowStep(KEY, DIGEST, helper = base.helper, request = grant.request))
        return bound
    }
}

private val KEY = StepKey("s0")
private val DIGEST = "a".repeat(64)
private val OPTIONS = AgentOptions("Helper")
