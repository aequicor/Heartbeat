package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCheckpoint
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ClaudeHistoryTest {
    @Test
    fun `changing page size when moving forward never skips items`() = runTest {
        val history = ClaudeHistory()
        repeat(10) { index -> history.item(TurnId("turn")) { SessionItem.Notice(it, "$index") } }
        val latest = history.page(HistoryPageRequest(limit = 2))
        val older = history.page(HistoryPageRequest(latest.older, limit = 3))
        val newer = history.page(HistoryPageRequest(older.newer, limit = 1))
        assertEquals(listOf(5L, 6L, 7L), older.items.map { it.info.position })
        assertEquals(listOf(8L), newer.items.map { it.info.position })
    }

    @Test
    fun `watch replays events that happened after atomic page checkpoint`() = runTest {
        val history = ClaudeHistory()
        val checkpoint = history.page().checkpoint
        repeat(2) { history.item(TurnId("turn")) { info -> SessionItem.Notice(info, "update") } }
        val events = history.watch(checkpoint).take(2).toList()
        assertEquals(2, events.size)
        events.forEach { assertIs<SessionEvent.ItemUpserted>(it) }
    }

    @Test
    fun `foreign and expired checkpoints emit invalidation and complete`() = runTest {
        val history = ClaudeHistory()
        assertIs<SessionEvent.HistoryInvalidated>(history.watch(HistoryCheckpoint("old-runtime:2")).toList().single())
        val checkpoint = history.page().checkpoint
        repeat(300) { history.item(TurnId("turn")) { info -> SessionItem.Notice(info, "update") } }
        assertIs<SessionEvent.HistoryInvalidated>(history.watch(checkpoint).toList().single())
    }

    @Test
    fun `history is bounded by total text size, keeping the newest items`() = runTest {
        val history = ClaudeHistory()
        val chunk = "x".repeat((MAX_ITEM_CHARS / 4).toInt())
        repeat(6) { history.item(TurnId("turn")) { info -> SessionItem.Notice(info, chunk) } }
        val page = history.page(HistoryPageRequest(limit = 100))
        assertEquals(listOf(2L, 3L, 4L, 5L), page.items.map { it.info.position })
    }

    @Test
    fun `an oversized item is still retained as the newest one`() = runTest {
        val history = ClaudeHistory()
        history.item(TurnId("turn")) { SessionItem.Notice(it, "small") }
        history.item(TurnId("turn")) { SessionItem.Notice(it, "x".repeat((MAX_ITEM_CHARS + 1).toInt())) }
        assertEquals(listOf(1L), history.page().items.map { it.info.position })
    }
}
