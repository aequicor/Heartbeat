package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCheckpoint
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCoverage
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCursor
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPage
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlin.uuid.Uuid

/** Atomic transcript snapshots with bounded replay; a slow observer reloads instead of losing revisions. */
internal class DeepSeekHistory(private val journalLimit: Int = 256) : SessionHistory {
    private val log = Log.tag("DeepSeekHistory")
    private val generation = Uuid.random().toString()
    private val state = MutableStateFlow(Snapshot())

    init {
        require(journalLimit > 0)
    }

    operator fun get(id: ItemId): SessionItem? = state.value.items[id]

    @HighFrequency
    fun upsert(item: SessionItem) {
        publish { SessionEvent.ItemUpserted(it, item) }
    }

    /** The factory can be retried on a CAS conflict and must have no side effects. */
    @HighFrequency
    fun publish(factory: (HistoryCheckpoint) -> SessionEvent) {
        log.v { "Publishing history revision" }
        state.update { old ->
            val sequence = old.sequence + 1
            val event = factory(checkpoint(sequence))
            val items = when (event) {
                is SessionEvent.ItemUpserted -> old.items + (event.item.info.id to event.item)

                is SessionEvent.ItemRemoved -> old.items - event.item

                is SessionEvent.TurnStarted, is SessionEvent.TurnFinished,
                is SessionEvent.PermissionRequested, is SessionEvent.HistoryInvalidated,
                -> old.items
            }
            old.copy(sequence = sequence, items = items, journal = (old.journal + event).takeLast(journalLimit))
        }
    }

    override suspend fun page(request: HistoryPageRequest): HistoryPage {
        val cursor = request.cursor
        val window = if (cursor == null) latest() else parse(cursor)
        val offset = window.offset
        val items = window.window.items
        val start = if (window.direction == "newer") offset else (offset - request.limit).coerceAtLeast(0)
        val end = if (window.direction == "older") offset else (offset + request.limit).coerceAtMost(items.size)
        return HistoryPage(
            items.subList(start, end),
            if (start > 0) HistoryCursor("${window.token}:older:$start") else null,
            if (end < items.size) HistoryCursor("${window.token}:newer:$end") else null,
            window.window.checkpoint,
            HistoryCoverage.Partial,
        )
    }

    override fun watch(after: HistoryCheckpoint): Flow<SessionEvent> = flow {
        var seen = after.value.substringAfterLast(':').toLongOrNull() ?: -1
        emitAll(
            state.transformWhile { current ->
                val earliest = current.journal.firstOrNull()?.sequence()?.minus(1) ?: current.sequence
                if (after.value.substringBeforeLast(':') != generation || seen !in earliest..current.sequence) {
                    emit(
                        SessionEvent.HistoryInvalidated(
                            checkpoint(current.sequence),
                            HistoryFailureReason.CursorExpired,
                        ),
                    )
                    false
                } else {
                    var isValid = true
                    for (event in current.journal.filter { it.sequence() > seen }) {
                        emit(event)
                        seen = event.sequence()
                        if (event is SessionEvent.HistoryInvalidated) {
                            isValid = false
                            break
                        }
                    }
                    isValid
                }
            },
        )
    }

    private fun latest(): Cursor {
        log.v { "Capturing transcript snapshot" }
        val token = Uuid.random().toString()
        while (true) {
            val old = state.value
            val page = Window(old.items.values.sortedBy { it.info.position }, checkpoint(old.sequence))
            val retained = (old.windows.toList() + (token to page)).takeLast(SNAPSHOT_LIMIT).toMap()
            if (state.compareAndSet(old, old.copy(windows = retained))) {
                return Cursor(token, page, page.items.size, "older")
            }
        }
    }

    private fun parse(cursor: HistoryCursor): Cursor {
        val parts = cursor.value.split(':')
        val window = state.value.windows[parts.first()]
        val offset = parts.getOrNull(2)?.toIntOrNull()
        val direction = parts.getOrNull(1)
        if (parts.size != CURSOR_PARTS || window == null || offset == null) expiredCursor()
        if (offset !in 0..window.items.size || direction !in setOf("older", "newer")) expiredCursor()
        return Cursor(parts.first(), window, offset, requireNotNull(direction))
    }

    private fun expiredCursor(): Nothing =
        throw EngineException(EngineFailure.History(HistoryFailureReason.CursorExpired))

    private fun checkpoint(sequence: Long) = HistoryCheckpoint("$generation:$sequence")

    private data class Snapshot(
        val sequence: Long = 0,
        val items: Map<ItemId, SessionItem> = emptyMap(),
        val journal: List<SessionEvent> = emptyList(),
        val windows: Map<String, Window> = emptyMap(),
    )

    private data class Window(val items: List<SessionItem>, val checkpoint: HistoryCheckpoint)
    private data class Cursor(val token: String, val window: Window, val offset: Int, val direction: String)

    private companion object {
        const val SNAPSHOT_LIMIT = 32
        const val CURSOR_PARTS = 3
    }
}

private fun SessionEvent.sequence(): Long = checkpoint.value.substringAfterLast(':').toLong()
