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
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerState
import io.aequicor.heartbeat.feature.scheduler.api.WakeDelivery
import io.aequicor.heartbeat.feature.scheduler.api.WakeFailure
import io.aequicor.heartbeat.feature.scheduler.api.WakeId
import io.aequicor.heartbeat.feature.scheduler.api.WakeOrigin
import io.aequicor.heartbeat.feature.scheduler.api.WakeReason
import io.aequicor.heartbeat.feature.scheduler.api.WakeRequest
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledSessionHost
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeAdmission
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeDeferredException
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeOwner
import io.aequicor.heartbeat.feature.scheduler.impl.domain.SchedulerEffects
import io.aequicor.heartbeat.feature.scheduler.impl.domain.SchedulerPersistence
import io.aequicor.heartbeat.feature.scheduler.impl.domain.WakeStorage
import io.aequicor.heartbeat.feature.scheduler.impl.domain.wakePrompt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `disabled feature defers without dropping wake or reporting failure`() = runTest {
        val host = FakeHost(priority = 1, failure = ScheduledWakeDeferredException())
        val wake = scheduled("deferred", events = setOf(EventKeys.custom("completed")))
        val delivery = WakeDelivery(wake, WakeReason.Deadline(START))
        SchedulerEffects(persistence, lazyOf(setOf(host))).handle(SchedulerEffect.Deliver(listOf(delivery)), scope)
        assertEquals(listOf<SchedulerIntent>(SchedulerIntent.Internal.Deferred(wake.id)), scope.sent)
        val machine = SpecMachine(SchedulerState.Ready(listOf(wake), delivering = setOf(wake.id)))
        machine.send(scope.sent.single())
        val ready = machine.state.value as SchedulerState.Ready
        assertEquals(listOf(wake), ready.wakes)
        assertTrue(ready.delivering.isEmpty())
        // A paused feature has no timer: its producer may replay the same event even after a long pause.
        machine.send(
            SchedulerIntent.Internal.Observed(BusEvent(EventKeys.custom("completed"), EventOrigin.Host, START)),
        )
        assertEquals(setOf(wake.id), (machine.state.value as SchedulerState.Ready).delivering)
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

    @Test
    fun `feature wake explains its owner without attributing sleep or its note to the agent`() {
        val wake = scheduled("feature", deadline = START)
        val request = wake.request.copy(origin = WakeOrigin.Feature("harness", "Compose harness"))
        val prompt = wakePrompt(WakeDelivery(wake.copy(request = request), WakeReason.Deadline(START)))
        assertTrue("Compose harness scheduled this continuation" in prompt.directive)
        assertTrue("Feature context for this continuation:" in prompt.directive)
        assertFalse("you put this session to sleep" in prompt.directive)
        assertFalse("Your note for this moment:" in prompt.directive)
        assertTrue(request.note in prompt.directive)
    }

    @Test
    fun `missing and ambiguous owners fail before constructing session hosts`() = runTest {
        val owner = object : ScheduledWakeOwner {
            override val feature = "harness"
            override fun admission(request: WakeRequest): Flow<ScheduledWakeAdmission> =
                flowOf(ScheduledWakeAdmission.Allow)
        }
        val duplicate = object : ScheduledWakeOwner by owner {}
        for (owners in listOf(emptySet(), setOf(owner, duplicate))) {
            val wake = scheduled("owned", deadline = START).let {
                it.copy(request = it.request.copy(ownerFeature = "harness"))
            }
            val result = RecordingScope()
            SchedulerEffects(persistence, lazy { error("Hosts must stay lazy") }, lazyOf(owners)).handle(
                SchedulerEffect.Deliver(listOf(WakeDelivery(wake, WakeReason.Deadline(START)))),
                result,
            )
            assertEquals(
                listOf<SchedulerIntent>(
                    SchedulerIntent.Internal.DeliveryFailed(listOf(wake.id), WakeFailure.OwnerUnavailable),
                ),
                result.sent,
            )
        }
    }

    @Test
    fun `owner refusal settles before selecting a host and defer keeps an event wait`() = runTest {
        for (decision in listOf(ScheduledWakeAdmission.Drop, ScheduledWakeAdmission.Defer)) {
            val wake = scheduled("owned", events = setOf(EventKeys.custom("done"))).let {
                it.copy(request = it.request.copy(ownerFeature = "harness"))
            }
            val owner = object : ScheduledWakeOwner {
                override val feature = "harness"
                override fun admission(request: WakeRequest): Flow<ScheduledWakeAdmission> = flowOf(decision)
            }
            val result = RecordingScope()
            SchedulerEffects(persistence, lazy { error("Hosts must stay lazy") }, lazyOf(setOf(owner))).handle(
                SchedulerEffect.Deliver(listOf(WakeDelivery(wake, WakeReason.Deadline(START)))),
                result,
            )
            val expected: SchedulerIntent = if (decision == ScheduledWakeAdmission.Drop) {
                SchedulerIntent.Internal.DeliveryFailed(listOf(wake.id), WakeFailure.OwnerRejected)
            } else {
                SchedulerIntent.Internal.Deferred(wake.id)
            }
            assertEquals(listOf(expected), result.sent)
        }
    }

    @Test
    fun `owned wakes require a capable host and carry live admission to that host`() = runTest {
        val owner = object : ScheduledWakeOwner {
            override val feature = "harness"
            override fun admission(request: WakeRequest): Flow<ScheduledWakeAdmission> =
                flowOf(ScheduledWakeAdmission.Allow)
        }
        val wake = scheduled("owned", deadline = START).let {
            it.copy(request = it.request.copy(ownerFeature = "harness"))
        }
        val legacy = FakeHost(priority = 10)
        val capable = FakeHost(priority = 1)
        val host = object : ScheduledSessionHost by capable {
            override val isWakeAdmissionSupported = true
        }
        val delivery = SchedulerEffect.Deliver(listOf(WakeDelivery(wake, WakeReason.Deadline(START))))
        SchedulerEffects(persistence, lazyOf(setOf(legacy, host)), lazyOf(setOf(owner))).handle(delivery, scope)
        assertTrue(legacy.woken.isEmpty())
        assertEquals(ScheduledWakeAdmission.Allow, checkNotNull(capable.woken.single().second.admission).first())
        val rejected = RecordingScope()
        SchedulerEffects(persistence, lazyOf(setOf(legacy)), lazyOf(setOf(owner))).handle(delivery, rejected)
        assertTrue(legacy.woken.isEmpty())
        assertEquals(
            listOf<SchedulerIntent>(
                SchedulerIntent.Internal.DeliveryFailed(listOf(wake.id), WakeFailure.SessionUnavailable),
            ),
            rejected.sent,
        )
    }

    @Test
    fun `self cancellation of owner or host settles delivering wakes instead of stranding them`() = runTest {
        for (isOwnerCancelled in listOf(true, false)) {
            val wake = scheduled("cancelled", deadline = START).let {
                it.copy(request = it.request.copy(ownerFeature = "harness"))
            }
            val owner = object : ScheduledWakeOwner {
                override val feature = "harness"
                override fun admission(request: WakeRequest): Flow<ScheduledWakeAdmission> = flow {
                    if (isOwnerCancelled) throw CancellationException("Owner cancelled")
                    emit(ScheduledWakeAdmission.Allow)
                }
            }
            val host = object : ScheduledSessionHost by FakeHost(priority = 1) {
                override val isWakeAdmissionSupported = true
                override suspend fun wake(
                    request: WakeRequest,
                    prompt: io.aequicor.heartbeat.feature.scheduler.api.spi.WakePrompt,
                ): Unit = throw CancellationException("Host cancelled before accepting")
            }
            val result = RecordingScope()
            SchedulerEffects(persistence, lazyOf(setOf(host)), lazyOf(setOf(owner))).handle(
                SchedulerEffect.Deliver(listOf(WakeDelivery(wake, WakeReason.Deadline(START)))),
                result,
            )
            assertEquals(
                listOf<SchedulerIntent>(SchedulerIntent.Internal.DeliveryFailed(listOf(wake.id), WakeFailure.Unknown)),
                result.sent,
            )
            val machine = SpecMachine(SchedulerState.Ready(listOf(wake), delivering = setOf(wake.id)))
            machine.send(result.sent.single())
            val ready = machine.state.value as SchedulerState.Ready
            assertTrue(ready.delivering.isEmpty())
            assertTrue(ready.wakes.isEmpty())
        }
    }

    @Test
    fun `cancelling the delivery scope preserves the wake for recovery`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val host = object : ScheduledSessionHost by FakeHost(priority = 1) {
            override suspend fun wake(
                request: WakeRequest,
                prompt: io.aequicor.heartbeat.feature.scheduler.api.spi.WakePrompt,
            ) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val result = RecordingScope()
        val job = launch {
            SchedulerEffects(persistence, lazyOf(setOf(host))).handle(
                SchedulerEffect.Deliver(
                    listOf(WakeDelivery(scheduled("interrupted", deadline = START), WakeReason.Deadline(START))),
                ),
                result,
            )
        }
        entered.await()
        job.cancelAndJoin()
        assertTrue(result.sent.isEmpty())
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
