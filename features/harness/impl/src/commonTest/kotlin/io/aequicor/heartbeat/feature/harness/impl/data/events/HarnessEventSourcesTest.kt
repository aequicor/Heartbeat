package io.aequicor.heartbeat.feature.harness.impl.data.events

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineInfo
import io.aequicor.heartbeat.feature.harness.api.event.HarnessBusEvent
import io.aequicor.heartbeat.feature.harness.api.event.HarnessEvent
import io.aequicor.heartbeat.feature.harness.api.event.HarnessLifecycleEvent
import io.aequicor.heartbeat.feature.harness.api.event.SchedulerEvent
import io.aequicor.heartbeat.feature.harness.api.event.SystemEvent
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessDispatchFixture
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessEventGate
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.RegistrationTestOrigins
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.scriptRequest
import io.aequicor.heartbeat.feature.scheduler.api.BusEvent
import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import io.aequicor.heartbeat.feature.scheduler.api.EventOrigin
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerOutput
import io.aequicor.heartbeat.feature.scheduler.api.WakeId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class HarnessEventSourcesTest {
    @Test
    fun `start synchronously attaches every hot input before library can start`() = runTest {
        val fixture = SourcesFixture(this)
        assertEquals(0, fixture.factoryCalls)
        fixture.start()
        assertEquals(1, fixture.factoryCalls)
        assertEquals(1, fixture.preparations)
        assertEquals(List(6) { 1 }, fixture.subscriptions())
    }

    @Test
    fun `start attaches nested machine output flow before first synchronous output`() = runTest {
        val fixture = SourcesFixture(this)
        val machine = MutableStateFlow<Flow<SchedulerOutput>?>(fixture.scheduler)
        fixture.schedulerInput = HarnessSwitchingEvents(machine) { it }
        fixture.dispatch.activate()
        fixture.start()
        // Machine Start may emit immediately after start returns, without advancing the test dispatcher.
        assertEquals(1, fixture.scheduler.subscriptionCount.value)
        val first = SchedulerOutput.Cancelled(listOf(WakeId("first")))
        fixture.scheduler.emit(first)
        runCurrent()
        assertEquals(listOf(first.ids), fixture.cancelled())
        val replacement = MutableSharedFlow<SchedulerOutput>()
        machine.value = replacement
        runCurrent()
        assertEquals(0, fixture.scheduler.subscriptionCount.value)
        assertEquals(1, replacement.subscriptionCount.value)
        fixture.scheduler.emit(SchedulerOutput.Cancelled(listOf(WakeId("obsolete"))))
        val second = SchedulerOutput.Cancelled(listOf(WakeId("second")))
        replacement.emit(second)
        runCurrent()
        assertEquals(listOf(first.ids, second.ids), fixture.cancelled())
    }

    @Test
    fun `replacing machine fences delayed cleanup output before attaching new collector`() = runTest {
        val release = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val stale = object : Flow<Int> {
            override suspend fun collect(collector: FlowCollector<Int>) {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleanupStarted.complete(Unit)
                        release.await()
                        collector.emit(1)
                    }
                }
            }
        }
        val current = MutableStateFlow<Flow<Int>?>(stale)
        val observed = mutableListOf<Int>()
        HarnessSwitchingEvents(current) { it }.subscribe(backgroundScope) { observed += it }
        val replacement = MutableSharedFlow<Int>()
        current.value = replacement
        runCurrent()
        assertTrue(cleanupStarted.isCompleted)
        assertEquals(0, replacement.subscriptionCount.value)
        release.complete(Unit)
        runCurrent()
        assertEquals(emptyList(), observed)
        assertEquals(1, replacement.subscriptionCount.value)
        replacement.emit(2)
        runCurrent()
        assertEquals(listOf(2), observed)
        current.value = null
        runCurrent()
        assertEquals(0, replacement.subscriptionCount.value)
    }

    @Test
    fun `started reaches each late instance once and starts new epoch after scheduler reenable`() = runTest {
        val fixture = SourcesFixture(this)
        fixture.scheduling.value = true
        fixture.start()
        runCurrent()
        fixture.dispatch.activate()
        runCurrent()
        assertEquals(listOf(1L), fixture.started().map { it.first })
        val firstEpoch = fixture.started().single().second.at
        fixture.scheduling.value = true
        runCurrent()
        assertEquals(1, fixture.started().size)
        fixture.dispatch.desired = scriptRequest(2)
        fixture.dispatch.activate()
        runCurrent()
        assertEquals(listOf(1L, 2L), fixture.started().map { it.first })
        assertEquals(firstEpoch, fixture.started().last().second.at)
        fixture.scheduling.value = false
        runCurrent()
        fixture.now = Instant.fromEpochMilliseconds(2_000)
        fixture.scheduling.value = true
        runCurrent()
        assertEquals(listOf(1L, 2L, 2L), fixture.started().map { it.first })
        assertEquals(fixture.now, fixture.started().last().second.at)
    }

    @Test
    fun `scheduler off suppresses system network while preserving untrusted bus delivery`() = runTest {
        val fixture = SourcesFixture(this)
        fixture.dispatch.activate()
        fixture.start()
        runCurrent()
        fixture.observed.clear()
        val event = BusEvent(EventKeys.NetworkAvailable, EventOrigin.System, fixture.now, "private payload")
        fixture.bus.emit(event)
        runCurrent()
        val delivered = fixture.observed.map { it.second }
        assertEquals(1, delivered.size)
        assertEquals("private payload", assertIs<HarnessBusEvent>(delivered.single()).payload?.text)
        assertFalse(delivered.any { it is SystemEvent.NetworkChanged })
    }

    @Test
    fun `forged host system key cannot generate trusted network event`() = runTest {
        val fixture = SourcesFixture(this)
        fixture.scheduling.value = true
        fixture.dispatch.activate()
        fixture.start()
        runCurrent()
        fixture.observed.clear()
        fixture.bus.emit(BusEvent(EventKeys.NetworkAvailable, EventOrigin.Host, fixture.now))
        runCurrent()
        assertIs<HarnessBusEvent>(fixture.observed.single().second)
        fixture.observed.clear()
        fixture.bus.emit(BusEvent(EventKeys.NetworkAvailable, EventOrigin.System, fixture.now))
        runCurrent()
        assertEquals(2, fixture.observed.size)
        assertEquals(SystemEvent.NetworkChanged(true, fixture.now), fixture.observed.last().second)
    }

    @Test
    fun `stop closes ingress immediately and next enabled branch creates fresh started epoch`() = runTest {
        val fixture = SourcesFixture(this)
        fixture.scheduling.value = true
        fixture.dispatch.activate()
        val firstBranch = fixture.start()
        runCurrent()
        assertEquals(1, fixture.started().size)
        fixture.sources.stop()
        fixture.bus.emit(BusEvent(EventKeys.NetworkLost, EventOrigin.System, fixture.now))
        runCurrent()
        assertEquals(1, fixture.started().size)
        assertTrue(fixture.observed.none { it.second is HarnessBusEvent || it.second is SystemEvent.NetworkChanged })
        firstBranch.cancelAndJoin()
        fixture.now = Instant.fromEpochMilliseconds(2_000)
        fixture.start()
        runCurrent()
        assertEquals(2, fixture.started().size)
        assertEquals(fixture.now, fixture.started().last().second.at)
        assertEquals(2, fixture.preparations)
    }
}

private class SourcesFixture(private val test: TestScope) {
    val dispatch = HarnessDispatchFixture(test.backgroundScope, StandardTestDispatcher(test.testScheduler))
    val bus = MutableSharedFlow<BusEvent>()
    val scheduler = MutableSharedFlow<SchedulerOutput>()
    var schedulerInput: HarnessEventSubscription<SchedulerOutput> = HarnessFlowEvents(scheduler)
    val scheduling = MutableStateFlow(false)
    val engines = MutableStateFlow<List<EngineInfo>>(emptyList())
    val bindings = MutableStateFlow<List<EngineBinding>>(emptyList())
    val workflows = MutableSharedFlow<HarnessLifecycleEvent.WorkflowFinished>()
    val observed = mutableListOf<Pair<Long, HarnessEvent>>()
    var now = Instant.fromEpochMilliseconds(1_000)
    var factoryCalls = 0
    var preparations = 0
    val sources = HarnessEventSources(
        {
            factoryCalls++
            HarnessEventInputs(bus, schedulerInput, scheduling, engines, bindings, HarnessFlowEvents(workflows)) {
                preparations++
            }
        },
        HarnessEventSourcePorts(
            dispatch.runtime,
            dispatch.events,
            HarnessEventGate(),
            object : Clock {
                override fun now(): Instant = now
            },
            RegistrationTestOrigins(),
        ),
    )

    init {
        dispatch.onEvaluate = { script -> script.events.on(HarnessEvent::class) { observed += script.revision to it } }
    }

    fun start(): Job {
        val job = SupervisorJob(test.backgroundScope.coroutineContext[Job])
        sources.start(CoroutineScope(test.backgroundScope.coroutineContext + job))
        return job
    }

    fun subscriptions(): List<Int> = listOf(
        bus.subscriptionCount.value,
        scheduler.subscriptionCount.value,
        scheduling.subscriptionCount.value,
        engines.subscriptionCount.value,
        bindings.subscriptionCount.value,
        workflows.subscriptionCount.value,
    )

    fun cancelled(): List<List<WakeId>> = observed.mapNotNull { (_, event) ->
        (event as? SchedulerEvent.WakesCancelled)?.ids
    }

    fun started(): List<Pair<Long, SystemEvent.Started>> = observed.mapNotNull { (revision, event) ->
        (event as? SystemEvent.Started)?.let { revision to it }
    }
}
