package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioMessage
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioToolRun
import io.aequicor.heartbeat.feature.checklist.api.ChecklistCompletionMode
import io.aequicor.heartbeat.feature.checklist.api.ChecklistEvent
import io.aequicor.heartbeat.feature.checklist.api.ChecklistEvents
import io.aequicor.heartbeat.feature.checklist.api.ChecklistStatus
import io.aequicor.heartbeat.feature.checklist.api.event
import io.aequicor.heartbeat.feature.scheduler.api.BusEvent
import io.aequicor.heartbeat.feature.scheduler.api.EventOrigin
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerEvents
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

class StudioChecklistsTest {
    private val session = SessionRef(EngineId("test"), SessionSourceId("local"), "session")
    private val request = RequestId("request")
    private val event = ChecklistEvent(
        "card",
        1,
        session,
        request,
        "native-turn",
        null,
        ChecklistCompletionMode.MarkSessionReady,
        ChecklistStatus.Open,
    )
    private val record = StudioChatRecord(
        "chat",
        "Chat",
        Clock.System.now(),
        ref = session,
        lastRunRequest = request,
        hasLastRunSucceeded = true,
    )

    @Test
    fun `save before acknowledgement failed write retries and duplicates preserve latest revision`() = runTest {
        val stores = ChecklistTestStores()
        val bus = ChecklistTestBus()
        val projection = StudioChecklists(stores, bus, ChecklistTestToggles())
        val values = stores.keyValue(KeyValueSpec("ai_studio_checklists"))
        values.failWrites = true
        projection.receive(event.asBusEvent())
        assertTrue(bus.published.isEmpty())
        values.failWrites = false
        bus.beforePublish = { key ->
            if (key == ChecklistEvents.Acknowledged) {
                assertTrue(
                    values.values.value.isNotEmpty(),
                )
            }
        }
        projection.receive(event.asBusEvent())
        val completed = event.copy(revision = 3, status = ChecklistStatus.Completed)
        projection.receive(completed.asBusEvent())
        projection.receive(event.asBusEvent())
        assertEquals(listOf(completed), projection.events.first())
        assertEquals(3, bus.published.size)
        assertEquals(listOf(completed), StudioChecklists(stores, bus, ChecklistTestToggles()).events.first())
        projection.receive(event.copy(revision = 99).asBusEvent(EventOrigin.Session(session)))
        assertEquals(listOf(completed), projection.events.first())
    }

    @Test
    fun `all current checks and successful turn required for ready old completion cannot mark new request`() {
        assertEquals(true to false, record.checklistReadiness(listOf(event)))
        val completed = event.copy(status = ChecklistStatus.Completed)
        assertEquals(false to true, record.checklistReadiness(listOf(completed)))
        assertEquals(true to false, record.checklistReadiness(listOf(completed, event.copy(id = "other"))))
        assertEquals(false to false, record.copy(hasLastRunSucceeded = false).checklistReadiness(listOf(completed)))
        assertEquals(false to false, record.copy(hasFailed = true).checklistReadiness(listOf(completed)))
        assertEquals(
            false to false,
            record.copy(lastRunRequest = RequestId("defects")).checklistReadiness(listOf(completed)),
        )
        assertEquals(
            false to false,
            record.checklistReadiness(listOf(event.copy(mode = ChecklistCompletionMode.ResumeSession))),
        )
    }

    @Test
    fun `tool first reply anchors by native turn across streaming and restoration`() {
        val reply = StudioMessage.Reply("first-tool", Clock.System.now(), historyTurn = "native-turn")
        val attached = listOf(reply).withChecklists(listOf(event)).single() as StudioMessage.Reply
        assertEquals(listOf("card"), attached.checklistIds)
        val streamed = listOf(reply.copy(text = "Answer after tool", isStreaming = true)).withChecklists(listOf(event))
        assertEquals(attached.checklistIds, (streamed.single() as StudioMessage.Reply).checklistIds)
    }

    @Test
    fun `streaming call fallback uses its turn when a native call id repeats`() {
        val tool = StudioToolRun("call_0", "checklist_create")
        val old = StudioMessage.Reply("old", Clock.System.now(), tools = listOf(tool), historyTurn = "old-turn")
        val current = old.copy(id = "current", historyTurn = event.turn)
        val replies = listOf(old, current).withChecklists(listOf(event.copy(callId = tool.id)))
            .filterIsInstance<StudioMessage.Reply>()
        assertTrue(replies.first().checklistIds.isEmpty())
        assertEquals(listOf(event.id), replies.last().checklistIds)
    }

    @Test
    fun `a different creation result blocks a conflicting call and turn fallback`() {
        val tool = StudioToolRun("call_0", "checklist_create", createdChecklistId = "other-card")
        val reply = StudioMessage.Reply("reply", Clock.System.now(), tools = listOf(tool), historyTurn = event.turn)
        val attached = listOf(reply).withChecklists(listOf(event.copy(callId = tool.id)))
            .single() as StudioMessage.Reply
        assertTrue(attached.checklistIds.isEmpty())
    }

    @Test
    fun `replay beyond bus buffer capacity drains without deadlocking acknowledgements`() = runTest {
        val stores = ChecklistTestStores()
        stores.keyValue(ChatSpec).set(
            ChatsKey,
            (1..100).map { index ->
                record.copy(id = "chat-$index", ref = session.copy(nativeId = "session-$index"))
            },
        )
        val bus = ChecklistTestBus()
        val projection = StudioChecklists(stores, bus, ChecklistTestToggles())
        val scope = object : io.aequicor.heartbeat.core.di.ScopeHandle {
            override val name = "profile"
            override val coroutineScope = backgroundScope
            override val savedState: io.aequicor.heartbeat.core.di.ScopeSavedState get() = error("unused")
            override val isClosed = false
            override fun onClose(action: () -> Unit) = kotlinx.coroutines.DisposableHandle { }
        }
        StudioChecklistStartup(lazyOf(projection), bus, scope).start()
        bus.publish(ChecklistEvents.Synchronize, EventOrigin.Host)
        runCurrent()
        assertEquals(100, bus.published.count { it.key == SchedulerEvents.RunStarted })
        bus.publish(ChecklistEvents.changed(event.id, event.status), EventOrigin.Host, Json.encodeToString(event))
        runCurrent()
        assertTrue(bus.published.any { it.key == ChecklistEvents.Acknowledged })
    }

    private fun ChecklistEvent.asBusEvent(origin: EventOrigin = EventOrigin.Host) =
        BusEvent(ChecklistEvents.changed(id, status), origin, Clock.System.now(), Json.encodeToString(this))
}
