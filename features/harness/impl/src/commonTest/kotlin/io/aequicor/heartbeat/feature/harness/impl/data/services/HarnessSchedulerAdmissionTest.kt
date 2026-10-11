package io.aequicor.heartbeat.feature.harness.impl.data.services

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.impl.data.HarnessTestStores
import io.aequicor.heartbeat.feature.harness.impl.data.events.EmptyHarnessEventRuns
import io.aequicor.heartbeat.feature.harness.impl.data.events.HarnessEventAncestry
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.MemoryHarnessRequestAncestry
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.dispatchSession
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessDeliveryPermit
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessSchedulerAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessTarget
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class HarnessSchedulerAdmissionTest {
    private val harness = HarnessId("owner")
    private val at = Instant.fromEpochSeconds(100)
    private val origin = HarnessCallOrigin(true, mapOf(harness to 2))
    private val storage = MemoryHarnessRequestAncestry()
    private var isCurrent = true
    private val permits = MutableStateFlow<HarnessAccess>(HarnessAccess.Permitted(HarnessDeliveryPermit { isCurrent }))
    private val access = object : HarnessSchedulerAccess {
        override fun permits(harness: HarnessId, target: HarnessTarget?): Flow<HarnessAccess> {
            assertEquals(this@HarnessSchedulerAdmissionTest.harness, harness)
            target?.let { assertEquals(dispatchSession, it.session) }
            return permits
        }

        override fun snapshot() = error("Admission never reads a bulk snapshot")
    }
    private val streaks = HarnessSessionTurnStreaks()
    private val admission = HarnessSchedulerAdmission(
        lazyOf(access),
        HarnessEventAncestry(lazyOf(storage), lazyOf(EmptyHarnessEventRuns)),
        lazyOf(storage),
        streaks,
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
        permits.value = HarnessAccess.Denied
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
            HarnessSessionTurnStreaks(),
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
        permits.value = HarnessAccess.Denied
        assertEquals(ScheduledWakeAdmission.Drop, admission.wake(request).first())
        assertEquals(
            ScheduledWakeAdmission.Drop,
            admission.event(WakeDelivery(ScheduledWake(request, at), WakeReason.Event(event))).first(),
        )
    }

    @Test
    fun `unknown provenance defers event wakes and keeps deadline wakes waiting instead of dropping`() = runTest {
        permits.value = HarnessAccess.Unknown
        val eventOnly = request.copy(condition = WakeCondition(events = setOf(EventKeys.custom("ready"))))
        assertEquals(ScheduledWakeAdmission.Defer, admission.wake(eventOnly).first())
        val decisions = mutableListOf<ScheduledWakeAdmission>()
        backgroundScope.launch { admission.wake(request).collect { decisions += it } }
        advanceTimeBy(10.seconds)
        runCurrent()
        assertTrue(decisions.isEmpty())
        permits.value = HarnessAccess.Permitted(HarnessDeliveryPermit { true })
        runCurrent()
        assertEquals(listOf(ScheduledWakeAdmission.Allow), decisions)
        assertEquals(origin, storage.lookup(request.session, request.id.deliveryRequestId()))
    }

    @Test
    fun `ordinary relay without any recorded ancestry allows without opening the database`() = runTest {
        val stores = HarnessTestStores()
        val stored = StoredHarnessRequestAncestry(stores)
        val detached = HarnessSchedulerAdmission(
            lazy { error("disabled feature must stay lazy") },
            HarnessEventAncestry(lazyOf(stored), lazyOf(EmptyHarnessEventRuns)),
            lazyOf(stored),
            HarnessSessionTurnStreaks(),
        )
        val source = RequestInitiator(dispatchSession, RequestId("source"))
        val ordinary = request.copy(ownerFeature = null, ownerContext = null, initiator = source)
        val event = BusEvent(EventKeys.custom("done"), EventOrigin.Session(dispatchSession, RequestId("cause")), at)
        val wake = ScheduledWake(ordinary, at)
        // HarnessTestStores refuses every database open, so any lookup that reached Room would drop the wake.
        for (reason in listOf(WakeReason.Deadline(at), WakeReason.Event(event))) {
            assertEquals(ScheduledWakeAdmission.Allow, detached.event(WakeDelivery(wake, reason)).first())
        }
    }

    @Test
    fun `corrupt ownership and storage failure never become neutral allow`() = runTest {
        assertEquals(ScheduledWakeAdmission.Drop, admission.wake(request.copy(ownerContext = "broken")).first())
        storage.failure = IllegalStateException("private IO failure")
        assertEquals(ScheduledWakeAdmission.Drop, admission.wake(request).first())
        assertTrue(HarnessScheduledEventOwner(admission).isSessionOriginObserver)
    }

    @Test
    fun `exhausted session streak holds owned wakes without persisting ancestry`() = runTest {
        repeat(HarnessLimits.SEND_CHAIN) { streaks.harnessTurnStarted(dispatchSession) }
        val eventOnly = request.copy(condition = WakeCondition(events = setOf(EventKeys.custom("ready"))))
        assertEquals(ScheduledWakeAdmission.Defer, admission.wake(eventOnly).first())
        assertEquals(ScheduledWakeAdmission.Drop, admission.wake(request).first())
        assertEquals(null, storage.lookup(request.session, request.id.deliveryRequestId()))
        streaks.otherTurnStarted(dispatchSession)
        assertEquals(ScheduledWakeAdmission.Allow, admission.wake(eventOnly).first())
    }

    @Test
    fun `denied publisher defers another owner's event wake but drops a harness wake`() = runTest {
        val event = BusEvent(
            EventKeys.custom("harness.owner.done"),
            EventOrigin.Feature("harness", HarnessOwnedContext().encode(harness, origin)),
            at,
        )
        val condition = WakeCondition(events = setOf(event.key))
        val foreign = request.copy(condition = condition, ownerFeature = null, ownerContext = null)
        permits.value = HarnessAccess.Denied
        assertEquals(
            ScheduledWakeAdmission.Defer,
            admission.event(WakeDelivery(ScheduledWake(foreign, at), WakeReason.Event(event))).first(),
        )
        val withDeadline = foreign.copy(condition = condition.copy(deadline = at))
        assertEquals(
            ScheduledWakeAdmission.Drop,
            admission.event(WakeDelivery(ScheduledWake(withDeadline, at), WakeReason.Event(event))).first(),
        )
        val owned = request.copy(condition = condition)
        assertEquals(
            ScheduledWakeAdmission.Drop,
            admission.event(WakeDelivery(ScheduledWake(owned, at), WakeReason.Event(event))).first(),
        )
    }
}
