package io.aequicor.heartbeat.feature.harness.impl.data.authoring

import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.HarnessScope
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeMachineKey
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeOutput
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreePhase
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeState
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeTask
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProjectHarnessCreationScopeTest {
    private val project = WorkspaceRef("project")
    private val checkout = WorkspaceRef("checkout")

    @Test
    fun `detached chats create shared harnesses without requiring local projects`() = runTest {
        val scope = ProjectHarnessCreationScope(ScopeWorktrees(null), ScopeProjects(isAvailable = false))
        assertEquals(HarnessScope.Attached, scope.resolve(null))
    }

    @Test
    fun `saved project retains its identity even when its directory is unavailable`() = runTest {
        val scope = ProjectHarnessCreationScope(ScopeWorktrees(null), ScopeProjects(listOf(project)))
        assertEquals(HarnessScope.Projects(setOf(project)), scope.resolve(project))
    }

    @Test
    fun `managed checkout belongs to its source project across lifecycle states`() = runTest {
        for (phase in WorktreePhase.entries) {
            val task = WorktreeTask("chat", project, executionWorkspace = checkout, phase = phase)
            val scope = ProjectHarnessCreationScope(
                ScopeWorktrees(WorktreeState.Ready(mapOf(task.chatId to task))),
                ScopeProjects(),
            )
            assertEquals(HarnessScope.Projects(setOf(project)), scope.resolve(checkout), phase.name)
        }
    }

    @Test
    fun `unknown workspace never falls back to shared scope`() = runTest {
        val scope = ProjectHarnessCreationScope(ScopeWorktrees(WorktreeState.Ready()), ScopeProjects(listOf(project)))
        assertNull(scope.resolve(checkout))
    }

    @Test
    fun `unrestored worktree journal cannot classify an unknown checkout`() = runTest {
        for (state in listOf(null, WorktreeState.Idle, WorktreeState.Loading, WorktreeState.LoadError)) {
            val scope = ProjectHarnessCreationScope(ScopeWorktrees(state), ScopeProjects(listOf(project)))
            assertNull(scope.resolve(checkout))
            assertEquals(HarnessScope.Projects(setOf(project)), scope.resolve(project))
        }
    }

    @Test
    fun `unsupported workspace registry cannot turn a project into a shared harness`() = runTest {
        val scope = ProjectHarnessCreationScope(ScopeWorktrees(null), ScopeProjects(isAvailable = false))
        assertNull(scope.resolve(project))
    }
}

private class ScopeProjects(
    private val projects: List<WorkspaceRef> = emptyList(),
    override val isAvailable: Boolean = true,
) : LocalWorkspaces {
    override fun observe(): Flow<List<LocalWorkspace>> {
        check(isAvailable)
        return flowOf(projects.map { LocalWorkspace(it, it.value) })
    }

    override suspend fun register(directory: String): LocalWorkspace = error("No project registration is expected")
    override suspend fun resolve(ref: WorkspaceRef): String? = error("Directory availability does not define ownership")
}

private class ScopeWorktrees(initial: WorktreeState?) : MachineRegistry {
    private val worktree = initial?.let {
        object : MachineRef<WorktreeState, Nothing, WorktreeOutput> {
            override val name = WorktreeMachineKey.name
            override val state = MutableStateFlow(it)
            override val outputs: Flow<WorktreeOutput> = emptyFlow()
            override suspend fun send(intent: Nothing): SendResult = error("No worktree command is expected")
        }
    }

    @Suppress("UNCHECKED_CAST") // The fake resolves only the worktree key.
    override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> find(
        key: MachineKey<S, I, P, E, O>,
    ): MachineRef<S, P, O>? = if (key == WorktreeMachineKey) worktree as MachineRef<S, P, O>? else null

    override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> observe(
        key: MachineKey<S, I, P, E, O>,
    ): StateFlow<MachineRef<S, P, O>?> = MutableStateFlow(find(key))

    override suspend fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> send(
        key: MachineKey<S, I, P, E, O>,
        intent: P,
    ): SendResult = error("No machine command is expected")
}
