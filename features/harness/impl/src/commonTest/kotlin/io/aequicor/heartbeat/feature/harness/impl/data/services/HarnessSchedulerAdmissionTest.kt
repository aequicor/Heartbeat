package io.aequicor.heartbeat.feature.harness.impl.data.services

import io.aequicor.heartbeat.feature.harness.impl.data.events.EmptyHarnessEventRuns
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.impl.data.events.HarnessEventAncestry
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.MemoryHarnessRequestAncestry
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.dispatchSession
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessDeliveryPermit
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessSchedulerAccess
import io.aequicor.heartbeat.feature.scheduler.api.BusEvent
import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import io.aequicor.heartbeat.feature.scheduler.api.EventOrigin
import io.aequicor.heartbeat.feature.scheduler.api.RequestInitiator
import io.aequicor.heartbeat.feature.scheduler.api.ScheduledWake
import io.aequicor.heartbeat.feature.scheduler.api.WakeCondition
import io.aequicor.heartbeat.feature.scheduler.api.WakeDelivery
import io.aequicor.heartbeat.feature.scheduler.api.WakeId
import io.aequicor.heartbeat.feature.scheduler.api.WakeOrigin
import io.aequicor.heartbeat.feature.scheduler.api.WakeReason
import io.aequicor.heartbeat.feature.scheduler.api.WakeRequest
import io.aequicor.heartbeat.feature.scheduler.api.deliveryRequestId
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeAdmission
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class HarnessSchedulerAdmissionTest {
    private val harness = HarnessId("owner")
    private val at = Instant.fromEpochSeconds(100)
    private val origin = HarnessCallOrigin(true, mapOf(harness to 2))
    private val storage = MemoryHarnessRequestAncestry()
    private var isCurrent = true
    private val permits = MutableStateFlow<HarnessDeliveryPermit?>(HarnessDeliveryPermit { isCurrent })
    private val access = HarnessSchedulerAccess { owner, target ->
        assertEquals(harness, owner)
        target?.let { assertEquals(dispatchSession, it.session) }
        permits
    }
    private val admission = HarnessSchedulerAdmission(
        lazyOf(access),
        HarnessEventAncestry(lazyOf(storage), lazyOf(EmptyHarnessEventRuns)),
        lazyOf(storage),
    )
    private val request = WakeRequest(
        WakeId("owned"),
        dispatchSession,
        null,
        WakeCondition(deadline = at),
        "private note",
        WakeOrigin.Feature("harness"),
        ownerFeature = "harness",
        ownerContext = HarnessOwnedContext().encode(harness, origin),
    )

    @Test
    fun `allow waits for durable ancestry and every fresh collection repeats the boundary`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var writes = 0
        storage.beforeRestrict = {
            writes++
            entered.complete(Unit)
            release.await()
        }
        val result = async { admission.wake(request).first() }
        entered.await()
        assertFalse(result.isCompleted)
        release.complete(Unit)
        assertEquals(ScheduledWakeAdmission.Allow, result.await())
        assertEquals(origin, storage.lookup(request.session, request.id.deliveryRequestId()))
        assertEquals(ScheduledWakeAdmission.Allow, admission.wake(request).first())
        assertEquals(2, writes)
    }

    @Test
    fun `changed authority during write drops before any allow`() = runTest {
        storage.beforeRestrict = { isCurrent = false }
        assertEquals(ScheduledWakeAdmission.Drop, admission.wake(request).first())
        assertEquals(origin, storage.lookup(request.session, request.id.deliveryRequestId()))
    }

    @Test
    fun `live revocation interrupts pending durable write and emits drop`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        storage.beforeRestrict = {
            entered.complete(Unit)
            release.await()
        }
        val observed = mutableListOf<ScheduledWakeAdmission>()
        backgroundScope.launch { admission.wake(request).collect { observed += it } }
        entered.await()
        permits.value = null
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf(ScheduledWakeAdmission.Drop), observed)
    }

    @Test
    fun `ordinary causal relay persists restrictions without resolving disabled feature authority`() = runTest {
        val source = RequestInitiator(dispatchSession, RequestId("source"))
        storage.restrict(source.session, source.request, origin)
        val detached = HarnessSchedulerAdmission(
            lazy { error("disabled feature must stay lazy") },
            HarnessEventAncestry(lazyOf(storage), lazyOf(EmptyHarnessEventRuns)),
            lazyOf(storage),
        )
        val ordinary = request.copy(ownerFeature = null, ownerContext = null, initiator = source)
        val delivery = WakeDelivery(ScheduledWake(ordinary, at), WakeReason.Deadline(at))
        assertEquals(ScheduledWakeAdmission.Allow, detached.event(delivery).first())
        assertEquals(origin, storage.lookup(request.session, request.id.deliveryRequestId()))
    }

    @Test
    fun `publisher and wake gates merge independent trigger restrictions into exact target`() = runTest {
        val other = HarnessId("other")
        val trigger = HarnessCallOrigin(sendChain = mapOf(other to 3))
        val event = BusEvent(
            EventKeys.custom("done"),
            EventOrigin.Feature("harness", HarnessOwnedContext().encode(harness, trigger)),
            at,
        )
        assertEquals(ScheduledWakeAdmission.Allow, admission.wake(request).first())
        assertEquals(
            ScheduledWakeAdmission.Allow,
            admission.event(WakeDelivery(ScheduledWake(request, at), WakeReason.Event(event))).first(),
        )
        assertEquals(origin.merge(trigger), storage.lookup(request.session, request.id.deliveryRequestId()))
        permits.value = null
        assertEquals(ScheduledWakeAdmission.Drop, admission.wake(request).first())
        assertEquals(
            ScheduledWakeAdmission.Drop,
            admission.event(WakeDelivery(ScheduledWake(request, at), WakeReason.Event(event))).first(),
        )
    }

    @Test
    fun `corrupt ownership and storage failure never become neutral allow`() = runTest {
        assertEquals(ScheduledWakeAdmission.Drop, admission.wake(request.copy(ownerContext = "broken")).first())
        storage.failure = IllegalStateException("private IO failure")
        assertEquals(ScheduledWakeAdmission.Drop, admission.wake(request).first())
        assertTrue(HarnessScheduledEventOwner(admission).isSessionOriginObserver)
    }
}
