package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCheckpoint
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
