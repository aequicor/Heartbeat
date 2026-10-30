package io.aequicor.heartbeat.feature.aiengine.kimi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class KimiHistoryTest {
    @Test
    fun `watch replays revisions published between page and subscription`() = runTest {
        val history = KimiHistory()
        val page = history.page()
        history.upsert(message(0, "first"))
        history.upsert(message(0, "updated", revision = 1))
        val events = history.watch(page.checkpoint).take(2).toList()
        assertEquals(
            listOf("first", "updated"),
            events.map {
                val item = assertIs<SessionEvent.ItemUpserted>(it).item
                (assertIs<SessionItem.Message>(item).parts.single() as ContentPart.Text).text
            },
        )
        assertEquals(message(0, "updated", revision = 1), history.page().items.single())
    }

    @Test
    fun `expired replay invalidates instead of dropping events`() = runTest {
        val history = KimiHistory(journalLimit = 2)
        val page = history.page()
        repeat(3) { history.upsert(message(it)) }
        val events = history.watch(page.checkpoint).toList()
        val invalidated = assertIs<SessionEvent.HistoryInvalidated>(events.single())
        assertEquals(HistoryFailureReason.CursorExpired, invalidated.reason)
    }

    @Test
    fun `slow live observer receives invalidation after overflow`() = runTest {
        val history = KimiHistory(journalLimit = 2)
        val checkpoint = history.page().checkpoint
        val received = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val events = async {
            val collected = mutableListOf<SessionEvent>()
            history.watch(checkpoint).collect { event ->
                collected += event
                if (collected.size == 1) {
                    received.complete(Unit)
                    resume.await()
                }
            }
            collected
        }
        history.upsert(message(0))
        received.await()
        repeat(3) { history.upsert(message(it + 1)) }
        resume.complete(Unit)
        val collected = events.await()
        assertEquals(2, collected.size)
        assertIs<SessionEvent.HistoryInvalidated>(collected.last())
    }

    @Test
    fun `pagination traverses frozen snapshot in both directions`() = runTest {
        val history = KimiHistory()
        repeat(5) { history.upsert(message(it)) }
        val latest = history.page(HistoryPageRequest(limit = 2))
        assertEquals(listOf(message(3), message(4)), latest.items)
        assertNull(latest.newer)
        history.upsert(message(5))
        history.upsert(message(3, "new revision", revision = 1))
        val middle = history.page(HistoryPageRequest(latest.older, 2))
        assertEquals(listOf(message(1), message(2)), middle.items)
        val oldest = history.page(HistoryPageRequest(middle.older, 2))
        assertEquals(listOf(message(0)), oldest.items)
        assertNull(oldest.older)
        val newer = history.page(HistoryPageRequest(middle.newer, 2))
        assertEquals(latest.items, newer.items)
        assertEquals(latest.checkpoint, newer.checkpoint)
        assertEquals(
            listOf(message(3, "new revision", revision = 1), message(4), message(5)),
            history.page(HistoryPageRequest(limit = 3)).items,
        )
    }

    @Test
    fun `checkpoint from another session is invalidated`() = runTest {
        val first = KimiHistory().page()
        val event = KimiHistory().watch(first.checkpoint).first()
        assertIs<SessionEvent.HistoryInvalidated>(event)
    }

    private fun message(position: Int, text: String = "message-$position", revision: Long = 0): SessionItem.Message =
        SessionItem.Message(
            ItemInfo(ItemId("message-$position"), position.toLong(), revision),
            MessageRole.Assistant,
            listOf(ContentPart.Text(text)),
        )
}
