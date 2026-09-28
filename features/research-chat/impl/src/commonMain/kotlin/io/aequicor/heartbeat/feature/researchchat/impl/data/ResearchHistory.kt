package io.aequicor.heartbeat.feature.researchchat.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCheckpoint
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.isActive

/** Mirrors one native segment. The repository combines it with the previous visible question transcript. */
internal class ResearchHistory(private val write: suspend (List<SessionItem>) -> Unit) {
    private var items = emptyList<SessionItem>()

    suspend fun refresh(history: SessionHistory): HistoryCheckpoint {
        val latest = history.page(HistoryPageRequest(limit = PAGE_SIZE))
        val all = latest.items.toMutableList()
        var cursor = latest.older
        while (cursor != null) {
            val page = history.page(HistoryPageRequest(cursor, PAGE_SIZE))
            all.addAll(page.items)
            cursor = page.older
        }
        items = all.distinctBy { it.info.id }.sortedBy { it.info.position }
        write(items)
        return latest.checkpoint
    }

    suspend fun follow(history: SessionHistory) {
        while (currentCoroutineContext().isActive) {
            val checkpoint = refresh(history)
            var isInvalidated = false
            history.watch(checkpoint).takeWhile {
                isInvalidated = it is SessionEvent.HistoryInvalidated
                return@takeWhile !isInvalidated
            }.collect { event ->
                when (event) {
                    is SessionEvent.ItemUpserted -> {
                        val previous = items.firstOrNull { it.info.id == event.item.info.id }
                        if (previous == null || event.item.info.revision > previous.info.revision) {
                            items = (items.filterNot { it.info.id == event.item.info.id } + event.item)
                                .sortedBy { it.info.position }
                            write(items)
                        }
                    }

                    is SessionEvent.ItemRemoved -> {
                        items = items.filterNot { it.info.id == event.item }
                        write(items)
                    }

                    is SessionEvent.HistoryInvalidated, is SessionEvent.PermissionRequested,
                    is SessionEvent.TurnStarted, is SessionEvent.TurnFinished,
                    -> Unit
                }
            }
            check(isInvalidated) { "Research history observation ended" }
        }
    }

    private companion object {
        const val PAGE_SIZE = 500
    }
}

/** A native acceptance checkpoint may be older than already-persisted streamed research output. */
internal fun mergeResearchSegment(saved: List<SessionItem>, native: List<SessionItem>): List<SessionItem> =
    (saved + native).groupBy { it.info.id }.values.map { revisions -> revisions.maxBy { it.info.revision } }
        .sortedBy { it.info.position }
