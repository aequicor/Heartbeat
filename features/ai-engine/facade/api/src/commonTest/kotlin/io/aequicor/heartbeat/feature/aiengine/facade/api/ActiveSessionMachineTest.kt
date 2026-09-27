package io.aequicor.heartbeat.feature.aiengine.facade.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.core.statemachine.toMermaid
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActiveSessionMachineTest {
    private val spec = activeSessionMachineSpec(ActiveSessionMachineKey("test"), ActiveSessionState.Ready())

    @Test
    fun `submission waits for native acceptance and rejects another concurrent prompt`() {
        val submitting = ActiveSessionState.Submitting(TestPrompt, TestTurn)
        spec.assertTransition(
            ActiveSessionState.Ready(),
            ActiveSessionIntent.Public.Submit(TestPrompt, TestTurn),
            submitting,
            effects = listOf(ActiveSessionEffect.Submit(TestPrompt, TestTurn)),
        )
        spec.assertIgnored(submitting, ActiveSessionIntent.Public.Submit(TestPrompt, TestTurn))
        spec.assertIgnored(submitting, ActiveSessionIntent.Internal.Accepted(TurnId("foreign")))
        spec.assertTransition(
            submitting,
            ActiveSessionIntent.Internal.Accepted(TestTurn.id),
            ActiveSessionState.Running(TestTurn),
            outputs = listOf(ActiveSessionOutput.Accepted(TestTurn)),
        )
    }

    @Test
    fun `submission failure is not an accepted turn failure`() {
        val failure = EngineFailure.Request(RequestFailureReason.OutcomeUnknown, TestPrompt.id)
        spec.assertTransition(
            ActiveSessionState.Submitting(TestPrompt, TestTurn),
            ActiveSessionIntent.Internal.Failed(TestTurn.id, failure),
            ActiveSessionState.Unavailable(failure, TestTurn),
            outputs = listOf(ActiveSessionOutput.SubmissionFailed(TestPrompt.id, failure)),
        )
    }

    @Test
    fun `native outcomes finish accepted turns exactly once`() {
        val states = listOf(
            ActiveSessionState.Running(TestTurn),
            ActiveSessionState.Interrupting(TestTurn),
            ActiveSessionState.AwaitingUserAction(TestTurn, listOf(TestPermission)),
        )
        val outcomes = listOf(TurnOutcome.Completed, TurnOutcome.Cancelled, TurnOutcome.Failed(EngineFailure.Unknown()))
        states.forEach { state ->
            outcomes.forEach { outcome ->
                val finished = TestTurn.copy(outcome = outcome)
                val event = ActiveSessionIntent.Internal.Finished(TestTurn.id, outcome)
                spec.assertTransition(
                    state,
                    event,
                    ActiveSessionState.Ready(finished),
                    outputs = listOf(ActiveSessionOutput.Finished(finished)),
                )
                spec.assertIgnored(ActiveSessionState.Ready(finished), event)
                spec.assertIgnored(state, ActiveSessionIntent.Internal.Finished(TurnId("foreign"), outcome))
            }
        }
    }

    @Test
    fun `cancel waits for native confirmation and permits a completion race`() {
        listOf(
            ActiveSessionState.Running(TestTurn),
            ActiveSessionState.AwaitingUserAction(TestTurn, listOf(TestPermission)),
        ).forEach { state ->
            spec.assertIgnored(state, ActiveSessionIntent.Public.Cancel(TurnId("foreign")))
            spec.assertTransition(
                state,
                ActiveSessionIntent.Public.Cancel(TestTurn.id),
                ActiveSessionState.Interrupting(TestTurn),
                effects = listOf(ActiveSessionEffect.Cancel(TestTurn.id)),
            )
        }
        spec.assertIgnored(ActiveSessionState.Interrupting(TestTurn), ActiveSessionIntent.Public.Cancel(TestTurn.id))
    }

    @Test
    fun `failure preserves a possibly active turn and recheck never resubmits`() {
        val failure = EngineFailure.Transport(TransportFailureReason.NetworkUnavailable)
        val unavailable = ActiveSessionState.Unavailable(failure, TestTurn)
        spec.assertTransition(
            ActiveSessionState.Running(TestTurn),
            ActiveSessionIntent.Internal.Failed(TestTurn.id, failure),
            unavailable,
        )
        spec.assertTransition(
            unavailable,
            ActiveSessionIntent.Public.Recheck,
            unavailable,
            effects = listOf(ActiveSessionEffect.Recheck(TestTurn.id)),
        )
        spec.assertIgnored(unavailable, ActiveSessionIntent.Public.Submit(TestPrompt, TestTurn))
        val completed = TestTurn.copy(outcome = TurnOutcome.Unknown)
        spec.assertTransition(
            unavailable,
            ActiveSessionIntent.Internal.Synchronized(null),
            ActiveSessionState.Ready(completed),
            outputs = listOf(ActiveSessionOutput.Finished(completed)),
        )
        spec.assertTransition(
            unavailable,
            ActiveSessionIntent.Internal.Synchronized(TestTurn),
            ActiveSessionState.Running(TestTurn),
        )
        spec.assertTransition(
            unavailable,
            ActiveSessionIntent.Internal.Synchronized(TestTurn, listOf(TestPermission)),
            ActiveSessionState.AwaitingUserAction(TestTurn, listOf(TestPermission)),
        )
        val probeFailure = EngineFailure.Engine(EngineFailureReason.Unavailable)
        spec.assertTransition(
            unavailable,
            ActiveSessionIntent.Internal.Failed(null, probeFailure),
            unavailable.copy(failure = probeFailure),
        )
    }

    @Test
    fun `closing every live state only releases its handle and closed is terminal`() {
        val states = listOf(
            ActiveSessionState.Ready(),
            ActiveSessionState.Running(TestTurn),
            ActiveSessionState.AwaitingUserAction(TestTurn, listOf(TestPermission)),
            ActiveSessionState.Interrupting(TestTurn),
            ActiveSessionState.Unavailable(EngineFailure.Unknown(), TestTurn),
        )
        states.forEach { state ->
            spec.assertTransition(
                state,
                ActiveSessionIntent.Public.Close,
                ActiveSessionState.Closing(),
                effects = listOf(ActiveSessionEffect.Release),
            )
        }
        spec.assertTransition(ActiveSessionState.Closed, ActiveSessionIntent.Public.Close, ActiveSessionState.Closed)
        spec.assertIgnored(ActiveSessionState.Closed, ActiveSessionIntent.Public.Submit(TestPrompt, TestTurn))
        spec.assertIgnored(
            ActiveSessionState.Closed,
            ActiveSessionIntent.Internal.Failed(null, EngineFailure.Unknown()),
        )
        assertTrue(spec.toMermaid().contains("Closed"))
    }

    @Test
    fun `effect failures preserve domain classification and turn escaped cancellation into a failure`() {
        val failure = EngineFailure.QuotaExceeded(LimitScope.Binding(TestTarget.binding))
        val effect = ActiveSessionEffect.Submit(TestPrompt, TestTurn)
        assertEquals(
            ActiveSessionIntent.Internal.Failed(TestTurn.id, failure),
            spec.onEffectFailure(effect, EngineException(failure)),
        )
        assertEquals(
            ActiveSessionIntent.Internal.Failed(
                TestTurn.id,
                EngineFailure.Request(RequestFailureReason.OutcomeUnknown, TestPrompt.id),
            ),
            spec.onEffectFailure(effect, IllegalStateException("private native diagnostic")),
        )
        assertEquals(
            ActiveSessionIntent.Internal.Failed(
                TestTurn.id,
                EngineFailure.Request(RequestFailureReason.OutcomeUnknown, TestPrompt.id),
            ),
            spec.onEffectFailure(effect, CancellationException("timeout inside effect")),
        )
        assertEquals(
            ActiveSessionIntent.Internal.Failed(TestTurn.id, EngineFailure.Unknown()),
            spec.onEffectFailure(ActiveSessionEffect.Cancel(TestTurn.id), CancellationException("timeout")),
        )
        assertEquals(
            ActiveSessionIntent.Internal.Failed(null, EngineFailure.Unknown()),
            spec.onEffectFailure(ActiveSessionEffect.Release, CancellationException("timeout")),
        )
    }
}
