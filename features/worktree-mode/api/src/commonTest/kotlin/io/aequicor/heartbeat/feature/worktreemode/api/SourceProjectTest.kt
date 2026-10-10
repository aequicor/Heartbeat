package io.aequicor.heartbeat.feature.worktreemode.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SourceProjectTest {
    @Test
    fun `checkout identity resolves to its original project across lifecycle states`() {
        val project = WorkspaceRef("project")
        val checkout = WorkspaceRef("checkout")
        WorktreePhase.entries.forEach { phase ->
            val task = WorktreeTask("chat", project, executionWorkspace = checkout, phase = phase)
            assertEquals(project, WorktreeState.Ready(mapOf("chat" to task)).sourceProjectOf(checkout))
        }
    }

    @Test
    fun `unknown and ordinary workspaces require catalog validation`() {
        val task = WorktreeTask("chat", WorkspaceRef("project"))
        val state = WorktreeState.Ready(mapOf("chat" to task))
        assertNull(state.sourceProjectOf(task.project))
        assertNull(state.sourceProjectOf(WorkspaceRef("unknown")))
        assertNull(WorktreeState.Ready().sourceProjectOf(task.project))
    }
}
