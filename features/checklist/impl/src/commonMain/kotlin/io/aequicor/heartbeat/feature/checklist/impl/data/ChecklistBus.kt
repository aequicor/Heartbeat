package io.aequicor.heartbeat.feature.checklist.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.feature.checklist.api.Checklist
import io.aequicor.heartbeat.feature.checklist.api.ChecklistAcknowledgement
import io.aequicor.heartbeat.feature.checklist.api.ChecklistCompletionMode
import io.aequicor.heartbeat.feature.checklist.api.ChecklistDelivery
import io.aequicor.heartbeat.feature.checklist.api.ChecklistEnabled
import io.aequicor.heartbeat.feature.checklist.api.ChecklistEvents
import io.aequicor.heartbeat.feature.checklist.api.ChecklistIntent
import io.aequicor.heartbeat.feature.checklist.api.ChecklistJournal
import io.aequicor.heartbeat.feature.checklist.api.ChecklistState
import io.aequicor.heartbeat.feature.checklist.api.ChecklistStatus
import io.aequicor.heartbeat.feature.checklist.api.event
import io.aequicor.heartbeat.feature.checklist.impl.domain.ChecklistMachine
import io.aequicor.heartbeat.feature.scheduler.api.BusEvent
import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import io.aequicor.heartbeat.feature.scheduler.api.EventOrigin
import io.aequicor.heartbeat.feature.scheduler.api.RunStartedEvent
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerBus
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerEnabled
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerEvents
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerIntent
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerMachineKey
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerState
import io.aequicor.heartbeat.feature.scheduler.api.WakeCondition
import io.aequicor.heartbeat.feature.scheduler.api.WakeId
import io.aequicor.heartbeat.feature.scheduler.api.WakeOrigin
import io.aequicor.heartbeat.feature.scheduler.api.WakeRequest
import io.aequicor.heartbeat.feature.scheduler.api.WakeResultEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/** Durable publication on the scheduler bus, with replay until the studio and the scheduler confirm processing. */
@Inject
internal class ChecklistBus(
    private val machine: ChecklistMachine,
    private val storage: ChecklistStorage,
    private val bus: SchedulerBus,
    private val machines: MachineRegistry,
    private val toggles: FeatureToggles,
    private val clock: Clock,
) {
    private val log = Log.tag("ChecklistBus")

    fun start(scope: CoroutineScope) {
        scope.launch {
            machine.state.first { it is ChecklistState.Ready }
            toggles.observe(ChecklistEnabled).collectLatest { enabled ->
                if (enabled) runEnabled()
            }
        }
    }

    private suspend fun runEnabled() = coroutineScope {
        launch(start = CoroutineStart.UNDISPATCHED) { bus.events.collect(::received) }
        launch {
            while (true) {
                bus.publish(ChecklistEvents.Synchronize, EventOrigin.Host)
                delay(RETRY)
            }
        }
        storage.observe().filterNotNull().collectLatest { journal ->
            while (true) {
                try {
                    publish(journal)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.w(e) { "Checklist publication failed; durable events will be retried" }
                }
                delay(RETRY)
            }
        }
    }

    private suspend fun publish(journal: ChecklistJournal) {
        for (event in journal.outbox) {
            val isWakeRequired =
                event.status == ChecklistStatus.Completed && event.mode == ChecklistCompletionMode.ResumeSession
            val isPending = journal.cards.any { it.id == event.id && it.delivery == ChecklistDelivery.Pending }
            if (!isWakeRequired || !isPending) {
                bus.publish(
                    ChecklistEvents.changed(event.id, event.status),
                    EventOrigin.Host,
                    Json.encodeToString(event),
                )
            }
        }
        if (toggles.get(SchedulerEnabled)) {
            journal.cards.filter { it.delivery == ChecklistDelivery.Pending }.forEach { resume(it) }
        }
    }

    private suspend fun resume(card: Checklist) {
        val scheduler = machines.find(SchedulerMachineKey) ?: return
        val current = scheduler.state.value as? SchedulerState.Ready ?: return
        val id = card.wakeId()
        if (id in current.delivering) return
        if (current.wakes.none { it.id == id }) {
            scheduler.send(SchedulerIntent.Public.Schedule(card.wake(), clock.now()))
        }
        val armed = scheduler.state.value as? SchedulerState.Ready ?: return
        if (armed.wakes.none { it.id == id }) return
        bus.publish(
            ChecklistEvents.changed(card.id, ChecklistStatus.Completed),
            EventOrigin.Host,
            Json.encodeToString(card.event()),
        )
    }

    private suspend fun received(event: BusEvent) {
        if (event.origin != EventOrigin.Host) return
        val payload = event.payload ?: return
        try {
            when (event.key) {
                ChecklistEvents.Acknowledged -> acknowledge(Json.decodeFromString(payload))
                SchedulerEvents.RunStarted -> started(Json.decodeFromString(payload))
                SchedulerEvents.WakeResult -> delivered(Json.decodeFromString(payload))
            }
        } catch (e: IllegalArgumentException) {
            log.w(e.withoutChecklistText()) { "Malformed checklist event" }
        }
    }

    private suspend fun acknowledge(ack: ChecklistAcknowledgement) {
        val state = machine.state.value as? ChecklistState.Ready ?: return
        if (state.journal.outbox.any { it.eventId == ack.eventId }) {
            machine.send(ChecklistIntent.Internal.Acknowledged(ack.eventId))
        }
    }

    private suspend fun started(started: RunStartedEvent) {
        val state = machine.state.value as? ChecklistState.Ready ?: return
        val key = EventKeys.sessionSegment(started.session)
        if (started.revision > (state.journal.generationRevisions[key] ?: -1)) {
            machine.send(ChecklistIntent.Internal.Started(started.session, started.request, started.revision))
        }
    }

    private suspend fun delivered(result: WakeResultEvent) {
        if (result.isDeferred) return
        val state = machine.state.value as? ChecklistState.Ready ?: return
        val card = state.journal.cards.firstOrNull {
            it.wakeId() == result.id && it.delivery == ChecklistDelivery.Pending
        } ?: return
        machine.send(ChecklistIntent.Internal.Delivered(card.id, card.attempt, result.isSuccessful))
    }

    private companion object {
        val RETRY = 5.seconds
    }
}

internal fun Checklist.wakeId(): WakeId = WakeId("checklist_${id}_$attempt")

private fun Checklist.wake() = WakeRequest(
    id = wakeId(),
    session = session,
    workspace = workspace,
    target = target,
    condition = WakeCondition(setOf(ChecklistEvents.changed(id, ChecklistStatus.Completed))),
    note = "The user completed checklist $id. Read its frozen answers using checklist_get and continue the task.",
    origin = WakeOrigin.Feature(ChecklistEvents.OWNER, "Checklist"),
    isDeduplicationRequired = true,
    ownerFeature = ChecklistEvents.OWNER,
)
