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
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.organicai.api.OrganismIsolation
import io.aequicor.heartbeat.feature.organicai.api.sandboxTask
import io.aequicor.heartbeat.feature.organicai.impl.C1
import io.aequicor.heartbeat.feature.organicai.impl.organism
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeIntent
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeMachineKey
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeOutput
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreePhase
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeState
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeTask
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorktreeCellWorkspacesTest {
    private val project = WorkspaceRef("project")
    private val sandboxed = organism().copy(workspace = project, isolation = OrganismIsolation.Sandbox)
    private val key = sandboxed.sandboxTask(C1)
    private val prepare: WorktreeIntent.Public = WorktreeIntent.Public.Prepare(key, project, isSandbox = true)
    private val machine = WorktreeFake()
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

    fun publish(task: WorktreeTask) {
        val tasks = (state.value as WorktreeState.Ready).tasks
        state.value = WorktreeState.Ready(tasks + (task.chatId to task))
    }

    override suspend fun send(intent: WorktreeIntent.Public): SendResult {
        sent += intent
        if (result == SendResult.Accepted) onPrepare()
        return result
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
