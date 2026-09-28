package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCheckpoint
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.isActive

/** Replays native item revisions without persisting opaque page or watch cursors. */
internal class StudioHistoryMirror(
    private val read: suspend (String) -> List<SessionItem>,
    private val update: suspend (String, StudioChatRecord.() -> StudioChatRecord) -> Unit,
) {
    suspend fun refresh(id: String, history: SessionHistory): HistoryCheckpoint {
        val latest = history.page(HistoryPageRequest(limit = 500))
        val items = latest.items.toMutableList()
        var cursor = latest.older
        while (cursor != null) {
            val page = history.page(HistoryPageRequest(cursor, 500))
            items.addAll(page.items)
            cursor = page.older
        }
        update(id) { copy(items = items.distinctBy { it.info.id }.sortedBy { it.info.position }) }
        return latest.checkpoint
    }

    suspend fun follow(id: String, history: SessionHistory) {
        while (currentCoroutineContext().isActive) {
            val checkpoint = refresh(id, history)
            val revisions = read(id).associate { it.info.id to it.info.revision }.toMutableMap()
            var isInvalidated = false
            history.watch(checkpoint).takeWhile { event ->
                isInvalidated = event is SessionEvent.HistoryInvalidated
                return@takeWhile !isInvalidated
            }.collect { event ->
                when (event) {
                    is SessionEvent.ItemUpserted -> if (event.item.info.revision >
                        (revisions[event.item.info.id] ?: -1)
                    ) {
                        revisions[event.item.info.id] = event.item.info.revision
                        update(
                            id,
                        ) {
                            copy(
                                items = (
                                    items.filterNot {
                                        it.info.id == event.item.info.id
                                    } + event.item
                                ).sortedBy { it.info.position },
                            )
                        }
                    }

                    is SessionEvent.ItemRemoved -> if (event.revision > (revisions[event.item] ?: -1)) {
                        revisions[event.item] = event.revision
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
}
