package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionIntent.Internal
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NativeBridgeTest {
    private val request = PromptRequest(RequestId("r1"), listOf(ContentPart.Text("hi")))
    private val local = Turn(TurnId("local"), request.id, TestTarget)
    private val native = Turn(TurnId("native"), request.id, TestTarget)
    private val submitting = ActiveSessionState.Submitting(request, local)

    @Test
    fun `native turn with the pending request id is correlated and accepts the submission`() {
        val correlation = TurnCorrelation()
        val localized = correlation.localize(ActiveSessionState.Running(native), submitting)

        assertEquals(ActiveSessionState.Running(local), localized)
        assertEquals(listOf(Internal.Accepted(local.id)), reconcile(submitting, localized))
        assertEquals(native.id, correlation.native(local.id))
    }

    @Test
    fun `early completion or permission proves acceptance`() {
        val done = ActiveSessionState.Ready(local.copy(outcome = TurnOutcome.Completed))
        assertEquals(listOf(Internal.Finished(local.id, TurnOutcome.Completed)), reconcile(submitting, done))

        val request = permission("p1", local.id)
        val asking = ActiveSessionState.AwaitingUserAction(local, listOf(request))
        assertEquals(listOf(Internal.PermissionNeeded(request)), reconcile(submitting, asking))
    }

    @Test
    fun `permission requests and acknowledgements follow the native pending set`() {
        val first = permission("p1", local.id)
        val second = permission("p2", local.id)
        val awaiting = ActiveSessionState.AwaitingUserAction(local, listOf(first), setOf(first.id))

        assertEquals(
            listOf(Internal.PermissionNeeded(second)),
            reconcile(awaiting, ActiveSessionState.AwaitingUserAction(local, listOf(first, second))),
        )
        assertEquals(
            listOf(Internal.PermissionResolved(local.id, first.id)),
            reconcile(awaiting, ActiveSessionState.Running(local.copy(resolvedPermissions = setOf(first.id)))),
        )
    }

    @Test
    fun `a vanished turn without an outcome is never assumed finished`() {
        val running = ActiveSessionState.Running(local)
        val other = Turn(TurnId("other"), null, TestTarget, TurnOutcome.Completed)

        assertEquals(
            listOf(Internal.Failed(local.id, EngineFailure.Session(SessionFailureReason.Changed))),
            reconcile(running, ActiveSessionState.Ready(other)),
        )
        assertEquals(
            listOf(Internal.Failed(null, EngineFailure.Session(SessionFailureReason.Changed))),
            reconcile(ActiveSessionState.Ready(), ActiveSessionState.Running(other.copy(outcome = null))),
        )
        assertTrue(reconcile(ActiveSessionState.Ready(), ActiveSessionState.Ready(other)).isEmpty())
    }

    @Test
    fun `unavailable handles only record outcomes of their remembered turn`() {
        val unavailable = ActiveSessionState.Unavailable(EngineFailure.Unknown(), local)
        val done = local.copy(outcome = TurnOutcome.Cancelled)
        assertEquals(
            listOf(Internal.Finished(local.id, TurnOutcome.Cancelled)),
            reconcile(unavailable, ActiveSessionState.Ready(done)),
        )
        assertTrue(reconcile(unavailable, ActiveSessionState.Running(local)).isEmpty())
    }

    @Test
    fun `attached native states become valid initial states`() {
        assertEquals(
            ActiveSessionState.Unavailable(
                EngineFailure.Request(RequestFailureReason.OutcomeUnknown, request.id),
                native,
            ),
            ActiveSessionState.Submitting(request, native).asInitial(),
        )
        assertEquals(ActiveSessionState.Running(native), ActiveSessionState.Interrupting(native).asInitial())
        assertFailsWith<EngineException> { ActiveSessionState.Closed.asInitial() }
    }

    @Test
    fun `an unreachable native side makes the handle unavailable even while it still lists the turn`() {
        val outage = EngineFailure.Unknown()
        assertEquals(
            listOf(Internal.Failed(local.id, outage)),
            reconcile(ActiveSessionState.Running(local), ActiveSessionState.Unavailable(outage, local)),
        )
    }
}
