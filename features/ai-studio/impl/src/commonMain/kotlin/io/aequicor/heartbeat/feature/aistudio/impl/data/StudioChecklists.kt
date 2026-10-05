package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.profilefacade.ProfileStartup
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioMessage
import io.aequicor.heartbeat.feature.checklist.api.ChecklistAcknowledgement
import io.aequicor.heartbeat.feature.checklist.api.ChecklistCompletionMode
import io.aequicor.heartbeat.feature.checklist.api.ChecklistEnabled
import io.aequicor.heartbeat.feature.checklist.api.ChecklistEvent
import io.aequicor.heartbeat.feature.checklist.api.ChecklistEvents
import io.aequicor.heartbeat.feature.checklist.api.ChecklistStatus
import io.aequicor.heartbeat.feature.scheduler.api.BusEvent
import io.aequicor.heartbeat.feature.scheduler.api.EventOrigin
import io.aequicor.heartbeat.feature.scheduler.api.RunStartedEvent
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerBus
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerEvents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val ChecklistProjectionSpec = KeyValueSpec("ai_studio_checklists")
private val ChecklistProjectionKey = jsonKey("cards", ListSerializer(ChecklistEvent.serializer()))

/** Durable inbox projection. No checklist-machine outputs or cross-feature storage access are used. */
@SingleIn(ProfileScope::class)
@Inject
internal class StudioChecklists(
    @ForScope(ProfileScope::class) stores: DataStores,
    private val bus: SchedulerBus,
    private val toggles: FeatureToggles,
) {
    private val log = Log.tag("StudioChecklists")
    private val store = stores.keyValue(ChecklistProjectionSpec)
    private val chats = stores.keyValue(ChatSpec)
    private val lock = Mutex()

    val events: Flow<List<ChecklistEvent>> = combine(
        store.observe(ChecklistProjectionKey),
        toggles.observe(ChecklistEnabled),
    ) { events, enabled -> if (enabled) events.orEmpty() else emptyList() }

    suspend fun receive(event: BusEvent) {
        if (event.origin != EventOrigin.Host || !toggles.get(ChecklistEnabled)) return
        try {
            if (event.key == ChecklistEvents.Synchronize) {
                chats.get(ChatsKey).orEmpty().forEach { publishGeneration(it) }
            } else if (event.key.value.startsWith("custom.checklist.") && event.key != ChecklistEvents.Acknowledged) {
                changed(event)
            }
        } catch (e: SerializationException) {
            log.w(e.withoutChecklistPayload()) { "Checklist event rejected" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "Checklist event was not acknowledged; producer will retry" }
        }
    }
    suspend fun publishGeneration(chat: StudioChatRecord) {
        val session = chat.ref ?: return
        val request = chat.lastRunRequest ?: return
        bus.publish(
            SchedulerEvents.RunStarted,
            EventOrigin.Host,
            Json.encodeToString(RunStartedEvent(session, request, chat.runRevision)),
        )
    }

    private suspend fun changed(event: BusEvent) {
        val change = Json.decodeFromString<ChecklistEvent>(event.payload ?: return)
        if (event.key != ChecklistEvents.changed(change.id, change.status)) return
        lock.withLock {
            val current = store.get(ChecklistProjectionKey).orEmpty()
            val previous = current.firstOrNull { it.id == change.id }
            if (previous == null || previous.revision < change.revision) {
                store.set(ChecklistProjectionKey, current.filterNot { it.id == change.id } + change)
            }
        }
        bus.publish(
            ChecklistEvents.Acknowledged,
            EventOrigin.Host,
            Json.encodeToString(ChecklistAcknowledgement(change.eventId)),
        )
    }
}

/** Always listens while the profile is open; processing is gated without losing stored inbox entries. */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class StudioChecklistStartup(
    private val bridge: Lazy<StudioChecklists>,
    private val bus: SchedulerBus,
    @ForScope(ProfileScope::class) private val scope: ScopeHandle,
) : ProfileStartup {
    override fun start() {
        // Drain the shared bus independently: receive may publish ACK/replay events back to the same bus.
        val inbox = Channel<BusEvent>(Channel.UNLIMITED)
        scope.coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) { bus.events.collect { inbox.send(it) } }
        scope.coroutineScope.launch { for (event in inbox) bridge.value.receive(event) }
    }
}

/** All current checks must complete; an old generation can never mark newer work ready. */
internal fun StudioChatRecord.checklistReadiness(events: List<ChecklistEvent>): Pair<Boolean, Boolean> {
    val current = events.filter {
        it.session == ref && it.request == lastRunRequest && it.mode == ChecklistCompletionMode.MarkSessionReady
    }
    val isAwaiting = hasLastRunSucceeded && !hasFailed && current.any { it.status == ChecklistStatus.Open }
    val isReady = current.isNotEmpty() && current.all { it.status == ChecklistStatus.Completed } &&
        hasLastRunSucceeded && !hasFailed
    return isAwaiting to isReady
}

/** Resolves attachments by trusted tool/turn identities, never by list position or the currently selected chat. */
internal fun List<StudioMessage>.withChecklists(events: List<ChecklistEvent>): List<StudioMessage> {
    val anchors = events.groupBy { event ->
        val owner = filterIsInstance<StudioMessage.Reply>().firstOrNull { reply ->
            event.callId != null && reply.tools.any { it.id == event.callId }
        }
        owner?.id ?: filterIsInstance<StudioMessage.Reply>().firstOrNull { it.historyTurn == event.turn }?.id
    }
    return map { message ->
        if (message is StudioMessage.Reply) {
            message.copy(checklistIds = anchors[message.id].orEmpty().map { it.id })
        } else {
            message
        }
    }
}

private fun Exception.withoutChecklistPayload(): IllegalArgumentException =
    IllegalArgumentException("Invalid checklist payload (${this::class.simpleName.orEmpty()})")
