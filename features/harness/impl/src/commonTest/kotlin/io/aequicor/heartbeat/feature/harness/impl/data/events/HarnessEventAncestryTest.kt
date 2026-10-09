package io.aequicor.heartbeat.feature.harness.impl.data.events

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.impl.data.services.HarnessOwnedContext
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.MemoryHarnessRequestAncestry
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.dispatchSession
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.BusEvent
import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import io.aequicor.heartbeat.feature.scheduler.api.EventOrigin
import io.aequicor.heartbeat.feature.scheduler.api.RequestInitiator
import io.aequicor.heartbeat.feature.scheduler.api.ScheduledWake
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerOutput
import io.aequicor.heartbeat.feature.scheduler.api.WakeCondition
import io.aequicor.heartbeat.feature.scheduler.api.WakeDelivery
import io.aequicor.heartbeat.feature.scheduler.api.WakeFailure
import io.aequicor.heartbeat.feature.scheduler.api.WakeId
import io.aequicor.heartbeat.feature.scheduler.api.WakeOrigin
import io.aequicor.heartbeat.feature.scheduler.api.WakeReason
import io.aequicor.heartbeat.feature.scheduler.api.WakeRejection
import io.aequicor.heartbeat.feature.scheduler.api.WakeRequest
import io.aequicor.heartbeat.feature.scheduler.api.deliveryRequestId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class HarnessEventAncestryTest {
    private val storage = MemoryHarnessRequestAncestry()
    private val mapper = HarnessEventAncestry(lazyOf(storage), lazyOf(EmptyHarnessEventRuns))
    private val owner = HarnessId("owner")
    private val other = HarnessId("other")
    private val at = Instant.fromEpochMilliseconds(1000)
    private val initiator = RequestInitiator(dispatchSession, RequestId("source"))
    private val wake = WakeRequest(
        WakeId("wake"), dispatchSession, null, WakeCondition(deadline = at), "private note",
        WakeOrigin.Feature("harness"), ownerFeature = "harness",
        ownerContext = HarnessOwnedContext().encode(owner, HarnessCallOrigin(sendChain = mapOf(owner to 1))),
        initiator = initiator,
    )

    @Test
    fun `delivery merges owner initiator prior target and trigger independently`() = runTest {
        storage.restrict(dispatchSession, initiator.request, HarnessCallOrigin(sendChain = mapOf(owner to 3)))
        storage.restrict(dispatchSession, wake.id.deliveryRequestId(), HarnessCallOrigin(sendChain = mapOf(other to 2)))
        val trigger = EventOrigin.Feature("harness", HarnessOwnedContext().encode(other, HarnessCallOrigin(true)))
        val reason = WakeReason.Event(BusEvent(EventKeys.custom("done"), trigger, at, "untrusted payload"))
        val delivery = WakeDelivery(ScheduledWake(wake, at), reason)
        val expected = HarnessCallOrigin(true, mapOf(owner to 3, other to 2))
        assertEquals(expected, mapper.delivery(delivery))
        assertEquals(expected, mapper.output(SchedulerOutput.Woke(delivery.wake, reason)))
        assertEquals(
            expected,
            mapper.output(
                SchedulerOutput.DeliveryFailed(
                    listOf(delivery.wake),
                    WakeFailure.OwnerUnavailable,
                    listOf(trigger),
                ),
            ),
        )
    }

    @Test
    fun `cancel and rejected retry retain restrictions persisted before deferred delivery`() = runTest {
        val target = RequestInitiator(wake.session, wake.id.deliveryRequestId())
        val expected = HarnessCallOrigin(true, mapOf(other to 3))
        storage.restrict(target.session, target.request, expected)
        assertEquals(expected, mapper.output(SchedulerOutput.Cancelled(listOf(wake.id), listOf(target.origin()))))
        assertEquals(
            expected,
            mapper.output(SchedulerOutput.Rejected(wake.id, WakeRejection.Duplicate, deliveryRequest = target)),
        )
    }

    @Test
    fun `all exact bus reference types resolve the same persisted request`() = runTest {
        val expected = HarnessCallOrigin(true, mapOf(owner to 3))
        storage.restrict(dispatchSession, initiator.request, expected)
        val origins = listOf(
            initiator.origin(),
            EventOrigin.HostTurn(dispatchSession, initiator.request),
            EventOrigin.Action(ActionId("action"), initiator),
        )
        origins.forEach { assertEquals(expected, mapper.origin(it)) }
        assertEquals(expected, mapper.output(SchedulerOutput.Cancelled(listOf(wake.id), origins)))
        assertEquals(HarnessCallOrigin(), mapper.origin(EventOrigin.Session(dispatchSession, RequestId("different"))))
    }

    @Test
    fun `missing exact reference is neutral but failed lookup and malformed own metadata are not`() = runTest {
        assertEquals(HarnessCallOrigin(), mapper.origin(EventOrigin.Session(dispatchSession)))
        assertEquals(HarnessCallOrigin(), mapper.origin(EventOrigin.Action(ActionId("legacy"))))
        storage.failure = IllegalStateException("private IO diagnostics")
        assertFailsWith<IllegalStateException> { mapper.origin(initiator.origin()) }
        assertFailsWith<IllegalStateException> { mapper.origin(EventOrigin.Feature("harness", "broken")) }
        assertFailsWith<IllegalStateException> { mapper.wake(wake.copy(ownerContext = null)) }
        assertEquals(HarnessCallOrigin(), mapper.origin(EventOrigin.Feature("other", "not our codec")))
    }
}
