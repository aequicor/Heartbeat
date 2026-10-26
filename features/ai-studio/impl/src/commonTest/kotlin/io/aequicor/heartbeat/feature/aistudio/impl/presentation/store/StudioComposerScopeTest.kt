package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSession
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelId
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PaneUi
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class StudioComposerScopeTest {
    private val target = EngineTarget(EngineId("pi"), EngineBindingId("b"), ModelId("m"))
    private val project = WorkspaceRef("main-project")
    private val checkout = WorkspaceRef("worktree-checkout")

    @Test
    fun `a worktree projects its execution workspace separately from learned skill ownership`() {
        val session = session(SessionTreeAccess(target, checkout))
        val state = AiStudioScreenState(
            sessions = persistentListOf(session.toUi()),
            panes = persistentListOf(PaneUi(0, sessionId = session.id)),
        )
        val scope = state.composerScope(0)
        assertEquals(checkout, scope.workspace)
        assertEquals(project, scope.project)
        assertEquals(target, scope.target)
    }

    @Test
    fun `a worktree without a ready checkout never scans the original project`() {
        val session = session(null)
        val state = AiStudioScreenState(
            sessions = persistentListOf(session.toUi()),
            panes = persistentListOf(PaneUi(0, sessionId = session.id)),
        )
        assertNull(state.composerScope(0).workspace)
        assertEquals(project, state.composerScope(0).project)
        val fresh = state.copy(panes = persistentListOf(PaneUi(0, projectId = project.value, isWorktree = true)))
        assertNull(fresh.composerScope(0).workspace)
        assertEquals(project, fresh.composerScope(0).project)
    }

    @Test
    fun `a sandbox without an execution workspace resolves the source project as a pending sandbox`() {
        val state = AiStudioScreenState(
            panes = persistentListOf(PaneUi(0, projectId = project.value, isWorktree = true, isSandbox = true)),
        )
        val scope = state.composerScope(0)
        assertEquals(project, scope.workspace)
        assertEquals(project, scope.project)
        assertTrue(scope.isSandboxPending)
    }

    @Test
    fun `a plain worktree without an execution workspace stays without a workspace and is not pending`() {
        val state = AiStudioScreenState(
            panes = persistentListOf(PaneUi(0, projectId = project.value, isWorktree = true)),
        )
        val scope = state.composerScope(0)
        assertNull(scope.workspace)
        assertFalse(scope.isSandboxPending)
    }

    @Test
    fun `a sandbox with an execution workspace uses it and is no longer pending`() {
        val session = session(SessionTreeAccess(target, checkout))
        val state = AiStudioScreenState(
            sessions = persistentListOf(session.toUi()),
            panes = persistentListOf(PaneUi(0, sessionId = session.id, isWorktree = true, isSandbox = true)),
        )
        val scope = state.composerScope(0)
        assertEquals(checkout, scope.workspace)
        assertFalse(scope.isSandboxPending)
    }

    private fun session(access: SessionTreeAccess?) = StudioSession(
        id = "chat",
        projectId = project.value,
        title = "Worktree chat",
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        modelId = target.studioModelId(),
        isWorktree = true,
        treeAccess = access,
    )
}
