package io.aequicor.heartbeat.feature.harness.impl.domain.workflow

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.workflow.AgentOptions
import io.aequicor.heartbeat.feature.harness.api.workflow.StepKey
import io.aequicor.heartbeat.feature.harness.api.workflow.StepPhase
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowFailure
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRun
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStatus
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStep
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStepFailed
import io.aequicor.heartbeat.feature.harness.impl.data.RunDeliveryTestStore
import io.aequicor.heartbeat.feature.harness.impl.data.run.StoredWorkflowHelperJournal
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigins
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessExecutionLane
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessOriginContext
import io.aequicor.heartbeat.feature.harness.impl.domain.script.CompiledHarnessCode
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationResult
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationContext
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationResult
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessScriptHost
import io.aequicor.heartbeat.feature.harness.impl.domain.services.SpawnTestBindings
import io.aequicor.heartbeat.feature.harness.impl.domain.services.SpawnTestHelpers
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperAgents
import io.aequicor.heartbeat.feature.scheduler.api.HelperHandoff
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A paused run can outlive its helpers' ownership proofs; resume and cancellation must still end it. */
internal class WorkflowHelperProofExpiryTest {
    private val workflow = WorkflowFixture()
    private val grants = StoredWorkflowHelperJournal(RunDeliveryTestStore(workflow.clock), workflow.clock)
    private val base = SpawnTestHelpers(mutableListOf())
    private val bindings = SpawnTestBindings(mutableListOf())
    private val finished = mutableListOf<ActionId>()
    private val helpers = object : HelperAgents by base {
        override suspend fun finish(action: ActionId, payload: String) {
            finished += action
        }
    }
    private val resources = WorkflowHelperResources(helpers, grants)
    private val ports = WorkflowHelperPorts(grants, resources, helpers, bindings)
    private val expired =
        WorkflowStep(KEY, DIGEST, HelperId("h0"), request = RequestId("r0"), phase = StepPhase.Running)

    @Test
    fun `resumed step with an expired proof fails as interrupted without prompting`() = runTest {
        val progress = workflow.journal()
        progress.record(expired)
        var sequence = 0
        val driver = WorkflowHelperDriver(
            workflow.initial,
            progress,
            ports,
            null,
            HelperHandoff(),
            { true },
        ) { "id_${sequence++}" }
        val failure = assertFailsWith<WorkflowStepFailed> {
            driver.execute(KEY, DIGEST, "Task", AgentOptions("Helper"))
        }
        assertEquals(WorkflowFailure.Interrupted, failure.reason)
        val step = workflow.storage.load().single().steps.single()
        assertEquals(StepPhase.Failed, step.phase)
        assertEquals(WorkflowFailure.Interrupted, step.failure)
        assertTrue(base.prompts.isEmpty())
        assertEquals(0, base.creates)
        assertTrue(grants.pending().isEmpty())
    }

    @Test
    fun `cancelled run whose helper proof expired still becomes terminal`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val run = workflow.initial.copy(cancellation = WorkflowFailure.Cancelled, steps = listOf(expired))
        assertTrue(workflow.storage.save(run, null))
        val execution = WorkflowRunExecution(
            workflow.storage,
            WorkflowCodeExecutor(NoCode, HarnessExecutionLane(dispatcher), Neutral, workflow.clock) { it },
            ports,
            OrganismTestPorts().ports,
            Admitted,
            workflow.clock,
        ) {}
        val owner = SupervisorJob()
        val drivers = ProfileWorkflowDrivers(CoroutineScope(owner + dispatcher), execution) { _, _ -> true }
        drivers.drive(run)
        advanceUntilIdle()
        assertEquals(WorkflowStatus.Failed(WorkflowFailure.Cancelled), workflow.storage.load().single().status)
        assertEquals(listOf(run.id.action), finished)
        assertTrue(base.acquisitions.isEmpty())
        owner.cancel()
    }
}

private object Admitted : WorkflowRunAccess {
    override suspend fun isAdmitted(run: WorkflowRun): Boolean = true
    override fun origin(run: WorkflowRun): HarnessCallOrigin = HarnessCallOrigin()
}

private object Neutral : HarnessCallOrigins {
    override fun current(): HarnessCallOrigin = HarnessCallOrigin()
    override fun context(origin: HarnessCallOrigin): HarnessOriginContext = HarnessOriginContext(origin)
}

private object NoCode : HarnessScriptHost {
    override val isAvailable = true
    override suspend fun compile(request: HarnessCompilationRequest): HarnessCompilationResult =
        error("No author code runs in these lifecycle paths")
    override suspend fun evaluate(
        code: CompiledHarnessCode,
        context: HarnessEvaluationContext,
    ): HarnessEvaluationResult = error("No author code runs in these lifecycle paths")
    override suspend fun removeCached(harness: HarnessId, item: ItemId): Unit = error("Pinned code stays owned")
}

private val KEY = StepKey("s0")
private val DIGEST = "a".repeat(64)
