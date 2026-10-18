package io.aequicor.heartbeat.feature.worktreemode.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VirtualSandboxStateTest {
    @Test
    fun `pause preserves accepted identity and continuation creates a new request once`() {
        val state = VirtualSandboxState(baseCommit = "base", snapshot = "view")
        val request = RequestId("accepted")
        val original = WorktreeTask(
            "chat",
            WorkspaceRef("project"),
            virtualSandbox = state,
            phase = WorktreePhase.Idle,
            executionWorkspace = WorkspaceRef("sandbox-chat"),
        )
            .transition(WorktreeIntent.Public.RunStarted("chat", request))
        val paused = original.virtualChanged(state.copy(phase = VirtualSandboxPhase.Pausing, revision = 1))
        assertEquals(request, paused.run?.request)
        assertTrue(paused.pendingVirtualContinuation)
        assertEquals(listOf(request), paused.virtualInterruptedRequests)
        assertNull(paused.continueVirtual("fresh", "continue").actionRequest)
        val stopped = paused.copy(run = paused.run?.copy(outcome = TurnOutcome.Cancelled))
        val resumed = stopped.virtualChanged(state.copy(revision = 2))
        val continued = resumed.continueVirtual("fresh", "continue")
        assertEquals("fresh", continued.actionRequest?.operation)
        val started = continued.transition(WorktreeIntent.Public.RunStarted("chat", RequestId("fresh")))
        assertEquals("fresh", started.virtualContinuations[request.value])
        val pausedAgain = started.virtualChanged(state.copy(phase = VirtualSandboxPhase.Pausing, revision = 3))
        val restarted = pausedAgain.copy(run = pausedAgain.run?.copy(outcome = TurnOutcome.Cancelled))
            .virtualChanged(state.copy(revision = 4)).continueVirtual("second", "continue")
            .transition(WorktreeIntent.Public.RunStarted("chat", RequestId("second")))
        assertEquals("second", restarted.virtualContinuations[request.value])
        assertEquals("second", restarted.virtualContinuations["fresh"])

        assertEquals(continued, continued.continueVirtual("duplicate", "continue"))
        assertEquals(continued, continued.virtualChanged(state.copy(revision = 1)))
    }

    @Test
    fun `resolver interruption also retains a fresh continuation marker`() {
        val resolving = VirtualSandboxState(
            phase = VirtualSandboxPhase.Conflicted,
            baseCommit = "base",
            snapshot = "view",
            isResolving = true,
        )
        val task = WorktreeTask(
            "chat",
            WorkspaceRef("project"),
            virtualSandbox = resolving,
            phase = WorktreePhase.Idle,
            executionWorkspace = WorkspaceRef("sandbox-chat"),
        )
            .transition(WorktreeIntent.Public.RunStarted("chat", RequestId("resolver")))
        assertTrue(
            task.virtualChanged(
                resolving.copy(
                    phase = VirtualSandboxPhase.Pausing,
                    isResolving = false,
                    revision = 1,
                ),
            ).pendingVirtualContinuation,
        )
    }
}
