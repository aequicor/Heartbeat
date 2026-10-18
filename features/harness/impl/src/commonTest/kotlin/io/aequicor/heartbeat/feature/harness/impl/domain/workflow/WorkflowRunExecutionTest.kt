package io.aequicor.heartbeat.feature.harness.impl.domain.workflow

import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsEffect
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsIntent
import io.aequicor.heartbeat.feature.harness.api.workflow.RunId
import io.aequicor.heartbeat.feature.harness.api.workflow.StepKey
import io.aequicor.heartbeat.feature.harness.api.workflow.StepPhase
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowFailure
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRun
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStatus
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStep
import io.aequicor.heartbeat.feature.harness.impl.data.RunDeliveryTestStore
import io.aequicor.heartbeat.feature.harness.impl.data.run.KeyValueHarnessRunStorage
import io.aequicor.heartbeat.feature.harness.impl.data.run.StoredWorkflowHelperJournal
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessRunStorage
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessStorageUncertain
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
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessHelperBinding
import io.aequicor.heartbeat.feature.harness.impl.domain.services.SpawnTestBindings
import io.aequicor.heartbeat.feature.harness.impl.domain.services.SpawnTestHelpers
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperAgents
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperReleaseResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class WorkflowRunExecutionTest {
    @Test
    fun `terminal run retires its driver slot and follower`() = runTest {
        val fixture = ExecutionFixture(StandardTestDispatcher(testScheduler))
        val run = fixture.workflow.initial.copy(cancellation = WorkflowFailure.Cancelled)
        assertTrue(fixture.workflow.storage.save(run, null))
        val owner = SupervisorJob()
        val drivers = fixture.drivers(CoroutineScope(owner + StandardTestDispatcher(testScheduler)))
        drivers.drive(run)
        assertEquals(setOf(run.id), drivers.activeRuns)
        advanceUntilIdle()
        assertTrue(drivers.activeRuns.isEmpty())
        assertTrue(owner.children.none { it.isActive })
        assertTrue(drivers.isPaused(run.id, run.driverGeneration))
        assertEquals(listOf(run.id.action), fixture.finished)
        assertEquals(WorkflowStatus.Failed(WorkflowFailure.Cancelled), fixture.workflow.storage.load().single().status)
        owner.cancel()
    }

    @Test
    fun `terminal result reaches the outbox once across drives and restores`() = runTest {
        val fixture = ExecutionFixture(StandardTestDispatcher(testScheduler))
        val terminal = fixture.workflow.initial.copy(
            status = WorkflowStatus.Failed(WorkflowFailure.Cancelled),
            finishedAt = fixture.workflow.clock.now(),
        )
        assertTrue(fixture.workflow.storage.save(terminal, null))
        fixture.execution.publish(terminal)
        fixture.execution.publish(terminal)
        assertEquals(listOf(terminal.id.action), fixture.finished)
        // A new profile lifetime reads the durable mark and restores without publishing again.
        val restarted = KeyValueHarnessRunStorage(
            fixture.workflow.store,
            fixture.workflow.clock,
            Dispatchers.Unconfined,
        )
        val owner = SupervisorJob()
        val effects = HarnessRunsEffects(
            restarted,
            fixture.drivers(CoroutineScope(owner + StandardTestDispatcher(testScheduler))),
            fixture.execution(restarted),
            fixture.workflow.clock,
        )
        val machine = RecordingRunsScope()
        effects.handle(HarnessRunsEffect.Restore, machine)
        assertEquals(listOf(terminal.id.action), fixture.finished)
        assertTrue(machine.sent.single() is HarnessRunsIntent.Internal.Restored)
        owner.cancel()
    }

    @Test
    fun `uncertain outbox mark retries only the mark without publishing the result again`() = runTest {
        val fixture = ExecutionFixture(StandardTestDispatcher(testScheduler))
        val terminal = fixture.workflow.initial.copy(
            status = WorkflowStatus.Failed(WorkflowFailure.Cancelled),
            finishedAt = fixture.workflow.clock.now(),
        )
        assertTrue(fixture.workflow.storage.save(terminal, null))
        var marks = 0
        val flaky = object : HarnessRunStorage by fixture.workflow.storage {
            override suspend fun markPublished(id: RunId) {
                marks++
                if (marks == 1) throw HarnessStorageUncertain()
                fixture.workflow.storage.markPublished(id)
            }
        }
        val execution = fixture.execution(flaky)
        assertFailsWith<HarnessStorageUncertain> { execution.publish(terminal) }
        execution.publish(terminal)
        assertEquals(listOf(terminal.id.action), fixture.finished)
        assertEquals(2, marks)
        assertTrue(fixture.workflow.storage.isPublished(terminal.id))
    }

    @Test
    fun `claim checkpoints every newer driver generation and fences older owners`() = runTest {
        val fixture = ExecutionFixture(StandardTestDispatcher(testScheduler))
        val run = fixture.workflow.initial
        assertTrue(fixture.workflow.storage.save(run, null))
        val newer = run.copy(driverGeneration = run.driverGeneration + 2)
        assertFalse(fixture.execution.pause(newer))
        assertEquals(newer.driverGeneration, fixture.workflow.storage.find(run.id)?.driverGeneration)
        val older = run.copy(driverGeneration = run.driverGeneration + 1)
        assertFailsWith<WorkflowExecutionUnavailable> { fixture.execution.pause(older) }
        assertFailsWith<WorkflowExecutionUnavailable> { fixture.execution.execute(older) { true } }
        assertEquals(newer.driverGeneration, fixture.workflow.storage.find(run.id)?.driverGeneration)
        assertTrue(fixture.finished.isEmpty())
    }

    @Test
    fun `pause cleanup releases held slots before claiming one for an unrecorded helper step`() = runTest {
        val fixture = ExecutionFixture(StandardTestDispatcher(testScheduler))
        val run = fixture.workflow.initial
        val held = WorkflowHelperGrant(
            ActionId("wf_slot_held"),
            run.id,
            run.harness,
            StepKey("s0"),
            DIGEST,
            null,
            RequestId("r0"),
            RequestId("a0"),
        )
        // The run's only slot is still held in this process, e.g. by a revoked driver generation.
        fixture.resources.acquire(held, null).bind(HelperId("h0"))
        assertEquals(0, fixture.capacity.value)
        fixture.bindings.bind(HarnessHelperBinding(HelperId("h1"), run.id.action, run.harness, RequestId("a1")))
        val steps = listOf(
            WorkflowStep(StepKey("s0"), DIGEST, helper = HelperId("h0"), request = RequestId("r0")),
            WorkflowStep(StepKey("s1"), DIGEST, HelperId("h1"), request = RequestId("r1"), phase = StepPhase.Running),
        )
        val stored = run.copy(steps = steps)
        assertTrue(fixture.workflow.storage.save(stored, null))
        val pause = async { fixture.execution.pause(stored) }
        advanceUntilIdle()
        assertTrue(pause.isCompleted)
        assertFalse(pause.await())
        assertEquals(2, fixture.base.releases)
        assertEquals(1, fixture.capacity.value)
        assertTrue(fixture.grants.pending().isEmpty())
    }

    @Test
    fun `a cancelled run stops its organism and returns the slot before it becomes terminal`() = runTest {
        val fixture = ExecutionFixture(StandardTestDispatcher(testScheduler))
        val run = fixture.runWithOrganism(WorkflowFailure.Cancelled)
        val owner = SupervisorJob()
        val drivers = fixture.drivers(CoroutineScope(owner + StandardTestDispatcher(testScheduler)))
        drivers.drive(run)
        advanceUntilIdle()
        assertEquals(listOf(ORGANISM), fixture.organisms.host.aborted)
        assertTrue(fixture.organisms.grants.pending().isEmpty())
        assertEquals(WorkflowStatus.Failed(WorkflowFailure.Cancelled), fixture.workflow.storage.load().single().status)
        owner.cancel()
    }

    @Test
    fun `a cancelled run stays open and keeps its slot while organic AI cannot confirm the stop`() = runTest {
        val fixture = ExecutionFixture(StandardTestDispatcher(testScheduler))
        val run = fixture.runWithOrganism(WorkflowFailure.Cancelled)
        fixture.organisms.host.isAbortEffective = false
        val owner = SupervisorJob()
        val drivers = fixture.drivers(CoroutineScope(owner + StandardTestDispatcher(testScheduler)))
        drivers.drive(run)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(WorkflowStatus.Running, fixture.workflow.storage.load().single().status)
        assertEquals(1, fixture.organisms.grants.pending().size)
        assertTrue(fixture.organisms.host.aborted.size > 1)
        fixture.organisms.host.views[ORGANISM] = OrganismView.Aborted
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(fixture.organisms.grants.pending().isEmpty())
        assertEquals(WorkflowStatus.Failed(WorkflowFailure.Cancelled), fixture.workflow.storage.load().single().status)
        owner.cancel()
    }

    @Test
    fun `pausing an unfinished run leaves its organism running and its slot held`() = runTest {
        val fixture = ExecutionFixture(StandardTestDispatcher(testScheduler))
        val run = fixture.runWithOrganism(null)
        assertFalse(fixture.execution.pause(run))
        assertTrue(fixture.organisms.host.aborted.isEmpty())
        assertEquals(1, fixture.organisms.grants.pending().size)
    }
}

private val ORGANISM = organismOf("s0")

private class ExecutionFixture(private val dispatcher: CoroutineDispatcher) {
    val workflow = WorkflowFixture()
    val grants = StoredWorkflowHelperJournal(RunDeliveryTestStore(workflow.clock), workflow.clock)
    val base = SpawnTestHelpers(mutableListOf())
    val bindings = SpawnTestBindings(mutableListOf())
    val finished = mutableListOf<ActionId>()
    val capacity = MutableStateFlow(1)
    private val helpers = object : HelperAgents by base {
        override suspend fun finish(action: ActionId, payload: String) {
            finished += action
        }
    }
    val resources = WorkflowHelperResources(helpers, grants)
    val organisms = OrganismTestPorts()
    val execution = execution(workflow.storage)

    init {
        // One owner slot: a second acquisition waits until a held lease is released.
        base.beforeAcquire = {
            capacity.first { it > 0 }
            capacity.update { it - 1 }
        }
        base.release = {
            capacity.update { it + 1 }
            HelperReleaseResult.Released
        }
    }

    fun execution(storage: HarnessRunStorage) = WorkflowRunExecution(
        storage,
        WorkflowCodeExecutor(UnusedHost, HarnessExecutionLane(dispatcher), NeutralOrigins, workflow.clock) { it },
        WorkflowHelperPorts(grants, resources, helpers, bindings),
        organisms.ports,
        AdmittedRuns,
        workflow.clock,
    ) {}

    fun drivers(scope: CoroutineScope) = ProfileWorkflowDrivers(scope, execution) { _, _ -> true }

    /** A stored run whose first step owns a developing organism with a granted slot. */
    suspend fun runWithOrganism(cancellation: WorkflowFailure?): WorkflowRun {
        val step = WorkflowStep(StepKey("s0"), DIGEST, organism = ORGANISM, phase = StepPhase.Running)
        val run = workflow.initial.copy(steps = listOf(step), cancellation = cancellation)
        check(workflow.storage.save(run, null))
        organisms.grants.acquire(WorkflowOrganismGrant(run.id, run.harness, step.key, DIGEST, null, ORGANISM))
        organisms.host.views[ORGANISM] = OrganismView.Developing()
        return run
    }
}

private class RecordingRunsScope : EffectScope<HarnessRunsIntent> {
    val sent = mutableListOf<HarnessRunsIntent>()
    override suspend fun send(intent: HarnessRunsIntent): SendResult {
        sent += intent
        return SendResult.Accepted
    }
}

private object AdmittedRuns : WorkflowRunAccess {
    override suspend fun isAdmitted(run: WorkflowRun): Boolean = true
    override fun origin(run: WorkflowRun): HarnessCallOrigin = HarnessCallOrigin()
}

private object NeutralOrigins : HarnessCallOrigins {
    override fun current(): HarnessCallOrigin = HarnessCallOrigin()
    override fun context(origin: HarnessCallOrigin): HarnessOriginContext = HarnessOriginContext(origin)
}

private object UnusedHost : HarnessScriptHost {
    override val isAvailable = true
    override suspend fun compile(request: HarnessCompilationRequest): HarnessCompilationResult =
        error("No author code runs in these lifecycle paths")
    override suspend fun evaluate(
        code: CompiledHarnessCode,
        context: HarnessEvaluationContext,
    ): HarnessEvaluationResult = error("No author code runs in these lifecycle paths")
    override suspend fun removeCached(harness: HarnessId, item: ItemId): Unit = error("Pinned code stays owned")
}

private val DIGEST = "a".repeat(64)
