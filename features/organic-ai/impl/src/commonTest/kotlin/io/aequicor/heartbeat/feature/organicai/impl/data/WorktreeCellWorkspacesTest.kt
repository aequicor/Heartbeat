package io.aequicor.heartbeat.feature.organicai.impl.data

import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.OrganismIsolation
import io.aequicor.heartbeat.feature.organicai.api.sandboxTask
import io.aequicor.heartbeat.feature.organicai.impl.C1
import io.aequicor.heartbeat.feature.organicai.impl.organism
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeIntent
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeMachineKey
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeOutput
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreePhase
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRunKind
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeState
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeTask
import io.aequicor.heartbeat.feature.worktreemode.api.failed
import io.aequicor.heartbeat.feature.worktreemode.api.runPrepared
import io.aequicor.heartbeat.feature.worktreemode.api.transition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WorktreeCellWorkspacesTest {
    private val project = WorkspaceRef("project")
    private val sandboxed = organism().copy(workspace = project, isolation = OrganismIsolation.Sandbox)
    private val key = sandboxed.sandboxTask(C1)
    private val prepare: WorktreeIntent.Public = WorktreeIntent.Public.Prepare(key, project, isSandbox = true)
    private val machine = WorktreeFake()
    private val session = SessionRef(EngineId("pi"), SessionSourceId("local"), "cell")
    private val turn = TurnId("t1")
    private val workspaces = WorktreeCellWorkspaces(registry(machine))

    @Test
    fun `a missing sandbox is prepared once under the key of its cell`() = runTest {
        machine.onPrepare = { machine.publish(prepared(revision = 2)) }
        assertEquals(WorkspaceRef("checkout"), workspaces.prepare(sandboxed, C1))
        assertEquals(listOf(prepare), machine.sent)
    }

    @Test
    fun `an already prepared sandbox is found without preparing another`() = runTest {
        machine.publish(prepared(revision = 5))
        assertEquals(WorkspaceRef("checkout"), workspaces.prepare(sandboxed, C1))
        assertEquals(emptyList(), machine.sent)
    }

    @Test
    fun `a plain worktree prepared while sandboxes are off is accepted`() = runTest {
        machine.publish(prepared(revision = 3).copy(isSandbox = false))
        assertEquals(WorkspaceRef("checkout"), workspaces.prepare(sandboxed, C1))
        assertEquals(emptyList(), machine.sent)
    }

    @Test
    fun `a failed preparation breaks the cell instead of falling back to the project`() = runTest {
        machine.onPrepare = { machine.publish(failed(revision = 2)) }
        val error = assertFailsWith<EngineException> { workspaces.prepare(sandboxed, C1) }
        assertEquals(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet), error.failure)
    }

    @Test
    fun `a retry after a failure prepares again and ignores the stale failure`() = runTest {
        machine.publish(failed(revision = 4))
        machine.onPrepare = { machine.publish(prepared(revision = 5)) }
        assertEquals(WorkspaceRef("checkout"), workspaces.prepare(sandboxed, C1))
        assertEquals(1, machine.sent.size)
    }

    @Test
    fun `a rejected request and a missing worktree machine are failures too`() = runTest {
        machine.result = SendResult.Ignored
        assertFailsWith<EngineException> { workspaces.prepare(sandboxed, C1) }
        val absent = WorktreeCellWorkspaces(registry(null))
        assertFailsWith<EngineException> { absent.prepare(sandboxed, C1) }
    }

    @Test
    fun `a sandbox organism without a project cannot prepare anything`() = runTest {
        assertFailsWith<IllegalStateException> { workspaces.prepare(sandboxed.copy(workspace = null), C1) }
    }

    @Test
    fun `a run begins after the older request of the cell is closed as unknown`() = runTest {
        val stale = RequestId("organic-o1-c1-1")
        val fresh = RequestId("organic-o1-c1-2")
        machine.publish(running(stale, withTurn = true))
        workspaces.begin(sandboxed, C1, fresh)
        assertEquals(
            listOf<WorktreeIntent.Public>(
                WorktreeIntent.Public.RunSettled(key, stale, session, turn, TurnOutcome.Unknown),
                WorktreeIntent.Public.RunStarted(key, fresh, WorktreeRunKind.Coding),
            ),
            machine.sent,
        )
        val task = machine.task(key)
        assertEquals(fresh, task.run?.request)
        assertEquals(true, task.run?.isPrepared)
        assertEquals(WorktreePhase.Working, task.phase)
    }

    @Test
    fun `an older request that never reached the engine is rejected instead`() = runTest {
        val stale = RequestId("organic-o1-c1-1")
        machine.publish(running(stale, withTurn = false))
        workspaces.begin(sandboxed, C1, RequestId("organic-o1-c1-2"))
        assertEquals(WorktreeIntent.Public.RunRejected(key, stale, "InterruptedBeforeAcceptance"), machine.sent.first())
        assertEquals(2, machine.sent.size)
    }

    @Test
    fun `a repeated drive of a prepared request starts nothing and a failed start is a failure`() = runTest {
        val request = RequestId("organic-o1-c1-1")
        machine.publish(running(request, withTurn = false).copy(phase = WorktreePhase.Working))
        workspaces.begin(sandboxed, C1, request)
        assertEquals(emptyList(), machine.sent)

        val broken = WorktreeFake()
        broken.publish(prepared(revision = 1))
        broken.onPrepare = {}
        broken.failRuns = true
        assertFailsWith<EngineException> { WorktreeCellWorkspaces(registry(broken)).begin(sandboxed, C1, request) }
    }

    @Test
    fun `reports reach the same run and the settled one releases the sandbox for the next request`() = runTest {
        val request = RequestId("organic-o1-c1-1")
        val run = workspaces.begin(sandboxedWith(prepared(revision = 1)), C1, request)
        run.accepted(session, turn)
        assertEquals(turn, machine.task(key).run?.turn)
        run.settled(session, turn, TurnOutcome.Completed)
        assertEquals(TurnOutcome.Completed, machine.task(key).run?.outcome)
        assertEquals(WorktreePhase.Idle, machine.task(key).phase)
        // A second report of an already settled run changes nothing and does not fail.
        run.settled(session, turn, TurnOutcome.Completed)
        workspaces.begin(sandboxed, C1, RequestId("organic-o1-c1-2"))
        assertEquals(RequestId("organic-o1-c1-2"), machine.task(key).run?.request)
    }

    @Test
    fun `a lost run keeps its identity unsettled and the next request closes it`() = runTest {
        val request = RequestId("organic-o1-c1-1")
        machine.publish(prepared(revision = 1))
        val run = workspaces.begin(sandboxed, C1, request)
        run.lost(session, null)
        assertEquals(WorktreePhase.RecoveryRequired, machine.task(key).phase)
        assertNull(machine.task(key).run?.outcome)
        workspaces.begin(sandboxed, C1, RequestId("organic-o1-c1-2"))
        assertEquals(WorktreePhase.Working, machine.task(key).phase)
    }

    @Test
    fun `a restarted run is adopted only for the exact recorded native turn`() = runTest {
        val request = RequestId("organic-o1-c1-1")
        machine.publish(running(request, withTurn = true).copy(phase = WorktreePhase.RecoveryRequired))
        assertNull(workspaces.adopt(sandboxed, C1, session, TurnId("another")))
        assertEquals(emptyList(), machine.sent)
        val adopted = workspaces.adopt(sandboxed, C1, session, turn)
        assertEquals(
            listOf<WorktreeIntent.Public>(WorktreeIntent.Public.RunAdopted(key, request, session, turn)),
            machine.sent,
        )
        assertEquals(WorktreePhase.Working, machine.task(key).phase)
        adopted!!.settled(session, turn, TurnOutcome.Completed)
        assertEquals(TurnOutcome.Completed, machine.task(key).run?.outcome)
    }

    @Test
    fun `a run that is still working is taken over without any command`() = runTest {
        machine.publish(running(RequestId("organic-o1-c1-1"), withTurn = true).copy(phase = WorktreePhase.Working))
        assertEquals(true, workspaces.adopt(sandboxed, C1, session, turn) != null)
        assertEquals(emptyList(), machine.sent)
        assertNull(workspaces.adopt(sandboxed, C1, session.copy(nativeId = "other"), turn))
    }

    private fun sandboxedWith(task: WorktreeTask): Organism {
        machine.publish(task)
        return sandboxed
    }

    private fun running(request: RequestId, withTurn: Boolean): WorktreeTask {
        val pending = prepared(
            revision = 1,
        ).transition(WorktreeIntent.Public.RunStarted(key, request)).runPrepared(request)
        val accepted = if (withTurn) {
            pending.transition(
                WorktreeIntent.Public.RunAccepted(key, request, session, turn),
            )
        } else {
            pending
        }
        return accepted.copy(revision = 3)
    }

    private fun prepared(revision: Long) = WorktreeTask(
        key,
        project,
        executionWorkspace = WorkspaceRef("checkout"),
        phase = WorktreePhase.Idle,
        revision = revision,
        isSandbox = true,
    )

    private fun failed(revision: Long) = WorktreeTask(
        key,
        project,
        phase = WorktreePhase.Failed,
        failure = "ProvisionFailed",
        revision = revision,
        isSandbox = true,
    )
}

private class WorktreeFake : MachineRef<WorktreeState, WorktreeIntent.Public, WorktreeOutput> {
    override val name = "worktree-fake"
    override val state = MutableStateFlow<WorktreeState>(WorktreeState.Ready())
    override val outputs = emptyFlow<WorktreeOutput>()
    val sent = mutableListOf<WorktreeIntent.Public>()
    var result = SendResult.Accepted
    var onPrepare: () -> Unit = {}
    var failRuns = false

    fun publish(task: WorktreeTask) {
        val tasks = (state.value as WorktreeState.Ready).tasks
        state.value = WorktreeState.Ready(tasks + (task.chatId to task))
    }

    fun task(key: String): WorktreeTask = (state.value as WorktreeState.Ready).tasks.getValue(key)

    override suspend fun send(intent: WorktreeIntent.Public): SendResult {
        sent += intent
        if (result != SendResult.Accepted) return result
        when (intent) {
            is WorktreeIntent.Public.Prepare -> onPrepare()

            is WorktreeIntent.Public.RunStarted -> change(intent.chatId) { task ->
                // The journal prepares the run in the same step; this is what the real one commits.
                if (failRuns) {
                    task.failed(
                        "WorktreeUnavailable",
                    )
                } else {
                    task.transition(intent).runPrepared(intent.request)
                }
            }

            else -> change(intent.runKey()) { it.transition(intent) }
        }
        return result
    }

    private fun change(key: String, transform: (WorktreeTask) -> WorktreeTask) {
        val before = task(key)
        val after = transform(before)
        if (after != before) publish(after.copy(revision = before.revision + 1))
    }

    private fun WorktreeIntent.Public.runKey(): String = when (this) {
        is WorktreeIntent.Public.RunAccepted -> chatId
        is WorktreeIntent.Public.RunSettled -> chatId
        is WorktreeIntent.Public.RunRejected -> chatId
        is WorktreeIntent.Public.RunAdopted -> chatId
        is WorktreeIntent.Public.RunObservationLost -> chatId
        is WorktreeIntent.Public.RunSubmissionObservationLost -> chatId
        else -> error("unexpected $this")
    }
}

private fun registry(machine: WorktreeFake?) = object : MachineRegistry {
    @Suppress("UNCHECKED_CAST") // Only the checked worktree key is served by this fixture.
    override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> find(
        key: MachineKey<S, I, P, E, O>,
    ): MachineRef<S, P, O>? {
        check(key == WorktreeMachineKey)
        return machine as MachineRef<S, P, O>?
    }

    override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> observe(
        key: MachineKey<S, I, P, E, O>,
    ): StateFlow<MachineRef<S, P, O>?> = MutableStateFlow(find(key))

    override suspend fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> send(
        key: MachineKey<S, I, P, E, O>,
        intent: P,
    ): SendResult = find(key)?.send(intent) ?: SendResult.NotRunning
}
