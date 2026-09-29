package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCheckpoint
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCoverage
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.isActive

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
    private val update: suspend (String, StudioChatRecord.() -> StudioChatRecord) -> Unit,
) {
    private val log = Log.tag("StudioHistoryMirror")

    suspend fun refresh(id: String, history: SessionHistory): HistoryCheckpoint = load(id, history).checkpoint

    suspend fun follow(id: String, history: SessionHistory) {
        while (currentCoroutineContext().isActive) {
            val snapshot = load(id, history)
            val window = snapshot.window.toMutableSet()
            val revisions = read(id).associate { it.info.id to it.info.revision }.toMutableMap()
            var isInvalidated = false
            history.watch(snapshot.checkpoint).takeWhile { event ->
                isInvalidated = event is SessionEvent.HistoryInvalidated
                return@takeWhile !isInvalidated
            }.collect { event ->
                when (event) {
                    is SessionEvent.ItemUpserted -> if (event.item.info.revision >
                        (revisions[event.item.info.id] ?: -1)
                    ) {
                        revisions[event.item.info.id] = event.item.info.revision
                        window += event.item.info.id
                        update(id) { copy(items = items.upsert(event.item, window)) }
                    }

                    is SessionEvent.ItemRemoved -> if (event.revision > (revisions[event.item] ?: -1)) {
                        revisions[event.item] = event.revision
                        window -= event.item
                        update(id) { copy(items = items.filterNot { it.info.id == event.item }) }
                    }

                    is SessionEvent.HistoryInvalidated, is SessionEvent.PermissionRequested,
                    is SessionEvent.TurnStarted, is SessionEvent.TurnFinished,
                    -> Unit
                }
            }
            check(isInvalidated) { "History stream ended without invalidation" }
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
        update(id) {
            val earlier = if (isComplete) emptyList() else keepEarlier(this.items, window)
            kept = earlier.size
            copy(items = earlier + window)
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
