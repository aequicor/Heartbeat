package io.aequicor.heartbeat.feature.aiengine.facade.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SessionRecoveryTest {
    private val key = ActiveSessionMachineKey("recovery")
    private val spec = activeSessionMachineSpec(key, ActiveSessionState.Ready())
    private val failure = EngineFailure.Transport(TransportFailureReason.NetworkUnavailable)

    @Test
    fun `external running and pending sessions attach with authoritative state`() {
        val running = ActiveSessionState.Running(TestTurn)
        val pending = ActiveSessionState.AwaitingUserAction(TestTurn, listOf(TestPermission))
        listOf(running, pending).forEach { snapshot ->
            val attached = activeSessionMachineSpec(key, snapshot)
            assertEquals(snapshot, attached.initial)
            attached.assertIgnored(attached.initial, ActiveSessionIntent.Public.Submit(TestPrompt, TestTurn))
        }
        assertFailsWith<IllegalArgumentException> {
            activeSessionMachineSpec(key, ActiveSessionState.Submitting(TestPrompt, TestTurn))
        }
    }

    @Test
    fun `late terminal result survives failure and subsequent reconciliation exactly once`() {
        val before = ActiveSessionState.Unavailable(failure, TestTurn)
        val completed = TestTurn.copy(outcome = TurnOutcome.Completed)
        val after = ActiveSessionState.Unavailable(failure, lastTurn = completed)
        val event = ActiveSessionIntent.Internal.Finished(TestTurn.id, TurnOutcome.Completed)
        spec.assertTransition(before, event, after, outputs = listOf(ActiveSessionOutput.Finished(completed)))
        spec.assertIgnored(after, event)
        spec.assertTransition(
            after,
            ActiveSessionIntent.Internal.Synchronized(null),
            ActiveSessionState.Ready(completed),
        )
    }

    @Test
    fun `stale recheck cannot revive a turn after confirmed completion`() {
        val completed = TestTurn.copy(outcome = TurnOutcome.Completed)
        val unavailable = ActiveSessionState.Unavailable(failure, lastTurn = completed)
        spec.assertIgnored(unavailable, ActiveSessionIntent.Internal.Synchronized(TestTurn))
        spec.assertIgnored(unavailable, ActiveSessionIntent.Internal.Synchronized(TestTurn, listOf(TestPermission)))
    }

    @Test
    fun `an idle failure preserves the last completed result for late observers`() {
        val completed = TestTurn.copy(outcome = TurnOutcome.Completed)
        spec.assertTransition(
            ActiveSessionState.Ready(completed),
            ActiveSessionIntent.Internal.Failed(null, failure),
            ActiveSessionState.Unavailable(failure, lastTurn = completed),
        )
    }

    @Test
    fun `acknowledged permissions stay resolved after delayed notifications and reconnect`() {
        val turn = TestTurn.copy(resolvedPermissions = setOf(TestPermission.id))
        val running = ActiveSessionState.Running(turn)
        val event = ActiveSessionIntent.Internal.PermissionNeeded(TestPermission)
        spec.assertIgnored(running, event)
        val second = TestPermission.copy(id = PermissionRequestId("second"))
        spec.assertIgnored(ActiveSessionState.AwaitingUserAction(turn, listOf(second)), event)
        spec.assertTransition(
            ActiveSessionState.Unavailable(failure, turn),
            ActiveSessionIntent.Internal.Synchronized(turn),
            running,
        )
        assertFailsWith<IllegalArgumentException> {
            ActiveSessionIntent.Internal.Synchronized(
                turn,
                listOf(TestPermission),
            )
        }
    }

    @Test
    fun `release failure is observable and closing can retry without cancelling native execution`() {
        val closing = ActiveSessionState.Closing()
        val failed = ActiveSessionState.Closing(failure)
        spec.assertIgnored(closing, ActiveSessionIntent.Public.Close)
        spec.assertTransition(closing, ActiveSessionIntent.Internal.Failed(null, failure), failed)
        spec.assertTransition(
            failed,
            ActiveSessionIntent.Public.Close,
            closing,
            effects = listOf(ActiveSessionEffect.Release),
        )
        spec.assertIgnored(failed, ActiveSessionIntent.Public.Submit(TestPrompt, TestTurn))
        spec.assertTransition(closing, ActiveSessionIntent.Internal.Released, ActiveSessionState.Closed)
    }

    @Test
    fun `closing an unacknowledged submission reports ambiguous delivery instead of hanging send`() {
        val unknown = EngineFailure.Request(RequestFailureReason.OutcomeUnknown, TestPrompt.id)
        spec.assertTransition(
            ActiveSessionState.Submitting(TestPrompt, TestTurn),
            ActiveSessionIntent.Public.Close,
            ActiveSessionState.Closing(),
            effects = listOf(ActiveSessionEffect.Release),
            outputs = listOf(ActiveSessionOutput.SubmissionFailed(TestPrompt.id, unknown)),
        )
    }
}
