package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCheckpoint
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCoverage
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * Replays native item revisions without persisting opaque page or watch cursors.
 *
 * A stored transcript is [earlier items][keepEarlier] followed by the engine's window ordered by position.
 * The window replaces what it covers; when the engine's coverage is not [HistoryCoverage.Complete] (Partial or
 * Unknown), stored items preceding it are kept, since they are missing from the engine rather than removed.
 * They stay first because a new journal generation restarts item positions from zero, so the stored order is
 * the display order and is never re-sorted by position.
 */
internal class StudioHistoryMirror(
    private val read: suspend (String) -> List<SessionItem>,
    private val update: suspend (String, (List<SessionItem>) -> List<SessionItem>) -> Unit,
) {
    private val log = Log.tag("StudioHistoryMirror")

    private companion object {
        /** Streamed revisions coalesce into one record write per window; the final flush never waits for it. */
        val WRITE_COALESCE = 250.milliseconds
    }

    suspend fun refresh(id: String, history: SessionHistory): HistoryCheckpoint = load(id, history).checkpoint

    suspend fun follow(id: String, history: SessionHistory) {
        while (currentCoroutineContext().isActive) {
            val snapshot = load(id, history)
            val session = StreamSession(id, history, snapshot, read(id))
            try {
                session.stream()
            } finally {
                // The stop (turn end, invalidation, cancellation) flushes what the last window did not.
                withContext(NonCancellable) { session.flush() }
            }
            check(session.isInvalidated) { "History stream ended without invalidation" }
            log.d { "History stream invalidated; reload snapshot" }
        }
    }

    /**
     * Coalescing mirror of one watch stream: streamed revisions accumulate in memory and reach the transcript in
     * one write per [WRITE_COALESCE] window (plus a final flush), so a token burst does not rewrite the stored
     * items on every revision. The timer ends with its stream so invalidation can reload the snapshot and resume
     * watching from a fresh checkpoint.
     */
    private inner class StreamSession(
        val id: String,
        private val history: SessionHistory,
        snapshot: Snapshot,
        stored: List<SessionItem>,
    ) {
        private val checkpoint = snapshot.checkpoint
        private val window = snapshot.window.toMutableSet()
        private val revisions = stored.associate { it.info.id to it.info.revision }.toMutableMap()
        private val upserts = LinkedHashMap<ItemId, SessionItem>()
        private val removals = mutableSetOf<ItemId>()
        private val lock = Mutex()

        var isInvalidated = false
            private set

        suspend fun stream() = coroutineScope {
            val events = Channel<SessionEvent>(Channel.UNLIMITED)
            launch { produce(events) }
            // The timer, not the next event, drives the flush: a finished burst still reaches the record
            // within one window, and virtual-time tests observe the transcript without waiting for wall clock.
            val timer = launch {
                while (currentCoroutineContext().isActive) {
                    delay(WRITE_COALESCE)
                    flush()
                }
            }
            try {
                for (event in events) accept(event)
            } finally {
                timer.cancel()
            }
        }

        private suspend fun produce(events: SendChannel<SessionEvent>) {
            history.watch(checkpoint).takeWhile { event ->
                isInvalidated = event is SessionEvent.HistoryInvalidated
                return@takeWhile !isInvalidated
            }.collect { events.send(it) }
            events.close()
        }

        private fun apply(event: SessionEvent) {
            when (event) {
                is SessionEvent.ItemUpserted -> if (event.item.info.revision >
                    (revisions[event.item.info.id] ?: -1)
                ) {
                    revisions[event.item.info.id] = event.item.info.revision
                    window += event.item.info.id
                    removals.remove(event.item.info.id)
                    upserts[event.item.info.id] = event.item
                }

                is SessionEvent.ItemRemoved -> if (event.revision > (revisions[event.item] ?: -1)) {
                    revisions[event.item] = event.revision
                    window -= event.item
                    upserts.remove(event.item)
                    removals += event.item
                }

                is SessionEvent.HistoryInvalidated, is SessionEvent.PermissionRequested,
                is SessionEvent.TurnStarted, is SessionEvent.TurnFinished,
                -> Unit
            }
        }

        /** Buffers one streamed revision under the session lock. */
        private suspend fun accept(event: SessionEvent) = lock.withLock { apply(event) }

        /** Applies the accumulated batch in a single transcript write under the session lock; clears both queues. */
        suspend fun flush() = lock.withLock {
            if (upserts.isEmpty() && removals.isEmpty()) return@withLock
            update(id) { stored ->
                var next = if (removals.isEmpty()) stored else stored.filterNot { it.info.id in removals }
                upserts.values.forEach { item -> next = next.upsert(item, window) }
                next
            }
            upserts.clear()
            removals.clear()
        }
    }

    private suspend fun load(id: String, history: SessionHistory): Snapshot {
        val latest = history.page(HistoryPageRequest(limit = 500))
        val items = latest.items.toMutableList()
        var coverage = latest.coverage
        var cursor = latest.older
        while (cursor != null) {
            val page = history.page(HistoryPageRequest(cursor, 500))
            items.addAll(page.items)
            if (page.coverage != HistoryCoverage.Complete) coverage = page.coverage
            cursor = page.older
        }
        val window = items.distinctBy { it.info.id }.sortedBy { it.info.position }
        val isComplete = coverage == HistoryCoverage.Complete
        var kept = 0
        update(id) { stored ->
            val earlier = if (isComplete) emptyList() else keepEarlier(stored, window)
            kept = earlier.size
            earlier + window
        }
        if (kept > 0) log.i { "Engine history is ${coverage.name}; kept $kept earlier stored items" }
        return Snapshot(latest.checkpoint, window.mapTo(mutableSetOf()) { it.info.id })
    }

    private data class Snapshot(val checkpoint: HistoryCheckpoint, val window: Set<ItemId>)
}

/** Stored items before the first one the engine [window] still holds; all of them when it holds none. */
private fun keepEarlier(stored: List<SessionItem>, window: List<SessionItem>): List<SessionItem> {
    val ids = window.mapTo(mutableSetOf()) { it.info.id }
    return stored.takeWhile { it.info.id !in ids }
}

/** Replaces or inserts [item] among the engine [window] items, leaving the earlier stored items first. */
private fun List<SessionItem>.upsert(item: SessionItem, window: Set<ItemId>): List<SessionItem> {
    val (current, earlier) = partition { it.info.id in window }
    return earlier + (current.filterNot { it.info.id == item.info.id } + item).sortedBy { it.info.position }
}
