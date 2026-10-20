package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class StudioEngineSwitchJournalTest {
    private val a = EngineTarget(EngineId("a"), EngineBindingId("one"), ModelId("model"))
    private val b = a.copy(engine = EngineId("b"))
    private var record = StudioChatRecord("chat", "Retained title", Instant.DISTANT_PAST, target = a)
    private var history: List<SessionItem> = listOf(
        SessionItem.Message(ItemInfo(ItemId("u"), 0, 0), MessageRole.User, listOf(ContentPart.Text("Original task"))),
    )
    private var writes = 0
    private var failAt = -1

    @Test
    fun `restart at every durable boundary completes once without losing transcript`() = runTest {
        for (boundary in 1..3) {
            record = StudioChatRecord("chat", "Retained title", Instant.DISTANT_PAST, target = a)
            history = history.take(1)
            writes = 0
            failAt = boundary
            assertFailsWith<IllegalStateException> { carry().switchEngine("chat", null) { journal(it, b) } }
            record = Json.decodeFromString<StudioChatRecord>(Json.encodeToString(record))
            history = Json.decodeFromString<List<SessionItem>>(Json.encodeToString(history))
            failAt = -1
            if (record.pendingEngineSwitch == null) carry().switchEngine("chat", null) { journal(it, b) }
            carry().recoverEngineSwitch("chat")
            assertNull(carry().recoverEngineSwitch("chat"))
            assertEquals(b, record.target)
            assertEquals("Retained title", record.title)
            assertNull(record.ref)
            assertNull(record.pendingEngineSwitch)
            assertEquals(1, record.contextSegment)
            assertEquals(2, history.size)
            assertTrue(history.all { it.isFrozenSegment })
            assertEquals(
                "Original task",
                (history.first() as SessionItem.Message).parts.single().let { (it as ContentPart.Text).text },
            )
        }
    }

    @Test
    fun `repeat route selection before first message preserves source history without nested seeds`() = runTest {
        carry().switchEngine("chat", null) { journal(it, b) }
        carry().switchEngine("chat", null) { journal(it, a.copy(binding = EngineBindingId("two"))) }
        carry().switchEngine("chat", null) { journal(it, a) }
        assertEquals(3, record.contextSegment)
        assertEquals(a, record.target)
        assertEquals(1, history.filterIsInstance<SessionItem.Message>().size)
        assertEquals(3, history.filterIsInstance<SessionItem.Notice>().size)
        val text = engineSwitchTranscript(history, null, "next").text
        assertEquals(1, Regex("Original task").findAll(text).count())
        assertNull(record.pendingSeed)
        assertNotNull(record.pendingEngineContext)
        assertNull(record.seeded().pendingEngineContext)
    }

    @Test
    fun `journal recovery preserves native ownership and independent chat fields`() = runTest {
        val source = SessionRef(a.engine, SessionSourceId("profile"), "native")
        record = record.copy(
            ref = source,
            projectId = "project",
            pendingEngineSwitch = journal(record.copy(ref = source), b),
        )
        carry().recoverEngineSwitch("chat")
        assertEquals(listOf(source), record.previousRefs)
        assertTrue(record.owns(source))
        assertEquals("project", record.projectId)
    }

    private fun journal(source: StudioChatRecord, target: EngineTarget) = StudioEngineSwitchJournal(
        source.ref, checkNotNull(source.target), target, StudioSessionSettings(target.studioModelId()),
        source.contextSegment + 1, "Source", "Target", setOf("harness"), source.ref, source.helper,
    )

    private fun fail() {
        check(++writes != failAt) { "simulated storage failure" }
    }

    private fun carry() = StudioContextCarry(
        StudioConversationLocks(Mutex()),
        { record },
        { history },
        { _, change ->
            fail()
            history = change(history)
        },
        { _, change ->
            fail()
            record = record.change()
        },
        object : StudioCarriedHandles {
            override suspend fun handle(id: String): ActiveSession? = null
            override suspend fun forget(id: String, closed: ActiveSession) = Unit
        },
    )
}
