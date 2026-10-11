package io.aequicor.heartbeat.feature.harness.impl.data.authoring

import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.HarnessEnabled
import io.aequicor.heartbeat.feature.harness.api.HarnessEntry
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import io.aequicor.heartbeat.feature.harness.api.HarnessItem
import io.aequicor.heartbeat.feature.harness.api.HarnessOutput
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.api.HarnessTools
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsIntent
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsOutput
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsState
import io.aequicor.heartbeat.feature.harness.impl.data.RunDeliveryClock
import io.aequicor.heartbeat.feature.harness.impl.data.RunDeliveryTestStore
import io.aequicor.heartbeat.feature.harness.impl.data.run.HarnessWorkflowLaunches
import io.aequicor.heartbeat.feature.harness.impl.data.run.KeyValueHarnessRunStorage
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessMachine
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.HarnessLibraryClient
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.LibraryOutcome
import io.aequicor.heartbeat.feature.harness.impl.domain.content.HarnessActiveAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.harness
import io.aequicor.heartbeat.feature.harness.impl.domain.now
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigins
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessExecutionLane
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessRequestOrigins
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.MemoryHarnessRequestAncestry
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.dispatchSession
import io.aequicor.heartbeat.feature.harness.impl.domain.script.CompiledHarnessCode
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationResult
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationContext
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationResult
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessScriptHost
import io.aequicor.heartbeat.feature.harness.impl.domain.services.SpawnTestHelpers
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.HarnessRunsMachine
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.WorkflowCodeExecutor
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.WorkflowLibraryEpoch
import io.aequicor.heartbeat.feature.scheduler.api.HelperAgents
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerEnabled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class HarnessWorkflowStartTest {
    private val schema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { putJsonObject("screen") { put("type", "string") } }
        put("required", JsonArray(listOf(JsonPrimitive("screen"))))
    }
    private val workflow = HarnessItem.Workflow(ItemId("wf"), ItemName("screen"), "Screen", "workflow { it }", schema)
    private val owner = harness.copy(items = listOf(workflow))
    private val library = StubLibraryMachine(HarnessState.Ready(listOf(HarnessEntry(owner)), isRuntimeAvailable = true))
    private val client = SnapshotLibrary(library)
    private val runs = AdmittingRuns()
    private val helpers = StubHelpers()
    private val spec = HARNESS_WORKFLOW_SPECS.single { it.name == HarnessTools.WORKFLOW_START }

    @Test
    fun `an approved start pins the approved workflow and reports the run`() = runTest {
        val tools = tools()
        val approval = tools.approval(context(), spec, arguments())
        assertEquals("harness=${owner.id.value}@${owner.revision};workflow=${workflow.id.value}", approval.binding)
        val result = tools.execute(context(authorization = approval), HarnessTools.WORKFLOW_START, arguments())
        assertFalse(result.isError, result.text)
        assertTrue(result.text.startsWith("Started run wf_"), result.text)
        val start = assertIs<HarnessRunsIntent.Internal.StartRun>(runs.sent.single())
        assertEquals(workflow.source, start.run.pinned.source)
        assertEquals(dispatchSession, start.run.caller)
    }

    @Test
    fun `a start is refused without its approval, an accepted turn or a host for helpers`() = runTest {
        val tools = tools()
        val approval = tools.approval(context(), spec, arguments())
        val unapproved = tools.execute(context(), HarnessTools.WORKFLOW_START, arguments())
        assertTrue(unapproved.isError && unapproved.text.contains("changed while awaiting approval"), unapproved.text)
        val detached = tools.execute(
            context(authorization = approval, request = null),
            HarnessTools.WORKFLOW_START,
            arguments(),
        )
        assertTrue(detached.isError && detached.text.contains("accepted turn"), detached.text)
        helpers.isHostable = false
        val described = tools.approval(context(), spec, arguments()).description.orEmpty()
        assertTrue(described.startsWith("Helper chats cannot be hosted"), described)
        val unhosted = tools.execute(context(authorization = approval), HarnessTools.WORKFLOW_START, arguments())
        assertTrue(unhosted.isError && unhosted.text.contains("cannot be hosted"), unhosted.text)
        assertTrue(runs.sent.isEmpty())
    }

    @Test
    fun `input outside the schema is refused before admission`() = runTest {
        val tools = tools()
        val wrong = arguments(JsonPrimitive(5))
        val approval = tools.approval(context(), spec, wrong)
        val result = tools.execute(context(authorization = approval), HarnessTools.WORKFLOW_START, wrong)
        assertTrue(result.isError && result.text.contains("does not match the workflow schema"), result.text)
        assertTrue(runs.sent.isEmpty())
    }

    @Test
    fun `a revision committed after the approved snapshot is never started`() = runTest {
        val tools = tools()
        val approval = tools.approval(context(), spec, arguments())
        // The tools still read the approved snapshot while the library already holds replaced code.
        client.snapshot = library.state.value as HarnessState.Ready
        val replaced = owner.copy(revision = 1, items = listOf(workflow.copy(source = "workflow { JsonNull }")))
        library.state.value = HarnessState.Ready(listOf(HarnessEntry(replaced)), isRuntimeAvailable = true)
        val result = tools.execute(context(authorization = approval), HarnessTools.WORKFLOW_START, arguments())
        assertTrue(result.isError, result.text)
        // A stale approval is its own outcome: the agent asks again instead of reading it as a capacity failure.
        assertEquals("The workflow changed while awaiting approval; request it again", result.text)
        assertTrue(runs.sent.isEmpty())
    }

    private fun TestScope.tools(): HarnessWorkflowTools {
        val origins = object : HarnessCallOrigins {
            override fun current() = HarnessCallOrigin()
            override fun context(origin: HarnessCallOrigin): CoroutineContext = EmptyCoroutineContext
        }
        val launches = HarnessWorkflowLaunches(
            lazyOf(library),
            lazyOf(runs),
            RunDeliveryClock().let { KeyValueHarnessRunStorage(RunDeliveryTestStore(it), it, Dispatchers.Unconfined) },
            lazyOf(helpers),
            lazy {
                WorkflowCodeExecutor(AvailableHost, HarnessExecutionLane(Dispatchers.Default), origins, StartClock) {
                    it
                }
            },
            lazyOf(HarnessActiveAccess { _, _ -> listOf(owner) }),
            lazy { error("wakes need the scheduler") },
            lazy { error("wakes need the scheduler") },
            StartToggles,
            WorkflowLibraryEpoch("epoch"),
            StartClock,
            TestProfile(backgroundScope),
        )
        return HarnessWorkflowTools(
            StartToggles,
            lazyOf(client),
            lazyOf(launches),
            lazyOf(runs),
            lazyOf(helpers),
            lazyOf(HarnessRequestOrigins(MemoryHarnessRequestAncestry())),
        )
    }

    private fun context(authorization: AgentToolApproval? = null, request: RequestId? = RequestId("request")) =
        AgentToolContext(dispatchSession, WORKSPACE, TurnId("turn"), request, authorization = authorization)

    private fun arguments(screen: JsonPrimitive = JsonPrimitive("home")): JsonObject = buildJsonObject {
        put("harness", owner.name.value)
        put("workflow", workflow.name.value)
        putJsonObject("input") { put("screen", screen) }
    }
}

private val WORKSPACE = WorkspaceRef("project")

private object StartClock : Clock {
    override fun now(): Instant = now
}

private object StartToggles : FeatureToggles {
    @Suppress("UNCHECKED_CAST") // Only boolean flags are read.
    override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = when (toggle) {
        HarnessEnabled -> flowOf(true)
        SchedulerEnabled -> flowOf(false)
        else -> error("Unexpected toggle")
    } as Flow<T>

    override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = observe(toggle).first()
}

private class StubLibraryMachine(initial: HarnessState) : HarnessMachine {
    override val name = "harness"
    override val state = MutableStateFlow(initial)
    override val outputs = MutableSharedFlow<HarnessOutput>()

    override suspend fun send(intent: HarnessIntent): SendResult = error("The library is read only here")
}

/** Reads the machine like the real client, or a pinned older snapshot. */
private class SnapshotLibrary(private val machine: StubLibraryMachine) : HarnessLibraryClient {
    var snapshot: HarnessState.Ready? = null
    override val current: HarnessState.Ready? get() = snapshot ?: machine.state.value as? HarnessState.Ready

    override suspend fun ready(): HarnessState.Ready? = current

    override suspend fun submit(intent: HarnessIntent.Command): LibraryOutcome = error("Unused")

    override suspend fun sourceProject(workspace: WorkspaceRef?): WorkspaceRef? = workspace
}

/** Admits every start into its journal so the launch is confirmed. */
private class AdmittingRuns : HarnessRunsMachine {
    override val name = "harness_runs"
    override val state = MutableStateFlow<HarnessRunsState>(HarnessRunsState.Ready())
    override val outputs = MutableSharedFlow<HarnessRunsOutput>()
    val sent = mutableListOf<HarnessRunsIntent>()

    override suspend fun send(intent: HarnessRunsIntent): SendResult {
        sent += intent
        if (intent is HarnessRunsIntent.Internal.StartRun) {
            state.value = HarnessRunsState.Ready((state.value as HarnessRunsState.Ready).runs + intent.run)
        }
        return SendResult.Accepted
    }
}

private class StubHelpers : HelperAgents by SpawnTestHelpers(mutableListOf()) {
    var isHostable = true

    override suspend fun canHost(parent: SessionRef?): Boolean = isHostable
}

private object AvailableHost : HarnessScriptHost {
    override val isAvailable = true

    override suspend fun compile(request: HarnessCompilationRequest): HarnessCompilationResult = error("Not compiled")

    override suspend fun evaluate(code: CompiledHarnessCode, context: HarnessEvaluationContext) =
        HarnessEvaluationResult.Unsupported

    override suspend fun removeCached(harness: HarnessId, item: ItemId) = Unit
}

private class TestProfile(override val coroutineScope: CoroutineScope) : ScopeHandle {
    override val name = "profile"
    override val savedState: ScopeSavedState get() = error("Unused")
    override val isClosed = false

    override fun onClose(action: () -> Unit): DisposableHandle = DisposableHandle { }
}
