package io.aequicor.heartbeat.feature.scheduler.api

import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Instant

class SchedulerOriginRetentionTest {
    @Test
    fun `failure retains trigger ancestry before any native request exists`() {
        val reason = WakeReason.Event(BusEvent(EventKeys.custom("done"), origin, at, "private payload"))
        val effect = SchedulerEffect.Deliver(listOf(WakeDelivery(wake, reason)))
        val failed = SchedulerIntent.Internal.DeliveryFailed(listOf(wake.id), WakeFailure.Unknown, listOf(origin))
        assertEquals(failed, SchedulerMachineSpec.onEffectFailure(effect, IllegalStateException("failure")))
        SchedulerMachineSpec.assertTransition(
            SchedulerState.Ready(listOf(wake), delivering = setOf(wake.id)),
            failed,
            SchedulerState.Ready(revision = 1),
            effects = listOf(SchedulerEffect.Persist(emptyList(), 1)),
            outputs = listOf(SchedulerOutput.DeliveryFailed(listOf(wake), WakeFailure.Unknown, listOf(origin))),
        )
        assertFalse(failed.toString().contains("private"))
    }

    private val at = Instant.fromEpochSeconds(100)
    private val origin = EventOrigin.Feature("harness", "private ancestry")
    private val initiator = RequestInitiator(
        SessionRef(EngineId("engine"), SessionSourceId("source"), "original"),
        RequestId("original-request"),
    )
    private val request = WakeRequest(
        WakeId("owned"),
        SessionRef(EngineId("engine"), SessionSourceId("source"), "session"),
        null,
        WakeCondition(deadline = at),
        "private note",
        WakeOrigin.Feature("harness"),
        ownerFeature = "harness",
        ownerContext = origin.context,
        initiator = initiator,
    )
    private val wake = ScheduledWake(request, at)
    private val target = EventOrigin.Session(request.session, request.id.deliveryRequestId())

    @Test
    fun `rejection keeps immutable host origin without note`() {
        val ready = SchedulerState.Ready(listOf(wake))
        val rejected = SchedulerOutput.Rejected(
            request.id,
            WakeRejection.Duplicate,
            origin,
            initiator,
            RequestInitiator(request.session, request.id.deliveryRequestId()),
        )
        SchedulerMachineSpec.assertTransition(
            ready,
            SchedulerIntent.Public.Schedule(request, at),
            ready,
            outputs = listOf(rejected),
        )
        assertFalse(rejected.toString().contains("private"))
    }

    @Test
    fun `cancelling callback ancestry survives cancellation of an ordinary wake`() {
        val ordinary = wake.copy(request = request.copy(ownerFeature = null, ownerContext = null))
        SchedulerMachineSpec.assertTransition(
            SchedulerState.Ready(listOf(ordinary)),
            SchedulerIntent.Public.Cancel(request.id, cause = origin),
            SchedulerState.Ready(revision = 1),
            effects = listOf(SchedulerEffect.Persist(emptyList(), 1)),
            outputs = listOf(SchedulerOutput.Cancelled(listOf(request.id), listOf(initiator.origin(), target, origin))),
        )
    }

    @Test
    fun `each cancellation route retains removed origins without note`() {
        val intents = listOf(
            SchedulerIntent.Public.Cancel(request.id),
            SchedulerIntent.Public.CancelSession(request.session),
            SchedulerIntent.Public.CancelOwned("harness"),
        )
        val cancelled = SchedulerOutput.Cancelled(listOf(request.id), listOf(origin, initiator.origin(), target))
        intents.forEach { intent ->
            SchedulerMachineSpec.assertTransition(
                SchedulerState.Ready(listOf(wake)),
                intent,
                SchedulerState.Ready(revision = 1),
                effects = listOf(SchedulerEffect.Persist(emptyList(), 1)),
                outputs = listOf(cancelled),
            )
        }
        assertFalse(cancelled.toString().contains("private"))
    }
}
