package io.aequicor.heartbeat.feature.harness.impl.domain.workflow

import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsIntent
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismOptions
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismPermission
import io.aequicor.heartbeat.feature.harness.api.workflow.RunId
import io.aequicor.heartbeat.feature.harness.api.workflow.StepKey
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowDefinition
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRouting
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRun
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStatus
import io.aequicor.heartbeat.feature.harness.impl.data.RunDeliveryTestStore
import io.aequicor.heartbeat.feature.harness.impl.data.run.StoredWorkflowHelperJournal
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigins
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessExecutionLane
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessOriginContext
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.RuntimeTestCode
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Author code runs on the harness lane, whose dispatcher does not take virtual time, so these tests run the lane in
 * real time and wait only for observable events, each bounded by a timeout.
 */
class WorkflowOrganismAnswerTest {
    @Test
    fun `a cell request reaches the run and the answer of the user reaches that cell through the owner`() = onLane {
        val fixture = AnswerFixture()
        fixture.host.views[ORGANISM] = OrganismView.Developing(listOf(BUILDER))
        val running = async { fixture.execution.execute(fixture.run) { true } }
        fixture.requests.first { it == listOf(SHOWN) }
        assertTrue(fixture.host.decided.isEmpty())
        fixture.execution.answerOrganism(fixture.run.id, 0, STEP, "c1", ALLOW)
        fixture.host.decisions.first { it == 1 }
        assertEquals(listOf(Triple(ORGANISM, "c1", ALLOW)), fixture.host.decided)
        fixture.requests.first { it.isEmpty() }
        fixture.host.views[ORGANISM] = OrganismView.Answered("done")
        assertTrue(running.await())
        assertTrue(fixture.workflow.storage.load().single().status is WorkflowStatus.Completed)
        assertTrue(fixture.requests.value.isEmpty())
        fixture.execution.answerOrganism(fixture.run.id, 0, STEP, "c1", ALLOW)
        assertEquals(1, fixture.host.decided.size)
    }

    @Test
    fun `an answer for another generation run step or cell reaches nobody`() = onLane {
        val fixture = AnswerFixture()
        fixture.host.views[ORGANISM] = OrganismView.Developing(listOf(BUILDER))
        val running = async { fixture.execution.execute(fixture.run) { true } }
        fixture.requests.first { it == listOf(SHOWN) }
        val run = fixture.run.id
        fixture.execution.answerOrganism(run, 1, STEP, "c1", ALLOW)
        fixture.execution.answerOrganism(RunId("wf_other"), 0, STEP, "c1", ALLOW)
        fixture.execution.answerOrganism(run, 0, StepKey("s1"), "c1", ALLOW)
        fixture.execution.answerOrganism(run, 0, STEP, "c2", ALLOW)
        fixture.execution.answerOrganism(run, 0, STEP, "c1", ALLOW.copy(request = PermissionRequestId("other")))
        // Answers queue in order, so the exact one is the first the owner could have sent if any other got through.
        fixture.execution.answerOrganism(run, 0, STEP, "c1", ALLOW)
        fixture.host.decisions.first { it == 1 }
        assertEquals(listOf(Triple(ORGANISM, "c1", ALLOW)), fixture.host.decided)
        fixture.host.views[ORGANISM] = OrganismView.Answered("done")
        running.await()
    }

    private fun onLane(block: suspend CoroutineScope.() -> Unit) = runTest {
        withContext(Dispatchers.Default) { withTimeout(LANE_TIMEOUT) { block() } }
    }
}

private val LANE_TIMEOUT = 20.seconds
private val STEP = StepKey("s0")
private val ORGANISM = organismOf("s0")
private val BUILDER = OrganismCellRequest("c1", "Builder", askingToRun())
private val SHOWN = OrganismPermission(STEP, "c1", "Builder", BUILDER.request)
private val ALLOW = PermissionDecision(TurnId("turn"), PermissionRequestId("req"), PermissionOptionId("allow"))

private fun askingToRun() = PermissionRequest(
    PermissionRequestId("req"),
    TurnId("turn"),
    "Run a command?",
    listOf(
        PermissionOption(PermissionOptionId("allow"), "Allow"),
        PermissionOption(PermissionOptionId("deny"), "Deny"),
    ),
)

/** A run executing author code that owns one organism step, with the run machine's feedback recorded. */
private class AnswerFixture {
    val workflow = WorkflowFixture()
    val organisms = OrganismTestPorts()
    val host get() = organisms.host
    val requests = MutableStateFlow<List<OrganismPermission>>(emptyList())
    private val source = "organism workflow"
    val run: WorkflowRun = workflow.initial.copy(
        pinned = workflow.initial.pinned.copy(source = source, sourceSha = hash(source)),
        routing = WorkflowRouting(null, HelperHandoff()),
    )
    private val definition: WorkflowDefinition = {
        organism("Ship it", OrganismOptions("Stage"))
        JsonNull
    }
    private val scriptHost = object : HarnessScriptHost {
        override val isAvailable = true
        override suspend fun compile(request: HarnessCompilationRequest): HarnessCompilationResult =
            HarnessCompilationResult.Success(RuntimeTestCode(), emptyList())

        override suspend fun evaluate(
            code: CompiledHarnessCode,
            context: HarnessEvaluationContext,
        ): HarnessEvaluationResult {
            (context as HarnessEvaluationContext.Workflow).registration.register(definition)
            return HarnessEvaluationResult.Success
        }

        override suspend fun removeCached(harness: HarnessId, item: ItemId): Unit = error("Pinned code stays owned")
    }
    private val origins = object : HarnessCallOrigins {
        override fun current(): HarnessCallOrigin = HarnessCallOrigin()
        override fun context(origin: HarnessCallOrigin): HarnessOriginContext = HarnessOriginContext(origin)
    }
    private val access = object : WorkflowRunAccess {
        override suspend fun isAdmitted(run: WorkflowRun): Boolean = true
        override fun origin(run: WorkflowRun): HarnessCallOrigin = HarnessCallOrigin()
    }
    private val grants = StoredWorkflowHelperJournal(RunDeliveryTestStore(workflow.clock), workflow.clock)
    private val helpers = object : HelperAgents by SpawnTestHelpers(mutableListOf()) {
        override suspend fun finish(action: ActionId, payload: String) = Unit
    }
    val execution = WorkflowRunExecution(
        workflow.storage,
        WorkflowCodeExecutor(scriptHost, HarnessExecutionLane(Dispatchers.Default), origins, workflow.clock, ::hash),
        WorkflowAgentPorts(
            WorkflowHelperPorts(
                grants,
                WorkflowHelperResources(helpers, grants),
                helpers,
                SpawnTestBindings(mutableListOf()),
            ),
            organisms.ports,
        ),
        access,
        workflow.clock,
    ) {
        // What the run machine would hold: the newest snapshot the owner published for this run and generation.
        if (it is HarnessRunsIntent.Internal.OrganismPermissionsChanged && it.run == run.id && it.generation == 0L) {
            requests.value = it.requests
        }
    }
}

private fun hash(source: String): String = source.encodeUtf8().sha256().hex()
