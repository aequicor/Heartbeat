package io.aequicor.heartbeat.feature.scheduler.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.core.statemachine.toMermaid
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class SchedulerMachineTest {
    private val spec = SchedulerMachineSpec
    private val now = Instant.parse("2026-10-05T10:00:00Z")
    private val session = SessionRef(EngineId("pi"), SessionSourceId("local"), "native-1")
    private val other = SessionRef(EngineId("pi"), SessionSourceId("local"), "native-2")
    private val done = EventKeys.custom("build.done")

    private fun request(
        id: String,
        events: Set<EventKey> = setOf(done),
        deadline: Instant? = null,
        target: SessionRef = session,
    ) = WakeRequest(
        WakeId(id),
        target,
        null,
        WakeCondition(events, deadline),
        "continue",
        WakeOrigin.Agent(TurnId("t1")),
    )

    private fun wake(id: String, deadline: Instant? = null, target: SessionRef = session) =
        ScheduledWake(request(id, deadline = deadline, target = target), now)

    private fun event(key: EventKey) = BusEvent(key, EventOrigin.Host, now, "ok")

    @Test
    fun `loading ends with stored wakes or an empty schedule`() {
        assertEquals(SchedulerState.Loading, spec.initial)
        spec.assertTransition(
            SchedulerState.Loading,
            SchedulerIntent.Internal.Start,
            SchedulerState.Loading,
            effects = listOf(SchedulerEffect.Load),
        )
        spec.assertIgnored(SchedulerState.Ready(), SchedulerIntent.Internal.Start)
        spec.assertTransition(
            SchedulerState.Loading,
            SchedulerIntent.Internal.Loaded(listOf(wake("w1"))),
            SchedulerState.Ready(listOf(wake("w1"))),
        )
        spec.assertTransition(SchedulerState.Loading, SchedulerIntent.Internal.LoadFailed, SchedulerState.Ready())
        spec.assertIgnored(SchedulerState.Loading, SchedulerIntent.Public.Schedule(request("w1"), now))
    }

    @Test
    fun `schedule adds a wake and persists it`() {
        spec.assertTransition(
            SchedulerState.Ready(),
            SchedulerIntent.Public.Schedule(request("w1"), now),
            SchedulerState.Ready(listOf(wake("w1")), revision = 1),
            effects = listOf(SchedulerEffect.Persist(listOf(wake("w1")), 1)),
            outputs = listOf(SchedulerOutput.Scheduled(wake("w1"))),
        )
    }

    @Test
    fun `schedule is rejected over limits`() {
        val ready = SchedulerState.Ready(listOf(wake("w1")))
        spec.assertTransition(
            ready,
            SchedulerIntent.Public.Schedule(request("w1"), now),
            ready,
            outputs = listOf(SchedulerOutput.Rejected(WakeId("w1"), WakeRejection.Duplicate)),
        )
        val full = SchedulerState.Ready((1..SchedulerLimits.MAX_PER_SESSION).map { wake("w$it") })
        spec.assertTransition(
            full,
            SchedulerIntent.Public.Schedule(request("next"), now),
            full,
            outputs = listOf(SchedulerOutput.Rejected(WakeId("next"), WakeRejection.SessionLimit)),
        )
        val far = request("far", events = emptySet(), deadline = now + SchedulerLimits.HORIZON + 1.days)
        spec.assertTransition(
            ready,
            SchedulerIntent.Public.Schedule(far, now),
            ready,
            outputs = listOf(SchedulerOutput.Rejected(WakeId("far"), WakeRejection.TooFar)),
        )
    }

    @Test
    fun `cancel removes only the session's own pending wake`() {
        val ready = SchedulerState.Ready(listOf(wake("w1"), wake("w2", target = other)))
        spec.assertTransition(
            ready,
            SchedulerIntent.Public.Cancel(WakeId("w1"), session),
            SchedulerState.Ready(listOf(wake("w2", target = other)), revision = 1),
            effects = listOf(SchedulerEffect.Persist(listOf(wake("w2", target = other)), 1)),
            outputs = listOf(SchedulerOutput.Cancelled(listOf(WakeId("w1")))),
        )
        spec.assertIgnored(ready, SchedulerIntent.Public.Cancel(WakeId("w2"), session))
        spec.assertIgnored(ready, SchedulerIntent.Public.Cancel(WakeId("missing")))
        spec.assertIgnored(ready.copy(delivering = setOf(WakeId("w1"))), SchedulerIntent.Public.Cancel(WakeId("w1")))
    }

    @Test
    fun `cancel session removes all its pending wakes`() {
        val ready = SchedulerState.Ready(listOf(wake("w1"), wake("w2"), wake("w3", target = other)))
        spec.assertTransition(
            ready,
            SchedulerIntent.Public.CancelSession(session),
            SchedulerState.Ready(listOf(wake("w3", target = other)), revision = 1),
            effects = listOf(SchedulerEffect.Persist(listOf(wake("w3", target = other)), 1)),
            outputs = listOf(SchedulerOutput.Cancelled(listOf(WakeId("w1"), WakeId("w2")))),
        )
    }

    @Test
    fun `matching event delivers every waiting session once`() {
        val ready = SchedulerState.Ready(listOf(wake("w1"), wake("w2", target = other)))
        val observed = event(done)
        spec.assertTransition(
            ready,
            SchedulerIntent.Internal.Observed(observed),
            ready.copy(delivering = setOf(WakeId("w1"), WakeId("w2"))),
            effects = listOf(
                SchedulerEffect.Deliver(
                    listOf(
                        WakeDelivery(wake("w1"), WakeReason.Event(observed)),
                        WakeDelivery(wake("w2", target = other), WakeReason.Event(observed)),
                    ),
                ),
            ),
        )
        spec.assertIgnored(ready, SchedulerIntent.Internal.Observed(event(EventKeys.NetworkLost)))
        spec.assertIgnored(
            ready.copy(delivering = setOf(WakeId("w1"), WakeId("w2"))),
            SchedulerIntent.Internal.Observed(observed),
        )
    }

    @Test
    fun `tick delivers due deadlines only`() {
        val due = wake("due", deadline = now - 1.minutes)
        val later = wake("later", deadline = now + 1.minutes)
        val ready = SchedulerState.Ready(listOf(due, later))
        spec.assertTransition(
            ready,
            SchedulerIntent.Internal.Tick(now),
            ready.copy(delivering = setOf(due.id)),
            effects = listOf(SchedulerEffect.Deliver(listOf(WakeDelivery(due, WakeReason.Deadline(now - 1.minutes))))),
        )
        spec.assertIgnored(SchedulerState.Ready(listOf(later)), SchedulerIntent.Internal.Tick(now))
    }

    @Test
    fun `settled deliveries leave the schedule`() {
        val reason = WakeReason.Event(event(done))
        val delivering = SchedulerState.Ready(listOf(wake("w1"), wake("w2")), setOf(WakeId("w1"), WakeId("w2")), 3)
        spec.assertTransition(
            delivering,
            SchedulerIntent.Internal.Delivered(WakeId("w1"), reason),
            SchedulerState.Ready(listOf(wake("w2")), setOf(WakeId("w2")), 4),
            effects = listOf(SchedulerEffect.Persist(listOf(wake("w2")), 4)),
            outputs = listOf(SchedulerOutput.Woke(wake("w1"), reason)),
        )
        spec.assertTransition(
            delivering,
            SchedulerIntent.Internal.DeliveryFailed(listOf(WakeId("w2"), WakeId("gone")), WakeFailure.Engine),
            SchedulerState.Ready(listOf(wake("w1")), setOf(WakeId("w1")), 4),
            effects = listOf(SchedulerEffect.Persist(listOf(wake("w1")), 4)),
            outputs = listOf(SchedulerOutput.DeliveryFailed(listOf(wake("w2")), WakeFailure.Engine)),
        )
        spec.assertIgnored(
            SchedulerState.Ready(listOf(wake("w1"))),
            SchedulerIntent.Internal.Delivered(WakeId("w1"), reason),
        )
    }

    @Test
    fun `effect failures settle their wakes`() {
        val deliver = SchedulerEffect.Deliver(listOf(WakeDelivery(wake("w1"), WakeReason.Deadline(now))))
        assertEquals(
            SchedulerIntent.Internal.DeliveryFailed(listOf(WakeId("w1")), WakeFailure.Unknown),
            spec.onEffectFailure(deliver, IllegalStateException("boom")),
        )
        assertEquals(
            SchedulerIntent.Internal.LoadFailed,
            spec.onEffectFailure(SchedulerEffect.Load, IllegalStateException()),
        )
        assertEquals(null, spec.onEffectFailure(SchedulerEffect.Persist(emptyList(), 1), IllegalStateException()))
    }

    @Test
    fun `diagram lists every state`() {
        val diagram = spec.toMermaid()
        assertTrue("Loading" in diagram && "Ready" in diagram)
    }
}
