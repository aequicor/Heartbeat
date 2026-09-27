package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCheckpoint
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCoverage
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCursor
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPage
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.uuid.Uuid

/** All mutations run on the injected main dispatcher, shared with the owning native session. */
internal class KoogHistory(initial: List<SessionItem>, private val context: CoroutineContext = EmptyCoroutineContext) :
    SessionHistory {
    var coverage: HistoryCoverage = HistoryCoverage.Complete
    private var isClosed = false
    private val log = Log.tag("KoogHistory")
    private var generation = Uuid.random().toString()
    private var sequence = 0L
    private val journal = MutableStateFlow<List<SessionEvent>>(emptyList())
    private val snapshots = linkedMapOf<String, Pair<List<SessionItem>, HistoryCheckpoint>>()
    var items: List<SessionItem> = initial
        private set

    fun append(event: (HistoryCheckpoint) -> SessionEvent) {
        log.d { "Publishing history revision" }
        sequence++
        val next = event(checkpoint())
        if (next is SessionEvent.ItemUpserted) {
            items = (items.filterNot { it.info.id == next.item.info.id } + next.item).sortedBy { it.info.position }
        }
        journal.value = (journal.value + next).takeLast(JOURNAL_LIMIT)
    }

    override suspend fun page(request: HistoryPageRequest): HistoryPage = withContext(context) {
        if (isClosed) fail(EngineFailure.History(HistoryFailureReason.Unavailable))
        log.d { "Reading transcript window" }
        val window = window(request.cursor)
        val token = window.token
        val snapshot = window.snapshot
        val offset = window.offset
        val direction = window.direction
        val start = if (direction == "newer") offset else (offset - request.limit).coerceAtLeast(0)
        val end = if (direction == "older") offset else (offset + request.limit).coerceAtMost(snapshot.first.size)
        HistoryPage(
            items = snapshot.first.subList(start, end),
            older = if (start > 0) HistoryCursor("$token:older:$start") else null,
            newer = if (end < snapshot.first.size) HistoryCursor("$token:newer:$end") else null,
            checkpoint = snapshot.second,
            coverage = coverage,
        )
    }

    private fun window(cursor: HistoryCursor?): HistoryWindow {
        if (cursor == null) {
            val token = Uuid.random().toString()
            val snapshot = items to checkpoint()
            snapshots[token] = snapshot
            if (snapshots.size > SNAPSHOT_LIMIT) snapshots.remove(snapshots.keys.first())
            return HistoryWindow(token, snapshot, items.size, "older")
        }
        val parts = cursor.value.split(':')
        val snapshot = snapshots[parts.first()] ?: fail(EngineFailure.History(HistoryFailureReason.CursorExpired))
        val offset = parts.getOrNull(2)?.toIntOrNull() ?: -1
        val direction = parts.getOrNull(1)
        if (parts.size != CURSOR_PARTS || offset !in 0..snapshot.first.size || direction !in setOf("older", "newer")) {
            fail(EngineFailure.History(HistoryFailureReason.CursorExpired))
        }
        return HistoryWindow(parts.first(), snapshot, offset, requireNotNull(direction))
    }

    override fun watch(after: HistoryCheckpoint): Flow<SessionEvent> = flow {
        var seen = after.value.substringAfterLast(':').toLongOrNull() ?: -1
        emitAll(
            journal.transformWhile { events ->
                val oldest = events.firstOrNull()?.checkpoint?.value?.substringAfterLast(':')?.toLongOrNull()
                val isRetained = oldest == null || seen >= oldest - 1
                val isSameGeneration = after.value.substringBeforeLast(':') == generation
                val isReplayAvailable = isSameGeneration && seen in 0..sequence && isRetained
                if (isClosed || !isReplayAvailable) {
                    val reason = if (isClosed) HistoryFailureReason.Unavailable else HistoryFailureReason.CursorExpired
                    emit(SessionEvent.HistoryInvalidated(checkpoint(), reason))
                    false
                } else {
                    events.filter { it.checkpoint.value.substringAfterLast(':').toLong() > seen }.forEach {
                        emit(it)
                        seen = it.checkpoint.value.substringAfterLast(':').toLong()
                    }
                    true
                }
            },
        )
    }.flowOn(context)

    /** Reconcile storage without invalidating the lifetime of previously issued history handles. */
    fun restore(restored: List<SessionItem>, restoredCoverage: HistoryCoverage) {
        log.i { "Restoring durable transcript" }
        items = restored
        coverage = restoredCoverage
        generation = Uuid.random().toString()
        sequence = 0
        snapshots.clear()
        journal.value = listOf(SessionEvent.HistoryInvalidated(checkpoint(), HistoryFailureReason.CursorExpired))
    }

    fun close() {
        log.d { "Closing history observation" }
        isClosed = true
        append { SessionEvent.HistoryInvalidated(it, HistoryFailureReason.Unavailable) }
    }

    private fun checkpoint() = HistoryCheckpoint("$generation:$sequence")

    private companion object {
        const val CURSOR_PARTS = 3
        const val JOURNAL_LIMIT = 1024
        const val SNAPSHOT_LIMIT = 32
    }
}

private data class HistoryWindow(
    val token: String,
    val snapshot: Pair<List<SessionItem>, HistoryCheckpoint>,
    val offset: Int,
    val direction: String,
)
