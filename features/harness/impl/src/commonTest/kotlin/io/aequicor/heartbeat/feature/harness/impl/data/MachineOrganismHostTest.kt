package io.aequicor.heartbeat.feature.harness.impl.data

import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolDecisionMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismEnd
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismExecution
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismOptions
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowOrganism
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowOrganismContext
import io.aequicor.heartbeat.feature.harness.impl.data.run.MachineOrganismHost
import io.aequicor.heartbeat.feature.harness.impl.data.run.WorkflowOrganismWorkspaces
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.OrganismConception
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.OrganismRelease
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.OrganismView
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.isAbortable
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.isEnded
import io.aequicor.heartbeat.feature.organicai.api.Breakdown
import io.aequicor.heartbeat.feature.organicai.api.Cell
import io.aequicor.heartbeat.feature.organicai.api.CellId
import io.aequicor.heartbeat.feature.organicai.api.CellPhase
import io.aequicor.heartbeat.feature.organicai.api.GrowthLimits
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiIntent
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiMachineSpec
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiOutput
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiState
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.OrganismBounds
import io.aequicor.heartbeat.feature.organicai.api.OrganismId
import io.aequicor.heartbeat.feature.organicai.api.OrganismIsolation
import io.aequicor.heartbeat.feature.organicai.api.OrganismStatus
import io.aequicor.heartbeat.feature.organicai.api.SessionRelease
import io.aequicor.heartbeat.feature.organicai.api.Work
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeIntent
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeOutput
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreePhase
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRun
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeSeal
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeState
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeTask
import io.aequicor.heartbeat.feature.worktreemode.api.sealed
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MachineOrganismHostTest {
    private val bound = WorkflowOrganism("wf-one")
    private val id = OrganismId("wf-one")
    private val options = OrganismOptions("Stage")
    private val machine = FakeOrganicMachine(OrganicAiState.Living(mapOf(id to organism())))

    private fun host(ref: FakeOrganicMachine? = machine): MachineOrganismHost {
        val registry = FakeOrganicRegistry(ref)
        return MachineOrganismHost(registry, WorkflowOrganismWorkspaces(registry))
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `a repeated output waits for its seal acknowledgement despite unrelated task updates`() = runTest {
        val base = "a".repeat(40)
        val ended = organism(OrganismStatus.Completed("done"), CellPhase.Completed("done")).copy(
            workspace = WorkspaceRef("project"),
            isolation = OrganismIsolation.Sandbox,
            baseCommit = base,
        )
        val organic = FakeOrganicMachine(OrganicAiState.Living(mapOf(id to ended)))
        val sandbox = FakeStageWorktree()
        val key = "organism.${bound.id}.zygote"
        val operation = "workflow_${bound.id}"
        val saved = WorktreeTask(
            key,
            WorkspaceRef("project"),
            branch = "heartbeat/task",
            pinnedBaseCommit = base,
            isSandbox = true,
            phase = WorktreePhase.Idle,
        ).sealed(WorktreeSeal(operation, "b".repeat(40)))
        sandbox.state.value = WorktreeState.Ready(mapOf(key to saved))
        val registry = FakeOrganicRegistry(organic, sandbox)
        val host = MachineOrganismHost(registry, WorkflowOrganismWorkspaces(registry))
        val output = async { host.output(bound) }
        runCurrent()
        assertEquals(listOf(WorktreeIntent.Public.Seal(key, operation)), sandbox.seals)
        // A build update preserves the old successful seal while the new Git validation is still pending.
        val updated = saved.copy(revision = saved.revision + 1)
        sandbox.state.value = WorktreeState.Ready(mapOf(key to updated))
        runCurrent()
        assertFalse(output.isCompleted)
        val failed = updated.sealed(WorktreeSeal(operation, failure = "ResultProofUnavailable"))
            .copy(revision = updated.revision + 1)
        sandbox.state.value = WorktreeState.Ready(mapOf(key to failed))
        runCurrent()
        assertTrue(output.isCompleted)
        assertNull(output.await())
    }

    @Test
    fun `a restored sandbox cannot release its quota while the durable native run is still open`() = runTest {
        val session = SessionRef(EngineId("engine"), SessionSourceId("source"), "native")
        val ended = organism(OrganismStatus.Completed("done"), CellPhase.Completed("done")).let {
            it.copy(
                workspace = WorkspaceRef("project"),
                isolation = OrganismIsolation.Sandbox,
                baseCommit = "a".repeat(40),
                cells = it.cells.map { cell -> cell.copy(session = session) },
            )
        }
        val organic = FakeOrganicMachine(OrganicAiState.Living(mapOf(id to ended)))
        val sandbox = FakeStageWorktree()
        val key = "organism.${bound.id}.zygote"
        val task = WorktreeTask(
            key,
            WorkspaceRef("project"),
            phase = WorktreePhase.Working,
            run = WorktreeRun(RequestId("request")),
        )
        sandbox.state.value = WorktreeState.Ready(mapOf(key to task))
        val registry = FakeOrganicRegistry(organic, sandbox)
        val host = MachineOrganismHost(registry, WorkflowOrganismWorkspaces(registry))
        val uncertain = host.view(bound)
        assertEquals(OrganismView.Answered("done", OrganismRelease.Unconfirmed), uncertain)
        assertFalse(uncertain.isEnded)
        val stopped = task.copy(phase = WorktreePhase.Idle, run = task.run?.copy(outcome = TurnOutcome.Completed))
        sandbox.state.value = WorktreeState.Ready(mapOf(key to stopped))
        assertEquals(OrganismView.Answered("done"), host.view(bound))
    }

    @Test
    fun `trusted isolated conception prepares the zygote before sending finite pinned context`() = runTest {
        val organic = FakeOrganicMachine(OrganicAiState.Living())
        val sandbox = FakeStageWorktree()
        val registry = FakeOrganicRegistry(organic, sandbox)
        val host = MachineOrganismHost(registry, WorkflowOrganismWorkspaces(registry))
        val context = WorkflowOrganismContext((1..10).map { HarnessId("h$it") }.toSet())
        val requested = options.copy(execution = OrganismExecution(maxCells = 3, maxCases = 0))
        assertEquals(
            OrganismConception.Accepted,
            host.conceiveIsolated(bound, "Stage", requested, WorkspaceRef("project"), context),
        )
        val prepared = sandbox.state.value as WorktreeState.Ready
        val task = prepared.tasks.values.single()
        val conception = (organic.sent.single() as OrganicAiIntent.Public.Conceive).conception
        assertEquals(task.pinnedBaseCommit, conception.baseCommit)
        assertEquals(OrganismIsolation.Sandbox, conception.isolation)
        assertEquals(AgentToolDecisionMode.AutomaticSafety, conception.decisionMode)
        assertEquals(context.harnesses.map { it.value }.toSet(), conception.harnesses)
        assertEquals(TrustLevel.Ask, conception.trust)
        assertEquals(3, conception.limits.maxCells)
        assertEquals(0, conception.limits.maxCases)
    }

    @Test
    fun `missing project or sandbox refuses isolated conception without sending a shared one`() = runTest {
        val organic = FakeOrganicMachine(OrganicAiState.Living())
        val registry = FakeOrganicRegistry(organic)
        val host = MachineOrganismHost(registry, WorkflowOrganismWorkspaces(registry))
        val context = WorkflowOrganismContext(setOf(HarnessId("owner")))
        assertEquals(OrganismConception.Rejected, host.conceiveIsolated(bound, "Stage", options, null, context))
        assertTrue(organic.sent.isEmpty())
    }

    @Test
    fun `a missing or sleeping machine is unavailable and never absent`() = runTest {
        assertEquals(OrganismView.Unavailable, host(null).view(bound))
        for (state in listOf(OrganicAiState.Dormant, OrganicAiState.Awakening, OrganicAiState.Broken)) {
            assertEquals(OrganismView.Unavailable, host(FakeOrganicMachine(state)).view(bound))
        }
    }

    @Test
    fun `an awake machine that does not know the organism shows it absent and ended`() = runTest {
        val empty = FakeOrganicMachine(OrganicAiState.Living())
        assertEquals(OrganismView.Absent, host(empty).view(bound))
        assertTrue(OrganismView.Absent.isEnded)
    }

    @Test
    fun `the state of the organism decides its view and only an ended one is ended`() = runTest {
        val interrupted = CellPhase.Stalled(Breakdown.Interrupted, Work.Genesis)
        val views = listOf(
            organism() to OrganismView.Developing,
            organism(phase = CellPhase.Resting) to OrganismView.Developing,
            organism(phase = CellPhase.Stalled(Breakdown.NoModel, Work.Genesis)) to
                OrganismView.Stalled(OrganismEnd.NoModel),
            organism(phase = interrupted) to OrganismView.Stalled(OrganismEnd.Failed),
            organism(OrganismStatus.Completed("answer")) to OrganismView.Answered("answer"),
            organism(OrganismStatus.Aborted) to OrganismView.Aborted,
            organism(OrganismStatus.Aborted, sessions = SessionRelease.Releasing) to OrganismView.Aborting,
            organism(OrganismStatus.Aborted, sessions = SessionRelease.Unconfirmed) to
                OrganismView.AbortUnconfirmed,
        )
        for ((found, expected) in views) {
            val ref = FakeOrganicMachine(OrganicAiState.Living(mapOf(id to found)))
            val view = host(ref).view(bound)
            assertEquals(expected, view)
            assertEquals(expected is OrganismView.Answered || expected == OrganismView.Aborted, view.isEnded)
        }
    }

    @Test
    fun `an organism that is only marked aborted is not ended until its sessions are released`() = runTest {
        val developing = OrganicAiState.Living(mapOf(id to organism()))
        val aborting = step(developing, OrganicAiIntent.Public.Abort(id))
        assertEquals(OrganismView.Aborting, viewIn(aborting))
        assertFalse(OrganismView.Aborting.isEnded)

        val failed = step(aborting, OrganicAiIntent.Internal.SessionsReleased(id, isConfirmed = false))
        assertEquals(OrganismView.AbortUnconfirmed, viewIn(failed))
        assertFalse(OrganismView.AbortUnconfirmed.isEnded)
        assertTrue(OrganismView.AbortUnconfirmed.isAbortable)

        val retried = step(failed, OrganicAiIntent.Public.Abort(id))
        assertEquals(OrganismView.Aborting, viewIn(retried))
        assertFalse(OrganismView.Aborting.isAbortable)

        val released = step(retried, OrganicAiIntent.Internal.SessionsReleased(id, isConfirmed = true))
        assertEquals(OrganismView.Aborted, viewIn(released))
        assertTrue(OrganismView.Aborted.isEnded)
    }

    /** The state the real organic AI spec moves to, so the host reads what the machine really shows. */
    private fun step(from: OrganicAiState, intent: OrganicAiIntent): OrganicAiState =
        OrganicAiMachineSpec.resolve(from, intent)?.to ?: error("The machine ignored $intent")

    private suspend fun viewIn(state: OrganicAiState): OrganismView = host(FakeOrganicMachine(state)).view(bound)

    @Test
    fun `a conception pins the models and the route and caps trust at Ask`() = runTest {
        val target = EngineTarget(EngineId("engine"), EngineBindingId("binding"), ModelId("model"))
        val pinned = options.copy(target = target, immunityTarget = target)
        val empty = FakeOrganicMachine(OrganicAiState.Living())
        val result = host(empty).conceive(bound, "Ship it", pinned, WorkspaceRef("project"))
        assertEquals(OrganismConception.Accepted, result)
        val conception = (empty.sent.single() as OrganicAiIntent.Public.Conceive).conception
        assertEquals(id, conception.id)
        assertEquals("Ship it", conception.goal)
        assertEquals(target, conception.target)
        assertEquals(target, conception.immunityTarget)
        assertEquals(WorkspaceRef("project"), conception.workspace)
        assertEquals(TrustLevel.Ask, conception.trust)
        assertEquals(AgentToolDecisionMode.AutomaticSafety, conception.decisionMode)
    }

    @Test
    fun `conception is unavailable without an awake machine and sends nothing`() = runTest {
        assertEquals(OrganismConception.Unavailable, host(null).conceive(bound, "Ship it", options, null))
        val asleep = FakeOrganicMachine(OrganicAiState.Dormant)
        assertEquals(OrganismConception.Unavailable, host(asleep).conceive(bound, "Ship it", options, null))
        assertTrue(asleep.sent.isEmpty())
        val gone = FakeOrganicMachine(OrganicAiState.Living(), SendResult.NotRunning)
        assertEquals(OrganismConception.Unavailable, host(gone).conceive(bound, "Ship it", options, null))
    }

    @Test
    fun `an ignored conception is accepted only for an organism that exists`() = runTest {
        val existing = FakeOrganicMachine(OrganicAiState.Living(mapOf(id to organism())), SendResult.Ignored)
        assertEquals(OrganismConception.Accepted, host(existing).conceive(bound, "Ship it", options, null))
        val missing = FakeOrganicMachine(OrganicAiState.Living(), SendResult.Ignored)
        assertEquals(OrganismConception.Rejected, host(missing).conceive(bound, "Ship it", options, null))
    }

    @Test
    fun `an abort addresses only the organism of the step`() = runTest {
        host().abort(bound)
        assertEquals(listOf<OrganicAiIntent.Public>(OrganicAiIntent.Public.Abort(id)), machine.sent)
    }

    @Test
    fun `the goal bound of a step is the bound of an organism`() {
        assertEquals(OrganismBounds.MAX_GOAL, HarnessLimits.ORGANISM_GOAL_CHARS)
        assertFalse(OrganismView.Answered("private-answer").toString().contains("private-answer"))
    }

    @Test
    fun `a retained session releases only with durable proof of a closed submission`() = runTest {
        val session = SessionRef(EngineId("engine"), SessionSourceId("source"), "native")
        val ended = organism(OrganismStatus.Aborted).let {
            it.copy(
                workspace = WorkspaceRef("project"),
                isolation = OrganismIsolation.Sandbox,
                baseCommit = "a".repeat(40),
                cells = it.cells.map { cell -> cell.copy(session = session) },
            )
        }
        val organic = FakeOrganicMachine(OrganicAiState.Living(mapOf(id to ended)))
        val sandbox = FakeStageWorktree()
        val registry = FakeOrganicRegistry(organic, sandbox)
        val host = MachineOrganismHost(registry, WorkflowOrganismWorkspaces(registry))
        val key = "organism.${bound.id}.zygote"
        val task = WorktreeTask(key, WorkspaceRef("project"), phase = WorktreePhase.Failed)
        sandbox.state.value = WorktreeState.Ready(mapOf(key to task))
        assertEquals(OrganismView.AbortUnconfirmed, host.view(bound))
        val closed = task.copy(closedSubmission = RequestId("rejected"))
        sandbox.state.value = WorktreeState.Ready(mapOf(key to closed))
        assertEquals(OrganismView.Aborted, host.view(bound))
        sandbox.state.value = WorktreeState.Ready(mapOf(key to closed.copy(phase = WorktreePhase.RecoveryRequired)))
        assertEquals(OrganismView.AbortUnconfirmed, host.view(bound))
        val active = closed.copy(phase = WorktreePhase.Working, run = WorktreeRun(RequestId("next")))
        sandbox.state.value = WorktreeState.Ready(mapOf(key to active))
        assertEquals(OrganismView.AbortUnconfirmed, host.view(bound))
    }

    private fun organism(
        status: OrganismStatus = OrganismStatus.Developing,
        phase: CellPhase = CellPhase.Working(RequestId("request"), Work.Genesis),
        sessions: SessionRelease = SessionRelease.Released,
    ) = Organism(
        id,
        "goal",
        null,
        null,
        null,
        null,
        GrowthLimits(),
        listOf(Cell(CellId.ZYGOTE, "zygote", null, "goal", phase)),
        status = status,
        sessions = sessions,
    )
}

private class FakeOrganicMachine(initial: OrganicAiState, private val result: SendResult = SendResult.Accepted) :
    MachineRef<OrganicAiState, OrganicAiIntent.Public, OrganicAiOutput> {
    override val name: String = "organic-ai"
    override val state: StateFlow<OrganicAiState> = MutableStateFlow(initial)
    override val outputs: Flow<OrganicAiOutput> = emptyFlow()
    val sent = mutableListOf<OrganicAiIntent.Public>()

    override suspend fun send(intent: OrganicAiIntent.Public): SendResult {
        sent += intent
        return result
    }
}

private class FakeOrganicRegistry(
    private val ref: FakeOrganicMachine?,
    private val sandbox: FakeStageWorktree? = null,
) : MachineRegistry {
    @Suppress("UNCHECKED_CAST") // tests address the one organic AI machine key
    override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> find(
        key: MachineKey<S, I, P, E, O>,
    ): MachineRef<S, P, O>? = (if (key.name == "worktree-mode") sandbox else ref) as MachineRef<S, P, O>?

    @Suppress("UNCHECKED_CAST") // tests address the one organic AI machine key
    override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> observe(
        key: MachineKey<S, I, P, E, O>,
    ): StateFlow<MachineRef<S, P, O>?> = MutableStateFlow(find(key))

    override suspend fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> send(
        key: MachineKey<S, I, P, E, O>,
        intent: P,
    ): SendResult = find(key)?.send(intent) ?: SendResult.NotRunning
}

private class FakeStageWorktree : MachineRef<WorktreeState, WorktreeIntent.Public, WorktreeOutput> {
    override val name = "worktree-mode"
    override val state = MutableStateFlow<WorktreeState>(WorktreeState.Ready())
    override val outputs: Flow<WorktreeOutput> = emptyFlow()
    val seals = mutableListOf<WorktreeIntent.Public.Seal>()

    override suspend fun send(intent: WorktreeIntent.Public): SendResult {
        if (intent is WorktreeIntent.Public.Seal) {
            seals += intent
            return SendResult.Accepted
        }
        val prepare = intent as WorktreeIntent.Public.Prepare
        check(prepare.isBasePinned && prepare.isSandbox == true)
        val task = WorktreeTask(
            prepare.chatId,
            prepare.project,
            executionWorkspace = WorkspaceRef("cell"),
            branch = "heartbeat/task",
            baseCommit = "a".repeat(40),
            pinnedBaseCommit = "a".repeat(40),
            isSandbox = true,
            phase = WorktreePhase.Idle,
        )
        state.value = WorktreeState.Ready(mapOf(task.chatId to task))
        return SendResult.Accepted
    }
}
