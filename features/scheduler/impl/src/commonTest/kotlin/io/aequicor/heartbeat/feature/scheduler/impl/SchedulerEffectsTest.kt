package io.aequicor.heartbeat.feature.scheduler.impl

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.scheduler.api.BusEvent
import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import io.aequicor.heartbeat.feature.scheduler.api.EventOrigin
import io.aequicor.heartbeat.feature.scheduler.api.ScheduledWake
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerEffect
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerIntent
import io.aequicor.heartbeat.feature.scheduler.api.WakeDelivery
import io.aequicor.heartbeat.feature.scheduler.api.WakeFailure
import io.aequicor.heartbeat.feature.scheduler.api.WakeId
import io.aequicor.heartbeat.feature.scheduler.api.WakeOrigin
import io.aequicor.heartbeat.feature.scheduler.api.WakeReason
import io.aequicor.heartbeat.feature.scheduler.impl.domain.SchedulerEffects
import io.aequicor.heartbeat.feature.scheduler.impl.domain.SchedulerPersistence
import io.aequicor.heartbeat.feature.scheduler.impl.domain.WakeStorage
import io.aequicor.heartbeat.feature.scheduler.impl.domain.wakePrompt
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchedulerEffectsTest {
    private val storage = MemoryWakes()
    private val persistence = SchedulerPersistence(storage)
    private val scope = RecordingScope()

    @Test
    fun `load reports stored wakes`() = runTest {
        storage.wakes = listOf(scheduled("w1", deadline = START))
        SchedulerEffects(persistence, lazyOf(emptySet())).handle(SchedulerEffect.Load, scope)
        assertEquals(listOf<SchedulerIntent>(SchedulerIntent.Internal.Loaded(storage.wakes)), scope.sent)
    }

    @Test
    fun `a stale revision never overwrites a newer one`() = runTest {
        val effects = SchedulerEffects(persistence, lazyOf(emptySet()))
        effects.handle(SchedulerEffect.Persist(listOf(scheduled("new", deadline = START)), 2), scope)
        effects.handle(SchedulerEffect.Persist(emptyList(), 1), scope)
        assertEquals(listOf(WakeId("new")), storage.wakes.map { it.id })
        assertEquals(1, storage.saves)
    }

    @Test
    fun `the owning host with the highest priority wakes the session`() = runTest {
        val fallback = FakeHost(priority = 0)
        val studio = FakeHost(priority = 100, owned = setOf(SESSION))
        val delivery = WakeDelivery(scheduled("w1", deadline = START), WakeReason.Deadline(START))
        SchedulerEffects(
            persistence,
            lazyOf(setOf(fallback, studio)),
        ).handle(SchedulerEffect.Deliver(listOf(delivery)), scope)
        assertEquals(listOf(delivery.wake.request), studio.woken.map { it.first })
        assertEquals(RequestId("wake_w1"), studio.woken.single().second.request)
        assertTrue(fallback.woken.isEmpty())
        assertEquals(
            listOf<SchedulerIntent>(SchedulerIntent.Internal.Delivered(WakeId("w1"), delivery.reason)),
            scope.sent,
        )
    }

    @Test
    fun `every delivery settles on its own`() = runTest {
        val host = FakeHost(priority = 0, owned = setOf(SESSION))
        val failing = FakeHost(
            priority = 1,
            owned = setOf(OTHER),
            failure = EngineException(EngineFailure.Engine(EngineFailureReason.Unavailable)),
        )
        val ok = WakeDelivery(scheduled("ok", deadline = START), WakeReason.Deadline(START))
        val refused = WakeDelivery(
            ScheduledWake(wakeRequest("refused", deadline = START, session = OTHER), START),
            WakeReason.Deadline(START),
        )
        val orphan = WakeDelivery(
            ScheduledWake(wakeRequest("orphan", deadline = START, session = OTHER.copy(nativeId = "x")), START),
            WakeReason.Deadline(START),
        )
        SchedulerEffects(
            persistence,
            lazyOf(setOf(host, failing)),
        ).handle(SchedulerEffect.Deliver(listOf(ok, refused, orphan)), scope)
        assertEquals(
            setOf<SchedulerIntent>(
                SchedulerIntent.Internal.Delivered(WakeId("ok"), ok.reason),
                SchedulerIntent.Internal.DeliveryFailed(listOf(WakeId("refused")), WakeFailure.Engine),
                SchedulerIntent.Internal.DeliveryFailed(listOf(WakeId("orphan")), WakeFailure.SessionUnavailable),
            ),
            scope.sent.toSet(),
        )
    }

    @Test
    fun `a session busy beyond the delivery timeout drops the wake`() = runTest {
        val busy = object : io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledSessionHost {
            override val priority: Int = 1
            override suspend fun owns(session: io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef) = true
            override suspend fun wake(
                request: io.aequicor.heartbeat.feature.scheduler.api.WakeRequest,
                prompt: io.aequicor.heartbeat.feature.scheduler.api.spi.WakePrompt,
            ) = kotlinx.coroutines.awaitCancellation()
        }
        val delivery = WakeDelivery(scheduled("w1", deadline = START), WakeReason.Deadline(START))
        SchedulerEffects(persistence, lazyOf(setOf(busy))).handle(SchedulerEffect.Deliver(listOf(delivery)), scope)
        assertEquals(
            listOf<SchedulerIntent>(SchedulerIntent.Internal.DeliveryFailed(listOf(WakeId("w1")), WakeFailure.Busy)),
            scope.sent,
        )
    }

    @Test
    fun `an agent wake without a chat host is not resumed unattended`() = runTest {
        val delivery = WakeDelivery(scheduled("w1", deadline = START), WakeReason.Deadline(START))
        SchedulerEffects(persistence, lazyOf(emptySet())).handle(SchedulerEffect.Deliver(listOf(delivery)), scope)
        assertEquals(
            listOf<SchedulerIntent>(
                SchedulerIntent.Internal.DeliveryFailed(listOf(WakeId("w1")), WakeFailure.SessionUnavailable),
            ),
            scope.sent,
        )
    }

    @Test
    fun `a feature wake also needs a host that services tools and permissions`() = runTest {
        val wake = scheduled("w1", deadline = START)
        val delivery = WakeDelivery(
            wake.copy(request = wake.request.copy(origin = WakeOrigin.Feature("test"))),
            WakeReason.Deadline(START),
        )
        SchedulerEffects(persistence, lazyOf(emptySet())).handle(SchedulerEffect.Deliver(listOf(delivery)), scope)
        assertEquals(
            listOf<SchedulerIntent>(
                SchedulerIntent.Internal.DeliveryFailed(listOf(WakeId("w1")), WakeFailure.SessionUnavailable),
            ),
            scope.sent,
        )
    }

    @Test
    fun `the wake prompt carries the reason, payload and note`() {
        val event = BusEvent(EventKeys.custom("build.done"), EventOrigin.Host, START, "exit 0")
        val prompt = wakePrompt(WakeDelivery(scheduled("w1", events = setOf(event.key)), WakeReason.Event(event)))
        assertTrue("custom.build.done" in prompt.visible)
        assertTrue("exit 0" in prompt.directive && "check the build" in prompt.directive)
        assertTrue("untrusted data" in prompt.directive)
        assertTrue("check the build" !in prompt.visible)
    }

    private class MemoryWakes : WakeStorage {
        var wakes: List<ScheduledWake> = emptyList()
        var saves = 0

        override suspend fun load(): List<ScheduledWake> = wakes

        override suspend fun save(wakes: List<ScheduledWake>) {
            this.wakes = wakes
            saves++
        }
    }
}
