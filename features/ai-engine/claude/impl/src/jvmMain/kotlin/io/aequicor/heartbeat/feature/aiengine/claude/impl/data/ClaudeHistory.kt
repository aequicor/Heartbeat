package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

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
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import java.util.UUID

/** Bounded observation journal. Native disk history is not read, so coverage is always Partial. */
internal class ClaudeHistory : SessionHistory {
    private val log = Log.tag("ClaudeHistory")
    private val lock = Any()
    private val generation = UUID.randomUUID().toString()
    private val updates = MutableStateFlow(0L)
    private var sequence = 0L
    private var position = 0L
    private val items = ArrayDeque<SessionItem>()
    private val events = ArrayDeque<Pair<Long, SessionEvent>>()
    private var isClosed = false

    fun item(turn: TurnId, create: (ItemInfo) -> SessionItem) = synchronized(lock) {
        val item = create(ItemInfo(ItemId(UUID.randomUUID().toString()), position++, 0, turn))
        items.addLast(item)
        if (items.size > MAX_ITEMS) items.removeFirst()
        publish { SessionEvent.ItemUpserted(it, item) }
    }

    fun publish(create: (HistoryCheckpoint) -> SessionEvent) = synchronized(lock) {
        log.d { "Recording Claude session event" }
        sequence++
        events.addLast(sequence to create(checkpoint(sequence)))
        if (events.size > MAX_EVENTS) events.removeFirst()
        updates.value = sequence
    }

    fun close() = synchronized(lock) {
        log.d { "Closing Claude history observation" }
        isClosed = true
        publish { SessionEvent.HistoryInvalidated(it, HistoryFailureReason.Unavailable) }
    }

    override suspend fun page(request: HistoryPageRequest): HistoryPage = synchronized(lock) {
        log.d { "Reading observed Claude history" }
        if (isClosed) throw EngineException(EngineFailure.History(HistoryFailureReason.Unavailable))
        val cursor = request.cursor?.value
        val direction = cursor?.substringAfterLast(':') ?: "older"
        val boundary = cursor?.substringBeforeLast(':')?.let(::decode) ?: position
        val firstPosition = items.firstOrNull()?.info?.position ?: 0
        validateBoundary(cursor, direction, boundary, firstPosition)
        val selected = when (direction) {
            "older" -> items.filter { it.info.position < boundary }.takeLast(request.limit)
            "newer" -> items.filter { it.info.position >= boundary }.take(request.limit)
            else -> expired()
        }
        HistoryPage(
            selected,
            selected.firstOrNull()?.info?.position?.takeIf { it > firstPosition }
                ?.let { HistoryCursor("${checkpoint(it).value}:older") },
            selected.lastOrNull()?.info?.position?.plus(1)?.takeIf { it < position }
                ?.let { HistoryCursor("${checkpoint(it).value}:newer") },
            checkpoint(sequence),
            HistoryCoverage.Partial,
        )
    }

    override fun watch(after: HistoryCheckpoint): Flow<SessionEvent> = flow {
        var last = decodeOrNull(after.value)
        while (true) {
            val boundary = last
            val batch = eventsAfter(boundary)
            if (batch == null) {
                emit(SessionEvent.HistoryInvalidated(checkpoint(updates.value), HistoryFailureReason.CursorExpired))
                return@flow
            }
            for ((index, event) in batch) {
                emit(event)
                last = index
                if (event is SessionEvent.HistoryInvalidated) return@flow
            }
            val delivered = last ?: 0L
            updates.first { it > delivered }
        }
    }

    private fun validateBoundary(cursor: String?, direction: String, boundary: Long, firstPosition: Long) {
        if (boundary > position || boundary < firstPosition) expired()
        if (cursor != null && direction == "older" && boundary <= firstPosition) expired()
    }

    private fun eventsAfter(boundary: Long?): List<Pair<Long, SessionEvent>>? = synchronized(lock) {
        when {
            boundary == null || boundary > sequence ||
                boundary < (events.firstOrNull()?.first ?: 1L) - 1 -> null

            isClosed && boundary == sequence -> null

            else -> events.filter { it.first > boundary }
        }
    }

    private fun checkpoint(index: Long) = HistoryCheckpoint("$generation:$index")
    private fun decode(value: String): Long = decodeOrNull(value) ?: expired()
    private fun decodeOrNull(value: String): Long? = if (value.startsWith("$generation:")) {
        value.substringAfterLast(':').toLongOrNull()?.takeIf { it >= 0 }
    } else {
        null
    }
    private fun expired(): Nothing = throw EngineException(EngineFailure.History(HistoryFailureReason.CursorExpired))
}

private const val MAX_ITEMS = 2000
private const val MAX_EVENTS = 256
