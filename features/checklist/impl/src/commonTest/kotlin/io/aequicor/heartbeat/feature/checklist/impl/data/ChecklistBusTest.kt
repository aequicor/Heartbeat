package io.aequicor.heartbeat.feature.checklist.impl.data

import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.checklist.api.ChecklistAcknowledgement
import io.aequicor.heartbeat.feature.checklist.api.ChecklistCompletionMode
import io.aequicor.heartbeat.feature.checklist.api.ChecklistDelivery
import io.aequicor.heartbeat.feature.checklist.api.ChecklistEvents
import io.aequicor.heartbeat.feature.checklist.api.ChecklistJournal
import io.aequicor.heartbeat.feature.checklist.api.ChecklistStatus
import io.aequicor.heartbeat.feature.checklist.api.event
import io.aequicor.heartbeat.feature.scheduler.api.EventOrigin
import io.aequicor.heartbeat.feature.scheduler.api.RunStartedEvent
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerEvents
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerIntent
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerMachineSpec
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerOutput
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerState
import io.aequicor.heartbeat.feature.scheduler.api.WakeResultEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

class ChecklistBusTest {
    @Test
    fun `register before publishing and replay lost completion after delivery until acknowledgement`() = runTest {
        val storage = ChecklistStorage(ChecklistTestStores())
        val card = testCard().copy(status = ChecklistStatus.Completed, delivery = ChecklistDelivery.Pending)
        val journal = ChecklistJournal(listOf(card), listOf(card.event()), revision = 1)
        storage.save(journal)
        val machine = PersistingChecklist(storage, journal)
        val bus = ChecklistTestBus()
        val scheduler = TestScheduler()
        bus.beforePublish = { key ->
            if (key == ChecklistEvents.changed(card.id, ChecklistStatus.Completed)) {
                assertTrue((scheduler.state.value as SchedulerState.Ready).wakes.any { it.id == card.wakeId() })
            }
        }
        ChecklistBus(
            machine,
            storage,
            bus,
            registry(scheduler),
            ChecklistTestToggles(),
            Clock.System,
        ).start(backgroundScope)
        runCurrent()
        assertEquals(1, scheduler.schedules)
        val completed = ChecklistEvents.changed(card.id, ChecklistStatus.Completed)
        assertEquals(1, bus.published.count { it.key == completed })
        // The receiver missed this publication. Restored durable state must retry even after successful delivery.
        bus.publish(
            SchedulerEvents.WakeResult,
            EventOrigin.Host,
            Json.encodeToString(WakeResultEvent(card.wakeId(), true)),
        )
        runCurrent()
        assertEquals(ChecklistDelivery.Delivered, storage.load().cards.single().delivery)
        val count = bus.published.count { it.key == completed }
        advanceTimeBy(5001)
        runCurrent()
        assertTrue(bus.published.count { it.key == completed } > count)
        assertEquals(1, scheduler.schedules)
        bus.publish(
            ChecklistEvents.Acknowledged,
            EventOrigin.Host,
            Json.encodeToString(ChecklistAcknowledgement(card.event().eventId)),
        )
        runCurrent()
        assertTrue(storage.load().outbox.isEmpty())
        val acknowledgedCount = bus.published.count { it.key == completed }
        advanceTimeBy(5001)
        runCurrent()
        assertEquals(acknowledgedCount, bus.published.count { it.key == completed })
    }

    @Test
    fun `toggle pauses actions and agent signals cannot acknowledge or supersede host cards`() = runTest {
        val storage = ChecklistStorage(ChecklistTestStores())
        val card = testCard().copy(mode = ChecklistCompletionMode.MarkSessionReady)
        val journal = ChecklistJournal(listOf(card), listOf(card.event()), revision = 1)
        storage.save(journal)
        val machine = PersistingChecklist(storage, journal)
        val bus = ChecklistTestBus()
        val toggles = ChecklistTestToggles().apply {
            enabled.value = false
            scheduler = false
        }
        ChecklistBus(machine, storage, bus, registry(TestScheduler()), toggles, Clock.System).start(backgroundScope)
        runCurrent()
        assertTrue(bus.published.isEmpty())
        toggles.enabled.value = true
        runCurrent()
        assertTrue(bus.published.any { it.key == ChecklistEvents.changed(card.id, card.status) })
        bus.publish(
            ChecklistEvents.Acknowledged,
            EventOrigin.Session(TEST_SESSION),
            Json.encodeToString(ChecklistAcknowledgement(card.event().eventId)),
        )
        bus.publish(
            SchedulerEvents.RunStarted,
            EventOrigin.Session(TEST_SESSION),
            Json.encodeToString(
                RunStartedEvent(
                    TEST_SESSION,
                    io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId("forged"),
                    99,
                ),
            ),
        )
        runCurrent()
        assertEquals(journal, storage.load())
    }
}

private class TestScheduler : MachineRef<SchedulerState, SchedulerIntent.Public, SchedulerOutput> {
    override val name = "Scheduler"
    override val state = MutableStateFlow<SchedulerState>(SchedulerState.Ready())
    override val outputs: Flow<SchedulerOutput> = emptyFlow()
    var schedules = 0
    override suspend fun send(intent: SchedulerIntent.Public): SendResult {
        val resolution = SchedulerMachineSpec.resolve(state.value, intent) ?: return SendResult.Ignored
        state.value = resolution.to
        schedules++
        return SendResult.Accepted
    }
}

private fun registry(scheduler: TestScheduler) = object : MachineRegistry {
    @Suppress("UNCHECKED_CAST") // This fixture only exposes the scheduler key.
    override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> find(
        key: MachineKey<S, I, P, E, O>,
    ): MachineRef<S, P, O> = scheduler as MachineRef<S, P, O>
    override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> observe(
        key: MachineKey<S, I, P, E, O>,
    ): StateFlow<MachineRef<S, P, O>?> = MutableStateFlow(find(key))
    override suspend fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> send(
        key: MachineKey<S, I, P, E, O>,
        intent: P,
    ): SendResult = find(key).send(intent)
}
