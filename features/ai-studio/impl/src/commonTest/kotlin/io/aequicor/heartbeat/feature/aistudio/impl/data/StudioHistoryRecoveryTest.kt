package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCheckpoint
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCoverage
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPage
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class StudioHistoryRecoveryTest {
    private val records = mutableMapOf<String, StudioChatRecord>()
    private val mirror = StudioHistoryMirror(
        read = { id -> record(id).items },
        update = { id, change -> records[id] = record(id).change() },
    )

    @Test
    fun `invalidation flushes pending edits before reloading and follows the new checkpoint`() = runTest {
        val original = message("answer", revision = 0)
        val buffered = message("answer", revision = 1)
        val next = message("next", position = 1)
        val history = ControlledHistory(listOf(original))
        val beforeReload = mutableListOf<List<SessionItem>>()
        history.beforePage = { if (history.pages > 1) beforeReload += record("chat").items }
        val following = launch { mirror.follow("chat", history) }
        runCurrent()

        history.upsert(buffered)
        history.invalidate(listOf(buffered, next))
        runCurrent()

        assertEquals(listOf(buffered), beforeReload.single())
        assertEquals(listOf(INITIAL, RELOADED), history.watched)
        assertEquals(listOf(buffered, next), record("chat").items)

        val latest = message("next", position = 1, revision = 2)
        history.upsert(latest)
        history.upsert(latest)
        history.upsert(next)
        runCurrent()
        advanceTimeBy(250)
        runCurrent()

        assertEquals(listOf(buffered, latest), record("chat").items)
        assertTrue(following.isActive)
        following.cancelAndJoin()
    }

    @Test
    fun `invalidating and cancelling one project leaves the other project streaming`() = runTest {
        val first = ControlledHistory()
        val second = ControlledHistory()
        val followingFirst = launch { mirror.follow("project-a", first) }
        val followingSecond = launch { mirror.follow("project-b", second) }
        runCurrent()

        val firstAnswer = message("answer-a")
        val secondAnswer = message("answer-b")
        first.upsert(firstAnswer)
        second.upsert(secondAnswer)
        first.invalidate(listOf(firstAnswer))
        runCurrent()
        advanceTimeBy(250)
        runCurrent()

        assertEquals(listOf(INITIAL, RELOADED), first.watched)
        assertEquals(listOf(INITIAL), second.watched)
        assertEquals(listOf(firstAnswer), record("project-a").items)
        assertEquals(listOf(secondAnswer), record("project-b").items)
        followingFirst.cancelAndJoin()

        val latest = message("answer-b", revision = 1)
        second.upsert(latest)
        runCurrent()
        advanceTimeBy(250)
        runCurrent()

        assertTrue(followingSecond.isActive)
        assertEquals(listOf(firstAnswer), record("project-a").items)
        assertEquals(listOf(latest), record("project-b").items)
        followingSecond.cancelAndJoin()
    }

    @Test
    fun `history ending without invalidation fails promptly and flushes pending edits`() = runTest {
        val history = ControlledHistory()
        val following = async {
            assertFailsWith<IllegalStateException> { mirror.follow("chat", history) }
        }
        runCurrent()

        val answer = message("answer")
        history.upsert(answer)
        history.events.close()
        runCurrent()

        assertTrue(following.isCompleted)
        assertEquals("History stream ended without invalidation", following.await().message)
        assertEquals(listOf(answer), record("chat").items)
        assertEquals(listOf(INITIAL), history.watched)
    }

    private fun record(id: String) = records.getOrPut(id) {
        StudioChatRecord(id, id, Instant.fromEpochSeconds(0))
    }

    private fun message(id: String, position: Long = 0, revision: Long = 0) = SessionItem.Message(
        ItemInfo(ItemId(id), position, revision),
        MessageRole.Assistant,
        listOf(ContentPart.Text("$id revision $revision")),
    )

    /** Each subscription consumes controlled events until invalidation or explicit channel completion. */
    private class ControlledHistory(private var items: List<SessionItem> = emptyList()) : SessionHistory {
        val events = Channel<SessionEvent>(Channel.UNLIMITED)
        val watched = mutableListOf<HistoryCheckpoint>()
        var pages = 0
            private set
        var beforePage: () -> Unit = {}
        private var checkpoint = INITIAL

        override suspend fun page(request: HistoryPageRequest): HistoryPage {
            pages++
            beforePage()
            return HistoryPage(items, null, null, checkpoint, HistoryCoverage.Complete)
        }

        override fun watch(after: HistoryCheckpoint): Flow<SessionEvent> = flow {
            watched += after
            for (event in events) emit(event)
        }

        suspend fun upsert(item: SessionItem) {
            events.send(SessionEvent.ItemUpserted(checkpoint, item))
        }

        suspend fun invalidate(replacement: List<SessionItem>) {
            items = replacement
            checkpoint = RELOADED
            events.send(SessionEvent.HistoryInvalidated(checkpoint, HistoryFailureReason.CursorExpired))
        }
    }

    private companion object {
        val INITIAL = HistoryCheckpoint("initial")
        val RELOADED = HistoryCheckpoint("reloaded")
    }
}
