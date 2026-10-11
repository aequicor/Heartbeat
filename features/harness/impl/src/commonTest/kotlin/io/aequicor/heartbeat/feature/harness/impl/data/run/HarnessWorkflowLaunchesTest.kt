package io.aequicor.heartbeat.feature.harness.impl.data.run

import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.harness.api.Harness
import io.aequicor.heartbeat.feature.harness.api.HarnessEntry
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import io.aequicor.heartbeat.feature.harness.api.HarnessItem
import io.aequicor.heartbeat.feature.harness.api.HarnessName
import io.aequicor.heartbeat.feature.harness.api.HarnessOutput
import io.aequicor.heartbeat.feature.harness.api.HarnessScope
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.api.ToolPolicySpec
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsIntent
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsOutput
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsState
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowOrigin
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRejection
import io.aequicor.heartbeat.feature.harness.impl.data.RunDeliveryClock
import io.aequicor.heartbeat.feature.harness.impl.data.RunDeliveryTestStore
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessMachine
import io.aequicor.heartbeat.feature.harness.impl.domain.content.HarnessActiveAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigins
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessExecutionLane
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessOriginContext
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessRequestOrigins
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.MemoryHarnessRequestAncestry
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.dispatchSession
import io.aequicor.heartbeat.feature.harness.impl.domain.script.CompiledHarnessCode
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationResult
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationContext
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationResult
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessScriptHost
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessSpawnFixture
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessWakeOperations
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessWakePort
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessWakeQuotas
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessWakeReceipt
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.HarnessRunsMachine
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.WorkflowCodeExecutor
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.WorkflowLibraryEpoch
import io.aequicor.heartbeat.feature.scheduler.api.EventOrigin
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerState
import io.aequicor.heartbeat.feature.scheduler.api.WakeRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

class HarnessWorkflowLaunchesTest {
    @Test
    fun `rejected start withdraws its persisted run and the caller wake scheduled after it`() = runTest {
        val fixture = LaunchFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        fixture.runs.rejection = WorkflowRejection.Capacity
        assertFailsWith<IllegalStateException> { fixture.launches.start(fixture.prepare()) }
        assertEquals(listOf(1), fixture.port.journalSizes)
        assertEquals(fixture.port.scheduled, fixture.port.cancelled)
        assertTrue(fixture.storage.load().isEmpty())
    }

    @Test
    fun `failure after the wake is scheduled still cancels it before Start is sent`() = runTest {
        val fixture = LaunchFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        // Admission changes while the wake is being scheduled: Start must never be sent.
        fixture.port.onSchedule = { fixture.active = emptyList() }
        assertFailsWith<IllegalStateException> { fixture.launches.start(fixture.prepare()) }
        assertEquals(1, fixture.port.scheduled.size)
        assertEquals(fixture.port.scheduled, fixture.port.cancelled)
        assertTrue(fixture.runs.sent.none { it is HarnessRunsIntent.Internal.StartRun })
        assertTrue(fixture.storage.load().isEmpty())
    }

    @Test
    fun `accepted start keeps the journal record its driver claims`() = runTest {
        val fixture = LaunchFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val result = fixture.launches.start(fixture.prepare())
        assertTrue(result.isConfirmed && result.hasWake)
        assertEquals(listOf(result.run), fixture.storage.load().map { it.id })
        assertTrue(fixture.port.cancelled.isEmpty())
    }
}

private class LaunchFixture(scope: CoroutineScope, dispatcher: CoroutineDispatcher) {
    val clock = RunDeliveryClock()
    val storage = KeyValueHarnessRunStorage(RunDeliveryTestStore(clock), clock, Dispatchers.Unconfined)
    val runs = LaunchRuns()
    val port = LaunchWakePort { storage.load().size }
    var active: List<Harness> = listOf(HARNESS)
    private val profile = object : ScopeHandle {
        override val name = "test"
        override val coroutineScope = scope
        override val isClosed = false
        override val savedState: ScopeSavedState get() = error("Not used")
        override fun onClose(action: () -> Unit): DisposableHandle = DisposableHandle {}
    }
    val launches = HarnessWorkflowLaunches(
        lazy { LaunchLibrary() },
        lazy { runs },
        storage,
        lazy { HarnessSpawnFixture(scope).helpers },
        lazy { WorkflowCodeExecutor(AvailableHost, HarnessExecutionLane(dispatcher), NoOrigins, clock) { it } },
        lazy { HarnessActiveAccess { _, _ -> active } },
        lazy {
            HarnessWakeOperations(
                scope,
                port,
                HarnessWakeQuotas(clock),
                HarnessRequestOrigins(MemoryHarnessRequestAncestry()),
                clock,
            )
        },
        lazy { port },
        LaunchToggles,
        WorkflowLibraryEpoch("epoch"),
        clock,
        profile,
    )

    suspend fun prepare(): WorkflowLaunch = launches.prepare(
        HARNESS.id,
        WORKFLOW.name,
        JsonObject(emptyMap()),
        dispatchSession,
        null,
        null,
        HarnessCallOrigin(),
        WorkflowOrigin.Script,
    )
}

private class LaunchRuns : HarnessRunsMachine {
    override val name = "harness_runs"
    override val state = MutableStateFlow<HarnessRunsState>(HarnessRunsState.Ready(emptyList()))
    override val outputs = MutableSharedFlow<HarnessRunsOutput>()
    val sent = mutableListOf<HarnessRunsIntent>()
    var rejection: WorkflowRejection? = null

    override suspend fun send(intent: HarnessRunsIntent): SendResult {
        sent += intent
        if (intent is HarnessRunsIntent.Internal.StartRun) {
            val rejected = rejection
            if (rejected == null) {
                state.value = HarnessRunsState.Ready(listOf(intent.run))
                outputs.emit(HarnessRunsOutput.Started(intent.requestId, intent.run.id))
            } else {
                outputs.emit(HarnessRunsOutput.Rejected(intent.requestId, rejected))
            }
        }
        return SendResult.Accepted
    }
}

private class LaunchLibrary : HarnessMachine {
    override val name = "harness"
    override val state = MutableStateFlow<HarnessState>(
        HarnessState.Ready(listOf(HarnessEntry(HARNESS)), isRuntimeAvailable = true),
    )
    override val outputs = MutableSharedFlow<HarnessOutput>()
    override suspend fun send(intent: HarnessIntent): SendResult = SendResult.Ignored
}

private class LaunchWakePort(private val journalSize: suspend () -> Int) : HarnessWakePort {
    val scheduled = mutableListOf<WakeRequest>()
    val cancelled = mutableListOf<WakeRequest>()
    val journalSizes = mutableListOf<Int>()
    var onSchedule: () -> Unit = {}
    override fun snapshot(): SchedulerState.Ready = SchedulerState.Ready()
    override suspend fun schedule(request: WakeRequest, at: Instant): HarnessWakeReceipt {
        journalSizes += journalSize()
        scheduled += request
        onSchedule()
        return HarnessWakeReceipt.Scheduled
    }
    override suspend fun cancel(request: WakeRequest, cause: EventOrigin?): Boolean {
        cancelled += request
        return true
    }
}

private object LaunchToggles : FeatureToggles {
    @Suppress("UNCHECKED_CAST") // Only boolean feature flags are read.
    override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = flowOf(true) as Flow<T>
    override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = observe(toggle).first()
}

private object NoOrigins : HarnessCallOrigins {
    override fun current(): HarnessCallOrigin = HarnessCallOrigin()
    override fun context(origin: HarnessCallOrigin): HarnessOriginContext = HarnessOriginContext(origin)
}

private object AvailableHost : HarnessScriptHost {
    override val isAvailable = true
    override suspend fun compile(request: HarnessCompilationRequest): HarnessCompilationResult = error("Not run")
    override suspend fun evaluate(
        code: CompiledHarnessCode,
        context: HarnessEvaluationContext,
    ): HarnessEvaluationResult = error("Not run")
    override suspend fun removeCached(harness: HarnessId, item: ItemId): Unit = error("Not run")
}

private val AT = Instant.fromEpochMilliseconds(1_000)
private val WORKFLOW = HarnessItem.Workflow(ItemId("flow"), ItemName("flow"), "", "workflow { input -> input }")
private val HARNESS = Harness(
    HarnessId("harness"), HarnessName("test"), "Test", "", HarnessScope.Profile, true,
    listOf(WORKFLOW), ToolPolicySpec(), null, 0, AT, AT,
)
