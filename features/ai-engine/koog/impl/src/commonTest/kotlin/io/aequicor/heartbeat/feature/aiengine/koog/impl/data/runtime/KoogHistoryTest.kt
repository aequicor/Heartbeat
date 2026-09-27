package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class KoogHistoryTest {
    @Test
    fun checkpointReplaysChangesIndependentlyForEachCollector() = runTest {
        val history = KoogHistory(emptyList())
        val page = history.page()
        history.append { SessionEvent.ItemUpserted(it, message("one", 0)) }
        val watch = history.watch(page.checkpoint)
        assertEquals(watch.first(), watch.first())
    }

    @Test
    fun pagesKeepAtomicSnapshotWhileNewMessagesArrive() = runTest {
        val history = KoogHistory(listOf(message("one", 0), message("two", 1), message("three", 2)))
        val latest = history.page(HistoryPageRequest(limit = 1))
        history.append { SessionEvent.ItemUpserted(it, message("four", 3)) }
        val older = history.page(HistoryPageRequest(latest.older, 2))
        assertEquals(listOf("one", "two"), older.items.map { it.info.id.value })
        assertEquals(latest.checkpoint, older.checkpoint)
        assertFailsWith<EngineException> { KoogHistory(emptyList()).page(HistoryPageRequest(latest.older)) }
    }

    @Test
    fun oldJournalCheckpointInvalidatesInsteadOfDroppingEvents() = runTest {
        val history = KoogHistory(emptyList())
        val checkpoint = history.page().checkpoint
        repeat(
            1030,
        ) { index -> history.append { SessionEvent.ItemUpserted(it, message(index.toString(), index.toLong())) } }
        assertIs<SessionEvent.HistoryInvalidated>(history.watch(checkpoint).first())
    }

    private fun message(id: String, position: Long) = SessionItem.Message(
        ItemInfo(ItemId(id), position, 0),
        MessageRole.User,
        listOf(ContentPart.Text(id)),
    )
}
