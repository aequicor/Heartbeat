package io.aequicor.heartbeat.feature.scheduler.impl

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.scheduler.api.BusEvent
import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import io.aequicor.heartbeat.feature.scheduler.api.EventOrigin
import io.aequicor.heartbeat.feature.scheduler.api.RequestInitiator
import io.aequicor.heartbeat.feature.scheduler.api.ScheduledWake
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerEffect
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerIntent
import io.aequicor.heartbeat.feature.scheduler.api.WakeDelivery
import io.aequicor.heartbeat.feature.scheduler.api.WakeFailure
import io.aequicor.heartbeat.feature.scheduler.api.WakeReason
import io.aequicor.heartbeat.feature.scheduler.api.WakeRequest
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledEventOwner
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledSessionHost
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeAdmission
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeDroppedException
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeOwner
import io.aequicor.heartbeat.feature.scheduler.api.spi.WakePrompt
import io.aequicor.heartbeat.feature.scheduler.impl.domain.SchedulerEffects
import io.aequicor.heartbeat.feature.scheduler.impl.domain.SchedulerPersistence
import io.aequicor.heartbeat.feature.scheduler.impl.domain.WakeDeliveryAdmission
import io.aequicor.heartbeat.feature.scheduler.impl.domain.WakeStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EventWakeAdmissionTest {
    @Test
    fun `initiating request is checked for deadline and system event delivery`() = runTest {
        val initiator = RequestInitiator(SESSION, RequestId("original"))
        for (reason in listOf(WakeReason.Deadline(START), WakeReason.Event(BusEvent(KEY, EventOrigin.System, START)))) {
            val base = eventDelivery()
            val delivery = base.copy(
                wake = base.wake.copy(request = base.wake.request.copy(initiator = initiator)),
                reason = reason,
            )
            val observer = object : ScheduledEventOwner {
                override val feature = "observer"
                override val isSessionOriginObserver = true
                override fun admission(delivery: WakeDelivery): Flow<ScheduledWakeAdmission> {
                    assertEquals(initiator, delivery.wake.request.initiator)
                    return flowOf(ScheduledWakeAdmission.Drop)
                }
            }
            val result = RecordingScope()
            effects(
                lazy { error("Denied initiator must not resolve host") },
                setOf(observer),
            ).handle(deliver(delivery), result)
            assertEquals(listOf(rejected(delivery, WakeFailure.OwnerRejected)), result.sent)
        }
    }

    @Test
    fun `initiator observers intersect feature publisher and duplicate owner is queried once`() = runTest {
        val base = eventDelivery()
        val delivery = base.copy(
            wake = base.wake.copy(
                request = base.wake.request.copy(
                    initiator = RequestInitiator(SESSION, RequestId("original")),
                ),
            ),
        )
        val observed = mutableListOf<String>()
        fun owner(name: String, decision: ScheduledWakeAdmission) = object : ScheduledEventOwner {
            override val feature = name
            override val isSessionOriginObserver = true
            override fun admission(delivery: WakeDelivery): Flow<ScheduledWakeAdmission> {
                observed += name
                return flowOf(decision)
            }
        }
        val admission = assertNotNull(
            WakeDeliveryAdmission.create(
                delivery,
                lazyOf(emptySet()),
                lazyOf(
                    setOf(
                        owner("harness", ScheduledWakeAdmission.Allow),
                        owner("other", ScheduledWakeAdmission.Drop),
                    ),
                ),
            ),
        )
        assertEquals(ScheduledWakeAdmission.Drop, admission.decisions.first())
        // One source factory per gate, even when the same owner is publisher and request observer.
        assertEquals(1, observed.count { it == "other" })
        assertEquals(1, observed.count { it == "harness" })
        assertTrue(observed.containsAll(listOf("harness", "other")))
    }

    @Test
    fun `publisher denial blocks a previously registered ordinary wake before resolving any host`() = runTest {
        val delivery = eventDelivery()
        var received: WakeDelivery? = null
        val publisher = publisher {
            received = it
            flowOf(ScheduledWakeAdmission.Drop)
        }
        val result = RecordingScope()
        effects(lazy { error("Host must remain lazy") }, setOf(publisher)).handle(deliver(delivery), result)
        assertSame(delivery, received)
        assertEquals(listOf(rejected(delivery, WakeFailure.OwnerRejected)), result.sent)
    }

    @Test
    fun `missing and ambiguous publisher owners refuse even when receiving wake has its own owner`() = runTest {
        val owner = publisher { flowOf(ScheduledWakeAdmission.Allow) }
        val duplicate = publisher { flowOf(ScheduledWakeAdmission.Allow) }
        for (publishers in listOf(emptySet(), setOf(owner, duplicate))) {
            for (feature in listOf(null, "receiver")) {
                val delivery = eventDelivery(feature)
                val result = RecordingScope()
                effects(
                    lazy { error("Host must remain lazy") },
                    publishers,
                    setOf(wakeOwner(flowOf(ScheduledWakeAdmission.Allow))),
                ).handle(deliver(delivery), result)
                assertEquals(listOf(rejected(delivery, WakeFailure.OwnerUnavailable)), result.sent)
            }
        }
    }

    @Test
    fun `publisher and receiving owner intersect with drop then defer priority`() = runTest {
        val delivery = eventDelivery("receiver")
        for (receiver in ScheduledWakeAdmission.entries) {
            for (published in ScheduledWakeAdmission.entries) {
                val admission = assertNotNull(
                    WakeDeliveryAdmission.create(
                        delivery,
                        lazyOf(setOf(wakeOwner(flowOf(receiver)))),
                        lazyOf(setOf(publisher { flowOf(published) })),
                    ),
                )
                val expected = when {
                    receiver == ScheduledWakeAdmission.Drop || published == ScheduledWakeAdmission.Drop ->
                        ScheduledWakeAdmission.Drop

                    receiver == ScheduledWakeAdmission.Defer || published == ScheduledWakeAdmission.Defer ->
                        ScheduledWakeAdmission.Defer

                    else -> ScheduledWakeAdmission.Allow
                }
                assertEquals(expected, admission.decisions.first())
            }
        }
    }

    @Test
    fun `publisher defer retains event only wait but drops one with a deadline`() = runTest {
        for (deadline in listOf(null, START)) {
            val original = eventDelivery()
            val delivery = original.copy(
                wake = original.wake.copy(
                    request = original.wake.request.copy(
                        condition = original.wake.request.condition.copy(deadline = deadline),
                    ),
                ),
            )
            val result = RecordingScope()
            effects(
                lazy { error("Host must remain lazy") },
                setOf(publisher { flowOf(ScheduledWakeAdmission.Defer) }),
            ).handle(deliver(delivery), result)
            val expected: SchedulerIntent = if (deadline == null) {
                SchedulerIntent.Internal.Deferred(delivery.wake.id)
            } else {
                rejected(delivery, WakeFailure.OwnerRejected)
            }
            assertEquals(listOf(expected), result.sent)
        }
    }

    @Test
    fun `host rereads publisher admission at native boundary and observed refusal remains sticky`() = runTest {
        val state = MutableStateFlow(ScheduledWakeAdmission.Allow)
        val original = eventDelivery("receiver")
        var submissions = 0
        val host = object : ScheduledSessionHost by FakeHost(0) {
            override val isWakeAdmissionSupported = true
            override suspend fun wake(request: WakeRequest, prompt: WakePrompt) {
                val admission = assertNotNull(prompt.admission)
                state.value = ScheduledWakeAdmission.Drop
                assertEquals(ScheduledWakeAdmission.Drop, admission.first())
                state.value = ScheduledWakeAdmission.Allow
                assertEquals(ScheduledWakeAdmission.Drop, admission.first())
                if (admission.first() == ScheduledWakeAdmission.Drop) throw ScheduledWakeDroppedException()
                submissions++
            }
        }
        val result = RecordingScope()
        effects(
            lazyOf(setOf(host)),
            setOf(publisher { state }),
            setOf(wakeOwner(flowOf(ScheduledWakeAdmission.Allow))),
        ).handle(deliver(original), result)
        assertEquals(0, submissions)
        assertEquals(listOf(rejected(original, WakeFailure.OwnerRejected)), result.sent)
    }

    @Test
    fun `ordinary event wake requires capable host and preserves its original owner`() = runTest {
        val legacy = FakeHost(100)
        val backing = FakeHost(0)
        val capable = object : ScheduledSessionHost by backing {
            override val isWakeAdmissionSupported = true
        }
        val owner = publisher { flowOf(ScheduledWakeAdmission.Allow) }
        val delivery = eventDelivery()
        val accepted = RecordingScope()
        effects(lazyOf(setOf(legacy, capable)), setOf(owner)).handle(deliver(delivery), accepted)
        assertTrue(legacy.woken.isEmpty())
        assertEquals(delivery.wake.request, backing.woken.single().first)
        assertEquals(ScheduledWakeAdmission.Allow, assertNotNull(backing.woken.single().second.admission).first())
        assertEquals(
            listOf<SchedulerIntent>(
                SchedulerIntent.Internal.Delivered(delivery.wake.id, delivery.reason),
            ),
            accepted.sent,
        )
        val refused = RecordingScope()
        effects(lazyOf(setOf(legacy)), setOf(owner)).handle(deliver(delivery), refused)
        assertTrue(legacy.woken.isEmpty())
        assertEquals(listOf(rejected(delivery, WakeFailure.SessionUnavailable)), refused.sent)
    }

    @Test
    fun `empty failed and silent publisher gates fail closed within initial deadline`() = runTest {
        val sources = listOf<Flow<ScheduledWakeAdmission>>(
            emptyFlow(),
            flow { error("private publisher metadata") },
            flow { awaitCancellation() },
        )
        for (source in sources) {
            val delivery = eventDelivery()
            val result = RecordingScope()
            effects(lazy { error("Host must remain lazy") }, setOf(publisher { source }))
                .handle(deliver(delivery), result)
            assertEquals(listOf(rejected(delivery, WakeFailure.OwnerUnavailable)), result.sent)
        }
        assertEquals(2_000L, testScheduler.currentTime)
    }

    @Test
    fun `non feature event and deadline deliveries never resolve publisher contributions`() = runTest {
        val host = FakeHost(0)
        val original = eventDelivery()
        val ordinary = original.copy(reason = WakeReason.Event(BusEvent(KEY, EventOrigin.Host, START)))
        val deadline = original.copy(reason = WakeReason.Deadline(START))
        val effects = SchedulerEffects(
            persistence(),
            lazyOf(setOf(host)),
            lazy { error("No wake owner expected") },
            lazy { error("No publisher owner expected") },
        )
        val result = RecordingScope()
        effects.handle(SchedulerEffect.Deliver(listOf(ordinary, deadline)), result)
        assertEquals(2, host.woken.size)
        assertTrue(host.woken.all { it.second.admission == null })
    }

    @Test
    fun `self cancelled publisher settles refusal without submitting through the host`() = runTest {
        val delivery = eventDelivery("receiver")
        val result = RecordingScope()
        effects(
            lazy { error("Host must remain lazy") },
            setOf(publisher { flow { throw CancellationException("publisher cancelled") } }),
            setOf(wakeOwner(MutableStateFlow(ScheduledWakeAdmission.Allow))),
        ).handle(deliver(delivery), result)
        assertEquals(listOf(rejected(delivery, WakeFailure.Unknown)), result.sent)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `every opted in observer sees exact session request and any denial closes relay`() = runTest {
        val request = RequestId("causal_turn")
        val original = eventDelivery("receiver")
        val delivery = original.copy(
            reason = WakeReason.Event(
                BusEvent(
                    KEY,
                    EventOrigin.Session(SESSION, request),
                    START,
                ),
            ),
        )
        val observed = mutableListOf<WakeDelivery>()
        fun observer(name: String, decision: ScheduledWakeAdmission) = object : ScheduledEventOwner {
            override val feature = name
            override val isSessionOriginObserver = true
            override fun admission(delivery: WakeDelivery): Flow<ScheduledWakeAdmission> {
                observed += delivery
                return flowOf(decision)
            }
        }
        val result = RecordingScope()
        effects(
            lazy { error("Denied relay must not resolve host") },
            setOf(
                observer("first", ScheduledWakeAdmission.Allow),
                observer("second", ScheduledWakeAdmission.Drop),
                publisher { error("Publisher-only owner must not receive session observations") },
            ),
            setOf(wakeOwner(flowOf(ScheduledWakeAdmission.Allow))),
        ).handle(deliver(delivery), result)
        assertEquals(2, observed.size)
        assertTrue(observed.all { it === delivery })
        assertEquals(listOf(rejected(delivery, WakeFailure.OwnerRejected)), result.sent)
    }

    @Test
    fun `exact session relay without opted in owners preserves legacy host path`() = runTest {
        val original = eventDelivery()
        val delivery = original.copy(
            reason = WakeReason.Event(
                BusEvent(
                    KEY,
                    EventOrigin.Session(SESSION, RequestId("turn")),
                    START,
                ),
            ),
        )
        val unrelated = publisher { error("Publisher-only owner must not receive session observations") }
        for (publishers in listOf(emptySet(), setOf(unrelated))) {
            val host = FakeHost(0)
            val result = RecordingScope()
            effects(lazyOf(setOf(host)), publishers).handle(deliver(delivery), result)
            assertEquals(delivery.wake.request, host.woken.single().first)
            assertTrue(host.woken.single().second.admission == null)
            assertEquals(
                listOf<SchedulerIntent>(
                    SchedulerIntent.Internal.Delivered(delivery.wake.id, delivery.reason),
                ),
                result.sent,
            )
        }
    }

    @Test
    fun `uncorrelated session event never resolves causal observers`() = runTest {
        val original = eventDelivery()
        val delivery = original.copy(reason = WakeReason.Event(BusEvent(KEY, EventOrigin.Session(SESSION), START)))
        val host = FakeHost(0)
        val effects = SchedulerEffects(
            persistence(),
            lazyOf(setOf(host)),
            lazy { error("No wake owner") },
            lazy { error("No exact request to observe") },
        )
        effects.handle(deliver(delivery), RecordingScope())
        assertTrue(host.woken.single().second.admission == null)
    }

    @Test
    fun `host turn notification keeps trusted identity while causal observer can refuse wake`() = runTest {
        val origin = EventOrigin.HostTurn(SESSION, RequestId("host_turn"))
        val original = eventDelivery()
        val delivery = original.copy(reason = WakeReason.Event(BusEvent(KEY, origin, START)))
        val observer = object : ScheduledEventOwner {
            override val feature = "causal"
            override val isSessionOriginObserver = true
            override fun admission(delivery: WakeDelivery): Flow<ScheduledWakeAdmission> {
                assertSame(origin, (delivery.reason as WakeReason.Event).event.origin)
                return flowOf(ScheduledWakeAdmission.Drop)
            }
        }
        val result = RecordingScope()
        effects(lazy { error("Host must remain lazy") }, setOf(observer)).handle(deliver(delivery), result)
        assertEquals(listOf(rejected(delivery, WakeFailure.OwnerRejected)), result.sent)
    }
}

private fun publisher(source: (WakeDelivery) -> Flow<ScheduledWakeAdmission>) = object : ScheduledEventOwner {
    override val feature = "harness"
    override fun admission(delivery: WakeDelivery): Flow<ScheduledWakeAdmission> = source(delivery)
}

private fun wakeOwner(source: Flow<ScheduledWakeAdmission>) = object : ScheduledWakeOwner {
    override val feature = "receiver"
    override fun admission(request: WakeRequest): Flow<ScheduledWakeAdmission> = source
}

private fun eventDelivery(owner: String? = null): WakeDelivery {
    val wake = scheduled("event", events = setOf(KEY))
    return WakeDelivery(
        wake.copy(request = wake.request.copy(ownerFeature = owner)),
        WakeReason.Event(BusEvent(KEY, EventOrigin.Feature("harness", "private origin"), START, "untrusted")),
    )
}

private fun effects(
    hosts: Lazy<Set<ScheduledSessionHost>>,
    publishers: Set<ScheduledEventOwner>,
    owners: Set<ScheduledWakeOwner> = emptySet(),
) = SchedulerEffects(persistence(), hosts, lazyOf(owners), lazyOf(publishers))

private fun persistence() = SchedulerPersistence(object : WakeStorage {
    override suspend fun load(): List<ScheduledWake> = emptyList()
    override suspend fun save(wakes: List<ScheduledWake>) = error("Unexpected persistence")
})

private fun deliver(delivery: WakeDelivery) = SchedulerEffect.Deliver(listOf(delivery))
private fun rejected(delivery: WakeDelivery, failure: WakeFailure): SchedulerIntent =
    SchedulerIntent.Internal.DeliveryFailed(listOf(delivery.wake.id), failure)
private val KEY = EventKeys.custom("harness.owner.done")
