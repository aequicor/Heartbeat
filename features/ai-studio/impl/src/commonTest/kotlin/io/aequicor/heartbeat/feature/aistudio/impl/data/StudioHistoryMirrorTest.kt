package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCheckpoint
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCoverage
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCursor
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPage
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class StudioHistoryMirrorTest {
    private var record = StudioChatRecord("chat", "Chat", Instant.fromEpochSeconds(0))
    private val mirror = StudioHistoryMirror(
        read = { record.items },
        update = { _, change -> record = record.change() },
    )

    @Test
    fun `partial history of a new journal generation keeps the stored transcript first`() = runTest {
        record = record.copy(items = listOf(message("old-prompt", 0), message("old-answer", 1)))
        val history = FakeHistory(emptyList(), HistoryCoverage.Partial)
        history.events = listOf(upsert(message("new-prompt", 0)), upsert(message("new-answer", 1)))

        val follow = launch { mirror.follow("chat", history) }
        runCurrent()
        follow.cancel()

        assertEquals(listOf("old-prompt", "old-answer", "new-prompt", "new-answer"), ids())
        history.items = listOf(message("new-prompt", 0), message("new-answer", 1, "final"))
        mirror.refresh("chat", history)
        assertEquals(listOf("old-prompt", "old-answer", "new-prompt", "new-answer"), ids())
        assertEquals("final", text(record.items.last()))
    }

    @Test
    fun `partial window replaces what it covers and keeps only earlier stored items`() = runTest {
        record = record.copy(items = listOf("a", "b", "c", "d").mapIndexed { index, id -> message(id, index.toLong()) })
        val history = FakeHistory(
            listOf(message("c", 2, "c2"), message("e", 4)),
            HistoryCoverage.Partial,
        )

        mirror.refresh("chat", history)

        assertEquals(listOf("a", "b", "c", "e"), ids())
        assertEquals("c2", text(record.items[2]))
    }

    @Test
    fun `complete history replaces the stored transcript`() = runTest {
        record = record.copy(items = listOf(message("old-prompt", 0), message("old-answer", 1)))
        val history = FakeHistory(listOf(message("prompt", 0), message("answer", 1)), HistoryCoverage.Complete)

        mirror.refresh("chat", history)

        assertEquals(listOf("prompt", "answer"), ids())
    }

    @Test
    fun `older partial page makes the whole history partial`() = runTest {
        record = record.copy(items = listOf(message("stored", 0)))
        val history = FakeHistory(listOf(message("older", 0), message("latest", 1)), HistoryCoverage.Complete)
        history.olderCoverage = HistoryCoverage.Partial

        mirror.refresh("chat", history)

        assertEquals(listOf("stored", "older", "latest"), ids())
    }

    private fun ids() = record.items.map { it.info.id.value }

    private fun text(item: SessionItem) = ((item as SessionItem.Message).parts.single() as ContentPart.Text).text

    private fun message(id: String, position: Long, text: String = id) = SessionItem.Message(
        ItemInfo(ItemId(id), position, 0),
        MessageRole.User,
        listOf(ContentPart.Text(text)),
    )

    private fun upsert(item: SessionItem) = SessionEvent.ItemUpserted(HistoryCheckpoint("next"), item)

    /** Serves [items] as one page, or two pages when [olderCoverage] is set; replays [events] once. */
    private class FakeHistory(var items: List<SessionItem>, val coverage: HistoryCoverage) : SessionHistory {
        var events: List<SessionEvent> = emptyList()
        var olderCoverage: HistoryCoverage? = null

        override suspend fun page(request: HistoryPageRequest): HistoryPage {
            val split = olderCoverage ?: return HistoryPage(items, null, null, CHECKPOINT, coverage)
            return if (request.cursor == null) {
                HistoryPage(items.drop(1), OLDER, null, CHECKPOINT, coverage)
            } else {
                HistoryPage(items.take(1), null, null, CHECKPOINT, split)
            }
        }

        override fun watch(after: HistoryCheckpoint): Flow<SessionEvent> = flow {
            events.forEach { emit(it) }
            awaitCancellation()
        }

        private companion object {
            val CHECKPOINT = HistoryCheckpoint("checkpoint")
            val OLDER = HistoryCursor("older")
        }
    }
}
