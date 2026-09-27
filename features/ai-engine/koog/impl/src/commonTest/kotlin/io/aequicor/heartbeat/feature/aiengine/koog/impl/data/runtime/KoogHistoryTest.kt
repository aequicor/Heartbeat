package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSummary
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineId
import io.aequicor.heartbeat.feature.aiengine.koog.impl.data.KoogRecord
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

    @Test
    fun `streamed revisions of one item keep only the latest in the journal`() = runTest {
        val history = KoogHistory(emptyList())
        val checkpoint = history.page().checkpoint
        repeat(2000) { revision ->
            history.append { SessionEvent.ItemUpserted(it, message("answer", 0, revision.toString())) }
        }
        val event = assertIs<SessionEvent.ItemUpserted>(history.watch(checkpoint).first())
        assertEquals(listOf(ContentPart.Text("1999")), assertIs<SessionItem.Message>(event.item).parts)
        assertEquals(1, history.items.size)
    }

    @Test
    fun `idle transcripts beyond the limit are evicted and reloaded while pinned ones stay`() = runTest {
        val profile = FakeProfile(backgroundScope)
        val cache = KoogSessionCache(profile)
        val records = (0..9).map { record(it.toString()) }
        cache.get(records[0])
        cache.pin(records[0].summary.ref)
        records.drop(1).forEach { cache.get(it) }
        var loads = 0
        cache.history(records[0].summary.ref) { records[0].also { loads++ } }
        cache.history(records[9].summary.ref) { records[9].also { loads++ } }
        assertEquals(0, loads)
        cache.history(records[1].summary.ref) { records[1].also { loads++ } }
        assertEquals(1, loads)
    }

    private fun record(id: String) = KoogRecord(
        SessionSummary(SessionRef(KoogEngineId, KoogSessionSource.id, id)),
        ModelId("model"),
        listOf(message(id, 0)),
    )

    private fun message(id: String, position: Long, text: String = id) = SessionItem.Message(
        ItemInfo(ItemId(id), position, 0),
        MessageRole.User,
        listOf(ContentPart.Text(text)),
    )
}
