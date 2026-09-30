package io.aequicor.heartbeat.feature.aistudio.impl.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StudioTranscriptsTest {
    private val database = Room.inMemoryDatabaseBuilder<StudioTranscriptDatabase>()
        .setDriver(BundledSQLiteDriver())
        .build()
    private val dao = CountingDao(database.transcripts())

    /** Items a previous version kept inside the chat record, and how often they were dropped from it. */
    private var carried = emptyList<SessionItem>()
    private var contractions = 0
    private var isContractionFailing = false

    private fun transcripts() = StudioTranscripts(
        dao,
        legacy = { carried },
        contracted = {
            if (isContractionFailing) {
                isContractionFailing = false
                error("Legacy contraction interrupted")
            }
            carried = emptyList()
            contractions++
        },
    )

    @AfterTest
    fun close() = database.close()

    @Test
    fun `stored items keep their display order and every kind survives the round trip`() = runTest {
        val items = listOf(message("prompt", 0), tool("call", 1), message("answer", 2))

        transcripts().replace("chat", items)

        assertEquals(items, transcripts().read("chat"))
    }

    @Test
    fun `a revised item rewrites its own row and not the whole transcript`() = runTest {
        val store = transcripts()
        store.replace("chat", listOf(message("prompt", 0), message("answer", 1)))
        dao.written = 0

        store.replace("chat", listOf(message("prompt", 0), message("answer", 1, "streamed", revision = 1)))

        assertEquals(1, dao.written)
        assertEquals(0, dao.deleted)
        assertEquals("streamed", text(store.read("chat").last()))
    }

    @Test
    fun `an unchanged transcript writes nothing`() = runTest {
        val items = listOf(message("prompt", 0), message("answer", 1))
        val store = transcripts()
        store.replace("chat", items)
        dao.written = 0

        store.replace("chat", items)

        assertEquals(0, dao.written)
        assertEquals(0, dao.deleted)
    }

    @Test
    fun `a resumed native snapshot can replace a higher stored revision`() = runTest {
        val store = transcripts()
        store.replace("chat", listOf(message("prompt", 0), message("answer", 1, "partial", revision = 12)))
        val stored = store.read("chat")
        val resumed = listOf(message("prompt", 0), message("answer", 1, "final", revision = 0))
        dao.written = 0
        dao.reads = 0

        store.replace("chat", resumed, previousItems = stored)

        assertEquals(1, dao.written)
        assertEquals(0, dao.reads)
        assertEquals(resumed, store.read("chat"))
    }

    @Test
    fun `a changed native snapshot is stored even when its revision is unchanged`() = runTest {
        val store = transcripts()
        store.replace("chat", listOf(message("answer", 0, "partial")))
        val updated = listOf(message("answer", 0, "final"))
        dao.written = 0

        store.replace("chat", updated)

        assertEquals(1, dao.written)
        assertEquals(updated, store.read("chat"))
    }

    @Test
    fun `a shorter transcript drops the removed items and a reorder rewrites the moved rows`() = runTest {
        val store = transcripts()
        store.replace("chat", listOf(message("a", 0), message("b", 1), message("c", 2)))
        dao.written = 0

        store.replace("chat", listOf(message("c", 2), message("a", 0)))

        assertEquals(listOf("c", "a"), store.read("chat").map { it.info.id.value })
        assertEquals(1, dao.deleted)
        // Both survivors changed their place in the display order.
        assertEquals(2, dao.written)
    }

    @Test
    fun `a transcript kept inside the chat record moves to the database once`() = runTest {
        carried = listOf(message("old-prompt", 0), message("old-answer", 1))
        val store = transcripts()

        assertEquals(listOf("old-prompt", "old-answer"), store.read("chat").map { it.info.id.value })
        assertEquals(1, contractions)
        assertTrue(carried.isEmpty())

        // The database now owns the conversation: a later record is never read again.
        carried = listOf(message("late", 9))
        assertEquals(listOf("old-prompt", "old-answer"), store.read("chat").map { it.info.id.value })
        assertEquals(1, contractions)
    }

    @Test
    fun `an interrupted contraction retries without replacing newer database items`() = runTest {
        carried = listOf(message("answer", 0, "legacy"))
        val store = transcripts()
        isContractionFailing = true

        assertFailsWith<IllegalStateException> { store.read("chat") }
        val newer = listOf(message("answer", 0, "newer", revision = 1))
        store.replace("chat", newer)

        assertEquals(newer, transcripts().read("chat"))
        assertEquals(1, contractions)
        assertTrue(carried.isEmpty())
    }

    @Test
    fun `a failed contraction retries in the same instance before a cleared transcript is reopened`() = runTest {
        carried = listOf(message("answer", 0, "legacy"))
        val store = transcripts()
        isContractionFailing = true

        assertFailsWith<IllegalStateException> { store.read("chat") }
        assertEquals(carried, store.read("chat"))
        assertEquals(1, contractions)
        store.replace("chat", emptyList())

        assertTrue(store.read("chat").isEmpty())
        assertTrue(transcripts().read("chat").isEmpty())
    }

    @Test
    fun `a corrupt row does not hide other items and a native snapshot repairs it`() = runTest {
        val store = transcripts()
        val items = listOf(message("prompt", 0), message("answer", 1))
        store.replace("chat", items)
        dao.upsert(listOf(StudioTranscriptEntity("chat", "answer", 1, 0, "invalid json")))
        val visible = store.read("chat")

        assertEquals(listOf(items.first()), visible)
        assertEquals(visible, store.observe("chat").first())
        dao.written = 0
        store.replace("chat", items, previousItems = visible)

        assertEquals(1, dao.written)
        assertEquals(items, store.read("chat"))
    }

    @Test
    fun `a conversation without items anywhere stays empty`() = runTest {
        val store = transcripts()

        assertTrue(store.read("chat").isEmpty())
        assertEquals(0, contractions)
        assertEquals(0, dao.written)
    }

    private fun text(item: SessionItem) = ((item as SessionItem.Message).parts.single() as ContentPart.Text).text

    private fun message(id: String, position: Long, text: String = id, revision: Long = 0) = SessionItem.Message(
        ItemInfo(ItemId(id), position, revision),
        MessageRole.User,
        listOf(ContentPart.Text(text)),
    )

    private fun tool(id: String, position: Long) = SessionItem.ToolCall(
        ItemInfo(ItemId(id), position, 0),
        ToolCallId(id),
        "powershell",
        "{}",
        ToolCallStatus.Succeeded,
    )

    /** Counts the rows a write touched, so a targeted write is distinguishable from a rewrite of the transcript. */
    private class CountingDao(private val dao: StudioTranscriptDao) : StudioTranscriptDao {
        var written = 0
        var deleted = 0
        var reads = 0

        override fun observe(chatId: String): Flow<List<StudioTranscriptEntity>> = dao.observe(chatId)
        override suspend fun items(chatId: String): List<StudioTranscriptEntity> {
            reads++
            return dao.items(chatId)
        }
        override suspend fun positions(chatId: String): List<StudioTranscriptPosition> = dao.positions(chatId)

        override suspend fun upsert(rows: List<StudioTranscriptEntity>) {
            written += rows.size
            dao.upsert(rows)
        }

        override suspend fun delete(chatId: String, itemIds: List<String>) {
            deleted += itemIds.size
            dao.delete(chatId, itemIds)
        }
    }
}
