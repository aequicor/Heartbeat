package io.aequicor.heartbeat.feature.aiengine.facade.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class SessionEventOrderingTest {
    private val spec = activeSessionMachineSpec(ActiveSessionMachineKey("ordering"), ActiveSessionState.Ready())
    private val submitting = ActiveSessionState.Submitting(TestPrompt, TestTurn)

    @Test
    fun `terminal events before acceptance finish the submission exactly once`() {
        val outcomes = listOf(TurnOutcome.Completed, TurnOutcome.Cancelled, TurnOutcome.Failed(EngineFailure.Unknown()))
        outcomes.forEach { outcome ->
            val completed = TestTurn.copy(outcome = outcome)
            val ready = ActiveSessionState.Ready(completed)
            val finished = ActiveSessionIntent.Internal.Finished(TestTurn.id, outcome)
            spec.assertTransition(
                submitting,
                finished,
                ready,
                outputs = listOf(ActiveSessionOutput.Accepted(TestTurn), ActiveSessionOutput.Finished(completed)),
            )
            spec.assertIgnored(ready, ActiveSessionIntent.Internal.Accepted(TestTurn.id))
            spec.assertIgnored(ready, finished)
        }
    }

    @Test
    fun `permission before acceptance becomes pending without waiting for the delayed acknowledgement`() {
        val waiting = ActiveSessionState.AwaitingUserAction(TestTurn, listOf(TestPermission))
        spec.assertTransition(
            submitting,
            ActiveSessionIntent.Internal.PermissionNeeded(TestPermission),
            waiting,
            outputs = listOf(ActiveSessionOutput.Accepted(TestTurn)),
        )
        spec.assertIgnored(waiting, ActiveSessionIntent.Internal.Accepted(TestTurn.id))
        spec.assertTransition(waiting, ActiveSessionIntent.Internal.PermissionNeeded(TestPermission), waiting)
        val completed = TestTurn.copy(outcome = TurnOutcome.Completed)
        spec.assertTransition(
            waiting,
            ActiveSessionIntent.Internal.Finished(TestTurn.id, TurnOutcome.Completed),
            ActiveSessionState.Ready(completed),
            outputs = listOf(ActiveSessionOutput.Finished(completed)),
        )
    }

    @Test
    fun `early events from another turn do not acknowledge or change a submission`() {
        spec.assertIgnored(submitting, ActiveSessionIntent.Internal.Finished(TurnId("foreign"), TurnOutcome.Completed))
        spec.assertIgnored(
            submitting,
            ActiveSessionIntent.Internal.PermissionNeeded(TestPermission.copy(turn = TurnId("foreign"))),
        )
    }

    @Test
    fun `idle reconciliation reports the remembered turn instead of silently losing it`() {
        val unavailable = ActiveSessionState.Unavailable(EngineFailure.Unknown(), TestTurn)
        val transition = assertNotNull(spec.resolve(unavailable, ActiveSessionIntent.Internal.Synchronized(null)))
        val ready = assertIs<ActiveSessionState.Ready>(transition.to)
        val finished = assertIs<ActiveSessionOutput.Finished>(transition.outputs.singleOrNull())
        assertEquals(TestTurn.id, finished.turn.id)
        assertEquals(finished.turn, ready.lastTurn)
    }
}
