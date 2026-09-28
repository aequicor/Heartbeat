package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCheckpoint
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class CodexHistoryTest {
    @Test
    fun `deltas merge by item and replay starts after atomic page checkpoint`() = runTest {
        val history = CodexHistory()
        val checkpoint = history.page(HistoryPageRequest()).checkpoint
        history.delta(json("itemId" to "item".json(), "delta" to "one".json()), null)
        history.delta(json("itemId" to "item".json(), "delta" to "two".json()), null)
        val item = assertIs<SessionItem.Message>(history.page(HistoryPageRequest()).items.single())
        assertEquals(listOf(ContentPart.Text("onetwo")), item.parts)
        assertEquals(1L, item.info.revision)
        assertIs<SessionEvent.ItemUpserted>(history.watch(checkpoint).first())
    }

    @Test
    fun `foreign and expired checkpoints invalidate instead of losing events`() = runTest {
        val history = CodexHistory()
        val checkpoint = history.page(HistoryPageRequest()).checkpoint
        repeat(513) { history.publish { cp -> SessionEvent.TurnFinished(cp, TurnId("turn"), TurnOutcome.Completed) } }
        assertIs<SessionEvent.HistoryInvalidated>(history.watch(checkpoint).first())
        assertIs<SessionEvent.HistoryInvalidated>(history.watch(HistoryCheckpoint("foreign:0")).first())
    }

    @Test
    fun `older cursor stays valid while a reply streams`() = runTest {
        val history = CodexHistory()
        listOf("a", "b", "c").forEach { history.nativeItem(message(it), TurnId("turn")) }
        val latest = history.page(HistoryPageRequest(limit = 2))
        history.delta(json("itemId" to "c".json(), "delta" to "more".json()), null)
        val older = history.page(HistoryPageRequest(checkNotNull(latest.older), limit = 2))
        assertEquals(listOf("a"), older.items.map { it.info.id.value })
        assertEquals(null, older.older)
        val streamed = history.page().items.last()
        assertEquals(TurnId("turn"), streamed.info.turn)
    }

    @Test
    fun `long reply coalesces in journal instead of expiring watchers`() = runTest {
        val history = CodexHistory()
        val checkpoint = history.page().checkpoint
        repeat(600) { history.delta(json("itemId" to "item".json(), "delta" to "x".json()), null) }
        val event = assertIs<SessionEvent.ItemUpserted>(history.watch(checkpoint).first())
        assertEquals(listOf(ContentPart.Text("x".repeat(600))), assertIs<SessionItem.Message>(event.item).parts)
    }

    @Test
    fun `invalidated history fails reads instead of serving a replayable checkpoint`() = runTest {
        val history = CodexHistory()
        val checkpoint = history.page().checkpoint
        history.invalidate()
        val failure = assertFailsWith<EngineException> { history.page() }
        assertEquals(EngineFailure.History(HistoryFailureReason.Unavailable), failure.failure)
        val event = assertIs<SessionEvent.HistoryInvalidated>(history.watch(checkpoint).single())
        assertEquals(HistoryFailureReason.Unavailable, event.reason)
    }

    private fun message(id: String) = json("id" to id.json(), "type" to "agentMessage".json(), "text" to id.json())
}
