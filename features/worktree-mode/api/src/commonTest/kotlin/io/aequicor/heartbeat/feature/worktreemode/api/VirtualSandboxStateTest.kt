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
        assertEquals(request, continued.actionRequest?.continuationOf)
        val delivered = continued.transition(WorktreeIntent.Public.ActionDelivered("chat", "fresh"))
        assertNull(delivered.actionRequest)
        assertEquals(request, delivered.expectedAction?.continuationOf)
        val started = delivered.transition(
            WorktreeIntent.Public.RunStarted("chat", RequestId("fresh"), continuationOf = request),
        )
        assertEquals("fresh", started.virtualContinuations[request.value])
        val pausedAgain = started.virtualChanged(state.copy(phase = VirtualSandboxPhase.Pausing, revision = 3))
        val restarted = pausedAgain.copy(run = pausedAgain.run?.copy(outcome = TurnOutcome.Cancelled))
            .virtualChanged(state.copy(revision = 4)).continueVirtual("second", "continue")
            .transition(
                WorktreeIntent.Public.RunStarted("chat", RequestId("second"), continuationOf = RequestId("fresh")),
            )
        assertEquals("second", restarted.virtualContinuations[request.value])
        assertEquals("second", restarted.virtualContinuations["fresh"])

        assertEquals(continued, continued.continueVirtual("duplicate", "continue"))
        assertEquals(continued, continued.virtualChanged(state.copy(revision = 1)))
    }

    @Test
    fun `independent coding after interruption does not inherit the old request`() {
        val source = RequestId("interrupted")
        val task = interrupted(source)
        val started = task.transition(WorktreeIntent.Public.RunStarted("chat", RequestId("human")))
        assertEquals(RequestId("human"), started.run?.request)
        assertTrue(started.virtualContinuations.isEmpty())
    }

    @Test
    fun `continuation requires matching durable source operation and kind`() {
        val source = RequestId("interrupted")
        val pending = interrupted(source).continueVirtual("fresh", "continue")
            .transition(WorktreeIntent.Public.ActionDelivered("chat", "fresh"))
        val prompt = WorktreeActionRequest("fresh", WorktreeRunKind.Coding, "continue", source)
        assertEquals(pending, pending.actionPrepared(prompt.copy(continuationOf = null)))
        assertEquals(pending, pending.actionPrepared(prompt.copy(continuationOf = RequestId("unrelated"))))
        assertEquals(prompt, pending.actionPrepared(prompt).actionRequest)
        val valid = WorktreeIntent.Public.RunStarted("chat", RequestId("fresh"), continuationOf = source)
        for (invalid in listOf(
            valid.copy(continuationOf = null),
            valid.copy(continuationOf = RequestId("unrelated")),
            valid.copy(request = RequestId("other")),
            valid.copy(kind = WorktreeRunKind.Merge),
        )) {
            assertEquals(pending, pending.transition(invalid))
        }
        assertEquals("fresh", pending.transition(valid).virtualContinuations[source.value])
        val noClaim = pending.copy(expectedAction = null)
        assertEquals(noClaim, noClaim.transition(valid))
        val noInterruption = pending.copy(virtualInterruptedRequests = emptyList())
        assertEquals(noInterruption, noInterruption.transition(valid))
        val uncertain = pending.copy(run = pending.run?.copy(outcome = TurnOutcome.Unknown))
        assertEquals(uncertain, uncertain.transition(valid))
    }

    private fun interrupted(source: RequestId): WorktreeTask = WorktreeTask(
        "chat",
        WorkspaceRef("project"),
        virtualSandbox = VirtualSandboxState(baseCommit = "base", snapshot = "view"),
        phase = WorktreePhase.Idle,
        executionWorkspace = WorkspaceRef("sandbox-chat"),
        run = WorktreeRun(source, outcome = TurnOutcome.Cancelled),
        virtualInterruptedRequests = listOf(source),
        pendingVirtualContinuation = true,
    )

    @Test
    fun `resolving without an interrupted source schedules fresh work without lineage`() {
        val task = interrupted(RequestId("completed")).copy(
            pendingVirtualContinuation = false,
            virtualInterruptedRequests = emptyList(),
            virtualSandbox = VirtualSandboxState(
                phase = VirtualSandboxPhase.Conflicted,
                baseCommit = "base",
                snapshot = "view",
                isResolving = true,
            ),
        ).continueVirtual("resolve", "resolve conflicts")
        assertEquals("resolve", task.actionRequest?.operation)
        assertNull(task.actionRequest?.continuationOf)
        val started = task.transition(WorktreeIntent.Public.RunStarted("chat", RequestId("resolve")))
        assertEquals(RequestId("resolve"), started.run?.request)
        assertTrue(started.virtualContinuations.isEmpty())
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
