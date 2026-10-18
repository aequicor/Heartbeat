package io.aequicor.heartbeat.feature.organicai.impl.data

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
import io.aequicor.heartbeat.feature.organicai.api.CellId
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.OrganismIsolation
import io.aequicor.heartbeat.feature.organicai.api.sandboxTask
import io.aequicor.heartbeat.feature.organicai.impl.C1
import io.aequicor.heartbeat.feature.organicai.impl.ZYGOTE
import io.aequicor.heartbeat.feature.organicai.impl.cell
import io.aequicor.heartbeat.feature.organicai.impl.domain.CellResult
import io.aequicor.heartbeat.feature.organicai.impl.organism
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeIntent
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreePhase
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRunKind
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeSeal
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeState
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeTask
import io.aequicor.heartbeat.feature.worktreemode.api.failed
import io.aequicor.heartbeat.feature.worktreemode.api.runPrepared
import io.aequicor.heartbeat.feature.worktreemode.api.transition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
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
    fun `a new run waits for restoration before closing the previous request`() = runTest {
        val stale = RequestId("organic-o1-c1-1")
        val fresh = RequestId("organic-o1-c1-2")
        machine.state.value = WorktreeState.Loading
        val starting = async { workspaces.begin(sandboxed, C1, fresh) }
        runCurrent()
        assertEquals(false, starting.isCompleted)
        assertEquals(emptyList(), machine.sent)

        val restored = running(stale, withTurn = true).copy(phase = WorktreePhase.RecoveryRequired)
        machine.state.value = WorktreeState.Ready(mapOf(key to restored))
        starting.await()
        assertEquals(
            listOf<WorktreeIntent.Public>(
                WorktreeIntent.Public.RunSettled(key, stale, session, turn, TurnOutcome.Unknown),
                WorktreeIntent.Public.RunStarted(key, fresh, WorktreeRunKind.Coding),
            ),
            machine.sent,
        )
        assertEquals(fresh, machine.task(key).run?.request)
    }

    @Test
    fun `adoption waits for the restored identity of the live native turn`() = runTest {
        val request = RequestId("organic-o1-c1-1")
        machine.state.value = WorktreeState.Loading
        val adopting = async { workspaces.adopt(sandboxed, C1, session, turn) }
        runCurrent()
        assertEquals(false, adopting.isCompleted)
        assertEquals(emptyList(), machine.sent)

        val restored = running(request, withTurn = true).copy(phase = WorktreePhase.RecoveryRequired)
        machine.state.value = WorktreeState.Ready(mapOf(key to restored))
        checkNotNull(adopting.await())
        assertEquals(
            listOf<WorktreeIntent.Public>(WorktreeIntent.Public.RunAdopted(key, request, session, turn)),
            machine.sent,
        )
        assertEquals(request, machine.task(key).run?.request)
    }

    @Test
    fun `workspace and result readers wait for the existing restored sandbox`() = runTest {
        machine.state.value = WorktreeState.Loading
        val workspace = async { workspaces.prepare(sandboxed, C1) }
        val result = async { workspaces.result(sandboxed, C1) }
        val branches = async { workspaces.branches(sandboxed.withCells(C1)) }
        runCurrent()
        assertEquals(false, workspace.isCompleted)
        assertEquals(false, result.isCompleted)
        assertEquals(false, branches.isCompleted)
        assertEquals(emptyList(), machine.sent)

        val restored = prepared(revision = 1).copy(branch = "branch", seal = WorktreeSeal("capture", "saved"))
        machine.state.value = WorktreeState.Ready(mapOf(key to restored))
        assertEquals(WorkspaceRef("checkout"), workspace.await())
        assertEquals(CellResult("branch", null, "saved"), result.await())
        assertEquals(mapOf(C1 to "branch"), branches.await())
        assertEquals(emptyList(), machine.sent)
    }

    @Test
    fun `a failed journal refuses every sandbox lookup without treating it as empty`() = runTest {
        machine.state.value = WorktreeState.LoadError
        assertFailsWith<EngineException> { workspaces.prepare(sandboxed, C1) }
        assertFailsWith<EngineException> { workspaces.begin(sandboxed, C1, RequestId("request")) }
        assertFailsWith<EngineException> { workspaces.adopt(sandboxed, C1, session, turn) }
        assertFailsWith<EngineException> { workspaces.result(sandboxed, C1) }
        assertFailsWith<EngineException> { workspaces.branches(sandboxed) }
        assertEquals(emptyList(), machine.sent)
    }

    @Test
    fun `waiting for restoration is cancellable and cannot start a run after cancellation`() = runTest {
        machine.state.value = WorktreeState.Loading
        val starting = async { workspaces.begin(sandboxed, C1, RequestId("request")) }
        runCurrent()
        starting.cancelAndJoin()
        machine.state.value = WorktreeState.Ready(mapOf(key to prepared(revision = 1)))
        runCurrent()
        assertEquals(true, starting.isCancelled)
        assertEquals(emptyList(), machine.sent)
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

    @Test
    fun `the result of an ended cell is sealed once and reused by every reader`() = runTest {
        machine.publish(prepared(revision = 1).copy(branch = "heartbeat/worktree-c1", baseCommit = "base1"))
        val first = checkNotNull(workspaces.result(sandboxed, C1))
        assertEquals(CellResult("heartbeat/worktree-c1", "base1", "commit-1"), first)
        assertEquals(first, workspaces.result(sandboxed, C1))
        assertEquals(1, machine.sent.count { it is WorktreeIntent.Public.Seal })
        assertEquals("commit-1", machine.task(key).seal?.commit)
    }

    @Test
    fun `overlapping readers share one capture even while the seal is pending`() = runTest {
        machine.publish(prepared(revision = 1).copy(branch = "heartbeat/worktree-c1"))
        val captured = CompletableDeferred<Unit>()
        machine.onSeal = { captured.await() }
        val first = async { workspaces.result(sandboxed, C1) }
        runCurrent()
        val second = async { workspaces.result(sandboxed, C1) }
        runCurrent()
        assertEquals(false, first.isCompleted)
        assertEquals(false, second.isCompleted)
        assertEquals(1, machine.sent.count { it is WorktreeIntent.Public.Seal })

        captured.complete(Unit)
        assertEquals(CellResult("heartbeat/worktree-c1", null, "commit-1"), first.await())
        assertEquals(first.await(), second.await())
        assertEquals(1, machine.sent.count { it is WorktreeIntent.Public.Seal })
    }

    @Test
    fun `a result nobody could capture is null and a later reader tries again`() = runTest {
        assertNull(workspaces.result(sandboxed, C1))
        machine.publish(prepared(revision = 1))
        assertNull(workspaces.result(sandboxed, C1))
        machine.publish(prepared(revision = 2).copy(branch = "heartbeat/worktree-c1"))
        machine.failSeals = true
        assertNull(workspaces.result(sandboxed, C1))
        machine.failSeals = false
        assertEquals("commit-1", workspaces.result(sandboxed, C1)?.commit)
        assertEquals(2, machine.sent.count { it is WorktreeIntent.Public.Seal })
    }

    @Test
    fun `branches lists every cell sandbox worktree mode knows`() = runTest {
        machine.publish(prepared(revision = 1).copy(branch = "heartbeat/worktree-c1"))
        assertEquals(mapOf(C1 to "heartbeat/worktree-c1"), workspaces.branches(sandboxed.withCells(C1, ZYGOTE)))
    }

    private fun Organism.withCells(vararg ids: CellId): Organism =
        copy(cells = cells + ids.filter { id -> cells.none { it.id == id } }.map { cell(it) })

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
