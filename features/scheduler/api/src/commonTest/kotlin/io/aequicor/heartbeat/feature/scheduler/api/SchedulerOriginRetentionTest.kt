package io.aequicor.heartbeat.feature.scheduler.api

import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.time.Instant

class SchedulerOriginRetentionTest {
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

    @Test
    fun `rejection keeps immutable host origin without note`() {
        val ready = SchedulerState.Ready(listOf(wake))
        val rejected = SchedulerOutput.Rejected(request.id, WakeRejection.Duplicate, origin, initiator)
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
            outputs = listOf(SchedulerOutput.Cancelled(listOf(request.id), listOf(initiator.origin(), origin))),
        )
    }

    @Test
    fun `each cancellation route retains removed origins without note`() {
        val intents = listOf(
            SchedulerIntent.Public.Cancel(request.id),
            SchedulerIntent.Public.CancelSession(request.session),
            SchedulerIntent.Public.CancelOwned("harness"),
        )
        val cancelled = SchedulerOutput.Cancelled(listOf(request.id), listOf(origin, initiator.origin()))
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
