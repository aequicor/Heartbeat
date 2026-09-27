package io.aequicor.heartbeat.feature.aiengine.facade.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SessionReconciliationTest {
    private val spec = activeSessionMachineSpec(ActiveSessionMachineKey("reconciliation"), ActiveSessionState.Ready())
    private val failure = EngineFailure.Transport(TransportFailureReason.NetworkUnavailable)
    private val unavailable = ActiveSessionState.Unavailable(failure, TestTurn)
    private val next = TestTurn.copy(id = TurnId("next"), request = RequestId("next"))
    private val permission = TestPermission.copy(turn = next.id)

    @Test
    fun `recovered outcomes finish the old turn before following idle running or pending snapshots`() {
        val outcomes = listOf(TurnOutcome.Completed, TurnOutcome.Cancelled, TurnOutcome.Failed(failure))
        outcomes.forEach { outcome ->
            val finished = ActiveSessionIntent.Internal.Finished(TestTurn.id, outcome)
            val old = TestTurn.copy(outcome = outcome)
            val snapshots = listOf(
                ActiveSessionIntent.Internal.Synchronized(null, completed = finished) to ActiveSessionState.Ready(old),
                ActiveSessionIntent.Internal.Synchronized(
                    next,
                    completed = finished,
                ) to ActiveSessionState.Running(next),
                ActiveSessionIntent.Internal.Synchronized(next, listOf(permission), finished) to
                    ActiveSessionState.AwaitingUserAction(next, listOf(permission)),
            )
            snapshots.forEach { (snapshot, expected) ->
                spec.assertTransition(
                    unavailable,
                    snapshot,
                    expected,
                    outputs = listOf(ActiveSessionOutput.Finished(old)),
                )
                spec.assertIgnored(expected, snapshot)
                spec.assertIgnored(expected, finished)
            }
        }
    }

    @Test
    fun `missing outcomes are explicit when a different turn replaced the remembered one`() {
        val old = TestTurn.copy(outcome = TurnOutcome.Unknown)
        spec.assertTransition(
            unavailable,
            ActiveSessionIntent.Internal.Synchronized(next),
            ActiveSessionState.Running(next),
            outputs = listOf(ActiveSessionOutput.Finished(old)),
        )
        spec.assertTransition(
            unavailable,
            ActiveSessionIntent.Internal.Synchronized(next, listOf(permission)),
            ActiveSessionState.AwaitingUserAction(next, listOf(permission)),
            outputs = listOf(ActiveSessionOutput.Finished(old)),
        )
    }

    @Test
    fun `a foreign completion cannot supply the remembered turn outcome`() {
        val foreign = ActiveSessionIntent.Internal.Finished(TurnId("foreign"), TurnOutcome.Completed)
        val old = TestTurn.copy(outcome = TurnOutcome.Unknown)
        spec.assertTransition(
            unavailable,
            ActiveSessionIntent.Internal.Synchronized(null, completed = foreign),
            ActiveSessionState.Ready(old),
            outputs = listOf(ActiveSessionOutput.Finished(old)),
        )
    }

    @Test
    fun `the same active turn is resumed without a terminal notification`() {
        val foreign = ActiveSessionIntent.Internal.Finished(TurnId("foreign"), TurnOutcome.Completed)
        spec.assertTransition(
            unavailable,
            ActiveSessionIntent.Internal.Synchronized(TestTurn, completed = foreign),
            ActiveSessionState.Running(TestTurn),
        )
        spec.assertTransition(
            unavailable,
            ActiveSessionIntent.Internal.Synchronized(TestTurn, listOf(TestPermission), foreign),
            ActiveSessionState.AwaitingUserAction(TestTurn, listOf(TestPermission)),
        )
    }

    @Test
    fun `recheck identifies the remembered turn for native outcome lookup`() {
        spec.assertTransition(
            unavailable,
            ActiveSessionIntent.Public.Recheck,
            unavailable,
            effects = listOf(ActiveSessionEffect.Recheck(TestTurn.id)),
        )
        val idle = ActiveSessionState.Unavailable(failure)
        spec.assertTransition(
            idle,
            ActiveSessionIntent.Public.Recheck,
            idle,
            effects = listOf(ActiveSessionEffect.Recheck(null)),
        )
    }

    @Test
    fun `completion received before reconciliation is not emitted a second time`() {
        val finished = ActiveSessionIntent.Internal.Finished(TestTurn.id, TurnOutcome.Completed)
        val old = TestTurn.copy(outcome = finished.outcome)
        spec.assertTransition(
            unavailable.copy(activeTurn = null, lastTurn = old),
            ActiveSessionIntent.Internal.Synchronized(null, completed = finished),
            ActiveSessionState.Ready(old),
        )
    }

    @Test
    fun `a snapshot cannot claim the same turn is active and completed`() {
        assertFailsWith<IllegalArgumentException> {
            ActiveSessionIntent.Internal.Synchronized(
                TestTurn,
                completed = ActiveSessionIntent.Internal.Finished(TestTurn.id, TurnOutcome.Completed),
            )
        }
    }

    @Test
    fun `an unknown terminal outcome is retained in serialized state`() {
        val state: ActiveSessionState = ActiveSessionState.Ready(TestTurn.copy(outcome = TurnOutcome.Unknown))
        assertEquals(state, Json.decodeFromString<ActiveSessionState>(Json.encodeToString(state)))
    }
}
