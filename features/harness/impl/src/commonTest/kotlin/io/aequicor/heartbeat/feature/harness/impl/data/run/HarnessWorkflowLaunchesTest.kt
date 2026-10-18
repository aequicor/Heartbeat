package io.aequicor.heartbeat.feature.harness.impl.data.run

import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.harness.api.Harness
import io.aequicor.heartbeat.feature.harness.api.HarnessActivationRequest
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
import io.aequicor.heartbeat.feature.harness.api.process.AttemptFailure
import io.aequicor.heartbeat.feature.harness.api.process.AttemptOutcome
import io.aequicor.heartbeat.feature.harness.api.process.ExecutorKind
import io.aequicor.heartbeat.feature.harness.api.process.ExecutorRef
import io.aequicor.heartbeat.feature.harness.api.process.ProcessCommand
import io.aequicor.heartbeat.feature.harness.api.process.ProcessEnd
import io.aequicor.heartbeat.feature.harness.api.process.ProcessId
import io.aequicor.heartbeat.feature.harness.api.process.ProcessLimits
import io.aequicor.heartbeat.feature.harness.api.process.ProcessReduction
import io.aequicor.heartbeat.feature.harness.api.process.ResultRef
import io.aequicor.heartbeat.feature.harness.api.process.StageName
import io.aequicor.heartbeat.feature.harness.api.process.workflowKey
import io.aequicor.heartbeat.feature.harness.api.script.ScriptWorkflowCancellation
import io.aequicor.heartbeat.feature.harness.api.script.ScriptWorkflowState
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsIntent
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsOutput
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsState
import io.aequicor.heartbeat.feature.harness.api.workflow.RunId
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowFailure
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowOrigin
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRejection
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRun
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStatus
import io.aequicor.heartbeat.feature.harness.impl.data.RunDeliveryClock
import io.aequicor.heartbeat.feature.harness.impl.data.RunDeliveryTestStore
import io.aequicor.heartbeat.feature.harness.impl.data.services.HarnessDataTestSecrets
import io.aequicor.heartbeat.feature.harness.impl.data.services.HarnessSessionAccess
import io.aequicor.heartbeat.feature.harness.impl.data.services.HarnessSessionRoute
import io.aequicor.heartbeat.feature.harness.impl.data.services.ProtectedHarnessData
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessMachine
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessRunStorage
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageUncertain
import io.aequicor.heartbeat.feature.harness.impl.domain.content.HarnessActiveAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigins
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessExecutionLane
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceTarget
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
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessAccessSnapshot
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessDeliveryPermit
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptData
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptProcesses
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessSpawnFixture
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessTarget
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
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

    @Test
    fun `ordinary start caller can cancel while detached submission is waiting`() = runTest {
        val fixture = LaunchFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val saving = CompletableDeferred<Unit>()
        val continueSave = CompletableDeferred<Unit>()
        fixture.beforeSave = {
            saving.complete(Unit)
            continueSave.await()
        }
        val start = async { fixture.launches.start(fixture.prepare()) }
        saving.await()
        start.cancel()
        runCurrent()
        assertTrue(start.isCompleted)
        continueSave.complete(Unit)
    }

    @Test
    fun `uncertain journal create is withdrawn before any wake or Start`() = runTest {
        val fixture = LaunchFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        // The record is committed, but the acknowledgement is lost.
        fixture.afterSave = { throw HarnessStorageUncertain() }
        assertFailsWith<IllegalStateException> { fixture.launches.start(fixture.prepare()) }
        assertTrue(fixture.storage.load().isEmpty())
        assertTrue(fixture.port.scheduled.isEmpty())
        assertTrue(fixture.runs.sent.none { it is HarnessRunsIntent.Internal.StartRun })
    }

    @Test
    fun `script workflow starts parentless and only while its instance stays current`() = runTest {
        val fixture = LaunchFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val sessions = ScriptSessions()
        val scripts = EngineHarnessScriptWorkflows(sessions, lazy { fixture.launches }, lazy { error("Not used") })
        val owner = HarnessInstanceTarget(HarnessActivationRequest(HARNESS, WORKFLOW, 0), ScriptInstance)
        val run = scripts.start(owner, WORKFLOW.name, JsonObject(emptyMap()), HarnessCallOrigin())
        val stored = fixture.storage.load().single()
        assertEquals(run, stored.id)
        assertEquals(null, stored.caller)
        assertEquals(WorkflowOrigin.Script, stored.origin)
        assertFailsWith<IllegalStateException> {
            scripts.start(owner, WORKFLOW.name, JsonObject(emptyMap()), HarnessCallOrigin(isHookRestricted = true))
        }
        sessions.isCurrent = false
        assertFailsWith<IllegalStateException> {
            scripts.start(owner, WORKFLOW.name, JsonObject(emptyMap()), HarnessCallOrigin())
        }
        assertEquals(1, fixture.storage.load().size)
    }
}

private class ScriptSessions : HarnessSessionAccess {
    var isCurrent = true
    override fun isCurrent(owner: HarnessInstanceTarget): Boolean = isCurrent
    override suspend fun permit(owner: HarnessInstanceTarget, target: HarnessTarget?): HarnessDeliveryPermit? =
        error("Not used")
    override fun snapshot(): HarnessAccessSnapshot = error("Not used")
    override suspend fun session(session: SessionRef): HarnessSessionRoute = error("Not used")
}

private object ScriptInstance : HarnessInstanceAccess {
    override val scope: CoroutineScope get() = error("Not used")
    override val dispatcher: CoroutineDispatcher get() = error("Not used")
    override val isActive = true
    override suspend fun awaitPublication(): Boolean = true
}

private class LaunchFixture(scope: CoroutineScope, dispatcher: CoroutineDispatcher) {
    val clock = RunDeliveryClock()
    private val stored = KeyValueHarnessRunStorage(RunDeliveryTestStore(clock), clock, Dispatchers.Unconfined)
    var beforeSave: suspend () -> Unit = {}
    var afterSave: () -> Unit = {}
    val storage = object : HarnessRunStorage by stored {
        override suspend fun save(run: WorkflowRun, expectedGeneration: Long?): Boolean {
            beforeSave()
            return stored.save(run, expectedGeneration).also { afterSave() }
        }
    }
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
        if (intent is HarnessRunsIntent.Internal.CancelStoredRun) {
            outputs.emit(HarnessRunsOutput.CancellationRequested(intent.requestId, intent.run.id))
        }
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
private val SECOND_WORKFLOW = WORKFLOW.copy(id = ItemId("second"), name = ItemName("second"))
private val SCRIPT = HarnessItem.Script(ItemId("script"), ItemName("script"), "", "")
private val HARNESS = Harness(
    HarnessId("harness"), HarnessName("test"), "Test", "", HarnessScope.Profile, true,
    listOf(WORKFLOW, SECOND_WORKFLOW, SCRIPT), ToolPolicySpec(), null, 0, AT, AT,
)

class ProcessWorkflowLaunchesTest {
    @Test
    fun `finished attempt releases workflow retention without another start`() = runTest {
        val fixture = LaunchFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val data = ProtectedHarnessData(HarnessDataTestSecrets())
        val owner = HarnessInstanceTarget(HarnessActivationRequest(HARNESS, SCRIPT, 0), ScriptInstance)
        val processes = HarnessScriptProcesses(
            HarnessScriptData(HARNESS.id, ScriptInstance, data),
            fixture.clock,
            data.processMutex,
            HARNESS.id,
            fixture.storage,
        )
        val key = processes.open(ProcessId("task")).workflowKey(StageName("verify"))
        val host = HarnessProcessWorkflows(
            ScriptSessions(),
            data,
            fixture.storage,
            lazy { fixture.launches },
            lazy { fixture.runs },
            fixture.clock,
        )
        val id = host.start(owner, key, WORKFLOW.name, JsonObject(emptyMap()), HarnessCallOrigin())
        val run = fixture.storage.find(id) ?: error("Missing workflow run")
        assertTrue(
            fixture.storage.save(
                run.copy(
                    status = WorkflowStatus.Completed(JsonPrimitive("done")),
                    finishedAt = run.startedAt,
                ),
                run.driverGeneration,
            ),
        )
        assertIs<ProcessReduction.Changed>(
            processes.apply(
                key.process,
                ProcessCommand.Finish(key.attempt, AttemptOutcome.Succeeded(ResultRef("test", "done"))),
            ),
        )
        assertTrue(fixture.storage.find(id)?.isProcessReleased == true)
        fixture.clock.instant += 31.days
        assertEquals(null, fixture.storage.find(id))
    }

    @Test
    fun `closed process cannot be removed while its workflow still runs`() = runTest {
        val fixture = LaunchFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val data = ProtectedHarnessData(HarnessDataTestSecrets())
        val owner = HarnessInstanceTarget(HarnessActivationRequest(HARNESS, SCRIPT, 0), ScriptInstance)
        val processes = HarnessScriptProcesses(
            HarnessScriptData(HARNESS.id, ScriptInstance, data),
            fixture.clock,
            data.processMutex,
            HARNESS.id,
            fixture.storage,
        )
        val key = processes.open(ProcessId("task")).workflowKey(StageName("verify"))
        val host = HarnessProcessWorkflows(
            ScriptSessions(),
            data,
            fixture.storage,
            lazy { fixture.launches },
            lazy { fixture.runs },
            fixture.clock,
        )
        val id = host.start(owner, key, WORKFLOW.name, JsonObject(emptyMap()), HarnessCallOrigin())
        assertIs<ProcessReduction.Changed>(processes.apply(key.process, ProcessCommand.Close(ProcessEnd.Completed)))
        assertFalse(processes.remove(key.process))
        val run = fixture.storage.find(id) ?: error("Missing workflow run")
        assertTrue(
            fixture.storage.save(
                run.copy(
                    status = WorkflowStatus.Failed(WorkflowFailure.Cancelled),
                    finishedAt = run.startedAt,
                ),
                run.driverGeneration,
            ),
        )
        host.releaseSettled(HARNESS.id)
        assertTrue(processes.remove(key.process))
    }

    @Test
    fun `running workflow still blocks removal after its attempt leaves bounded history`() = runTest {
        val fixture = LaunchFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val data = ProtectedHarnessData(HarnessDataTestSecrets())
        val owner = HarnessInstanceTarget(HarnessActivationRequest(HARNESS, SCRIPT, 0), ScriptInstance)
        val processes = HarnessScriptProcesses(
            HarnessScriptData(HARNESS.id, ScriptInstance, data),
            fixture.clock,
            data.processMutex,
            HARNESS.id,
            fixture.storage,
        )
        val key = processes.open(ProcessId("task")).workflowKey(StageName("verify"))
        val host = HarnessProcessWorkflows(
            ScriptSessions(),
            data,
            fixture.storage,
            lazy { fixture.launches },
            lazy { fixture.runs },
            fixture.clock,
        )
        val run = host.start(owner, key, WORKFLOW.name, JsonObject(emptyMap()), HarnessCallOrigin())
        assertIs<ProcessReduction.Changed>(
            processes.apply(
                key.process,
                ProcessCommand.Finish(key.attempt, AttemptOutcome.Failed(AttemptFailure.Cancelled)),
            ),
        )
        repeat(ProcessLimits.HISTORY + 1) {
            val started = assertIs<ProcessReduction.Changed>(
                processes.apply(
                    key.process,
                    ProcessCommand.Start(StageName("verify"), ExecutorRef(ExecutorKind.External, "next")),
                ),
            ).record
            assertIs<ProcessReduction.Changed>(
                processes.apply(
                    key.process,
                    ProcessCommand.Finish(started.inFlight!!.number, AttemptOutcome.Failed(AttemptFailure.Cancelled)),
                ),
            )
        }
        assertTrue(processes.get(key.process)!!.history.none { it.attempt.workflow?.run == run })
        assertIs<ProcessReduction.Changed>(processes.apply(key.process, ProcessCommand.Close(ProcessEnd.Completed)))
        assertFalse(processes.remove(key.process))
    }

    @Test
    fun `host retention decoder does not expose malformed process data`() = runTest {
        val fixture = LaunchFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val data = ProtectedHarnessData(HarnessDataTestSecrets())
        val raw = data.read(HARNESS.id, HarnessScriptProcesses.STORAGE_KEY) {}
        data.write(
            HARNESS.id,
            HarnessScriptProcesses.STORAGE_KEY,
            JsonPrimitive("private script input"),
            raw.revision,
        ) {}
        val host = HarnessProcessWorkflows(
            ScriptSessions(),
            data,
            fixture.storage,
            lazy { fixture.launches },
            lazy { fixture.runs },
            fixture.clock,
        )
        val failure = assertFailsWith<IllegalStateException> { host.releaseSettled(HARNESS.id) }
        assertEquals("Stored process is unreadable", failure.message)
        assertEquals(null, failure.cause)
    }

    @Test
    fun `closing an attempt waits for its reserved workflow dispatch`() = runTest {
        val fixture = LaunchFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val data = ProtectedHarnessData(HarnessDataTestSecrets())
        val owner = HarnessInstanceTarget(HarnessActivationRequest(HARNESS, SCRIPT, 0), ScriptInstance)
        val processes = HarnessScriptProcesses(
            HarnessScriptData(HARNESS.id, ScriptInstance, data),
            fixture.clock,
            data.processMutex,
        )
        val key = processes.open(ProcessId("task")).workflowKey(StageName("verify"))
        val host = HarnessProcessWorkflows(
            ScriptSessions(),
            data,
            fixture.storage,
            lazy { fixture.launches },
            lazy { fixture.runs },
            fixture.clock,
        )
        val saving = CompletableDeferred<Unit>()
        val continueSave = CompletableDeferred<Unit>()
        fixture.beforeSave = {
            saving.complete(Unit)
            continueSave.await()
        }
        val start = async { host.start(owner, key, WORKFLOW.name, JsonObject(emptyMap()), HarnessCallOrigin()) }
        saving.await()
        val close = async { processes.apply(key.process, ProcessCommand.Close(ProcessEnd.Completed)) }
        runCurrent()
        assertFalse(close.isCompleted)
        continueSave.complete(Unit)
        assertEquals(start.await(), fixture.storage.load().single().id)
        assertIs<ProcessReduction.Changed>(close.await())
    }

    @Test
    fun `a reserved attempt can be cancelled before its journal is created`() = runTest {
        val fixture = LaunchFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val data = ProtectedHarnessData(HarnessDataTestSecrets())
        val owner = HarnessInstanceTarget(HarnessActivationRequest(HARNESS, SCRIPT, 0), ScriptInstance)
        val processes = HarnessScriptProcesses(HarnessScriptData(HARNESS.id, ScriptInstance, data), fixture.clock)
        val key = processes.open(ProcessId("task")).workflowKey(StageName("verify"))
        val host = HarnessProcessWorkflows(
            ScriptSessions(),
            data,
            fixture.storage,
            lazy { fixture.launches },
            lazy { fixture.runs },
            fixture.clock,
        )
        fixture.beforeSave = { throw HarnessStorageUncertain() }
        assertFailsWith<IllegalStateException> {
            host.start(owner, key, WORKFLOW.name, JsonObject(emptyMap()), HarnessCallOrigin())
        }
        assertTrue(fixture.storage.load().isEmpty())
        assertEquals(ScriptWorkflowCancellation.Finished, host.cancel(owner, key))
        val observed = assertIs<ScriptWorkflowState.Found>(host.get(owner, key))
        assertEquals(WorkflowStatus.Failed(WorkflowFailure.Cancelled), observed.status)
        fixture.beforeSave = {}
        assertFailsWith<IllegalStateException> {
            host.start(owner, key, WORKFLOW.name, JsonObject(emptyMap()), HarnessCallOrigin())
        }
        assertTrue(fixture.runs.sent.none { it is HarnessRunsIntent.Internal.StartRun })
    }

    @Test
    fun `an open process attempt protects its completed run from age and count retention`() = runTest {
        val fixture = LaunchFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val data = ProtectedHarnessData(HarnessDataTestSecrets())
        val owner = HarnessInstanceTarget(HarnessActivationRequest(HARNESS, SCRIPT, 0), ScriptInstance)
        val processes = HarnessScriptProcesses(HarnessScriptData(HARNESS.id, ScriptInstance, data), fixture.clock)
        val key = processes.open(ProcessId("task")).workflowKey(StageName("verify"))
        val host = HarnessProcessWorkflows(
            ScriptSessions(),
            data,
            fixture.storage,
            lazy { fixture.launches },
            lazy { fixture.runs },
            fixture.clock,
        )
        val id = host.start(owner, key, WORKFLOW.name, JsonObject(emptyMap()), HarnessCallOrigin())
        val run = fixture.storage.load().single()
        val terminal = run.copy(status = WorkflowStatus.Completed(JsonPrimitive("done")), finishedAt = run.startedAt)
        assertTrue(fixture.storage.save(terminal, run.driverGeneration))
        repeat(25) { number ->
            assertTrue(fixture.storage.save(terminal.copy(id = RunId("wf_other_$number"), process = null), null))
        }
        assertEquals(terminal, fixture.storage.find(id))
        fixture.clock.instant += 31.days
        assertEquals(listOf(terminal), fixture.storage.load())
        assertEquals(id, host.start(owner, key, WORKFLOW.name, JsonObject(emptyMap()), HarnessCallOrigin()))
        assertEquals(1, fixture.runs.sent.count { it is HarnessRunsIntent.Internal.StartRun })
    }

    @Test
    fun `concurrent starts and a restarted service find one persisted executor`() = runTest {
        val fixture = LaunchFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val sessions = ScriptSessions()
        val data = ProtectedHarnessData(HarnessDataTestSecrets())
        val owner = HarnessInstanceTarget(HarnessActivationRequest(HARNESS, SCRIPT, 0), ScriptInstance)
        val processes = HarnessScriptProcesses(HarnessScriptData(HARNESS.id, ScriptInstance, data), fixture.clock)
        val key = processes.open(ProcessId("task")).workflowKey(StageName("verify"))
        fun host() = HarnessProcessWorkflows(
            sessions,
            data,
            fixture.storage,
            lazy { fixture.launches },
            lazy { fixture.runs },
            fixture.clock,
        )
        val first = host()
        val ids = listOf(
            async { first.start(owner, key, WORKFLOW.name, JsonObject(emptyMap()), HarnessCallOrigin()) },
            async { first.start(owner, key, WORKFLOW.name, JsonObject(emptyMap()), HarnessCallOrigin()) },
        ).awaitAll()
        assertEquals(ids.first(), ids.last())
        assertEquals(ids.first(), host().start(owner, key, WORKFLOW.name, JsonObject(emptyMap()), HarnessCallOrigin()))
        assertEquals(1, fixture.runs.sent.count { it is HarnessRunsIntent.Internal.StartRun })
        assertEquals(ids.first().value, processes.get(key.process)?.inFlight?.executor?.id)
        assertIs<ScriptWorkflowState.Found>(first.get(owner, key))
        val other = owner.copy(request = owner.request.copy(item = SCRIPT.copy(id = ItemId("foreign"))))
        assertEquals(ScriptWorkflowState.Missing, first.get(other, key))
        assertEquals(ScriptWorkflowCancellation.Missing, first.cancel(other, key))
        assertEquals(ScriptWorkflowCancellation.Requested, first.cancel(owner, key))
        assertEquals(WorkflowStatus.Running, assertIs<ScriptWorkflowState.Found>(first.get(owner, key)).status)
        sessions.isCurrent = false
        assertFailsWith<IllegalStateException> { first.get(owner, key) }
    }

    @Test
    fun `lost journal acknowledgement keeps the reservation and resumes the same run`() = runTest {
        val fixture = LaunchFixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val data = ProtectedHarnessData(HarnessDataTestSecrets())
        val owner = HarnessInstanceTarget(HarnessActivationRequest(HARNESS, SCRIPT, 0), ScriptInstance)
        val processes = HarnessScriptProcesses(HarnessScriptData(HARNESS.id, ScriptInstance, data), fixture.clock)
        val key = processes.open(ProcessId("task")).workflowKey(StageName("verify"))
        val host = HarnessProcessWorkflows(
            ScriptSessions(),
            data,
            fixture.storage,
            lazy { fixture.launches },
            lazy { fixture.runs },
            fixture.clock,
        )
        fixture.afterSave = { throw HarnessStorageUncertain() }
        assertFailsWith<IllegalStateException> {
            host.start(owner, key, WORKFLOW.name, JsonObject(emptyMap()), HarnessCallOrigin())
        }
        val saved = fixture.storage.load().single()
        assertTrue(fixture.runs.sent.none { it is HarnessRunsIntent.Internal.StartRun })
        fixture.afterSave = {}
        assertEquals(saved.id, host.start(owner, key, WORKFLOW.name, JsonObject(emptyMap()), HarnessCallOrigin()))
        assertEquals(1, fixture.storage.load().size)
        assertFailsWith<IllegalArgumentException> {
            host.start(
                owner,
                key,
                WORKFLOW.name,
                JsonObject(mapOf("changed" to JsonPrimitive(true))),
                HarnessCallOrigin(),
            )
        }
        assertFailsWith<IllegalStateException> {
            host.start(owner, key, SECOND_WORKFLOW.name, JsonObject(emptyMap()), HarnessCallOrigin())
        }
        assertEquals(1, fixture.runs.sent.count { it is HarnessRunsIntent.Internal.StartRun })
    }
}
