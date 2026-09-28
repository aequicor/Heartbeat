package io.aequicor.heartbeat.feature.aiengine.codex.impl.data
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCheckpoint
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCoverage
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCursor
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPage
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallStatus
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.Uuid

/** Bounded journal; slow consumers receive invalidation rather than a silently truncated stream. Main confined. */
internal class CodexHistory : SessionHistory {
    private val log = Log.tag("CodexHistory")
    private val generation = Uuid.random().toString()
    private var sequence = 0L
    private val items = linkedMapOf<ItemId, SessionItem>()

    /** [floor] is the last sequence lost to truncation; checkpoints below it cannot be replayed. */
    private data class Journal(val floor: Long, val events: List<SessionEvent>)
    private val journal = MutableStateFlow(Journal(0, emptyList()))
    private var isInvalid = false
    private val cursors = linkedMapOf<String, Int>()
    private fun checkpoint() = HistoryCheckpoint("$generation:$sequence")

    override suspend fun page(request: HistoryPageRequest): HistoryPage {
        log.d { "Codex history page" }
        // Invalidation is final for this adapter instance; serving pages would make reloading consumers loop.
        if (isInvalid) fail(EngineFailure.History(HistoryFailureReason.Unavailable))
        // Items are append-only with stable positions, so an older window survives streaming updates.
        val end = request.cursor?.let { cursor ->
            cursors[cursor.value] ?: fail(EngineFailure.History(HistoryFailureReason.CursorExpired))
        } ?: items.size
        val start = (end - request.limit).coerceAtLeast(0)
        val older = if (start > 0) {
            val token = Uuid.random().toString()
            if (cursors.size >= JOURNAL_LIMIT) cursors.remove(cursors.keys.first())
            cursors[token] = start
            HistoryCursor(token)
        } else {
            null
        }
        return HistoryPage(
            items.values.toList().subList(start, end),
            older,
            null,
            checkpoint(),
            HistoryCoverage.Partial,
        )
    }

    override fun watch(after: HistoryCheckpoint): Flow<SessionEvent> = flow {
        if (isInvalid) {
            emit(SessionEvent.HistoryInvalidated(checkpoint(), HistoryFailureReason.Unavailable))
            return@flow
        }
        val position = position(after)
        if (position == null) {
            emit(SessionEvent.HistoryInvalidated(checkpoint(), HistoryFailureReason.CursorExpired))
            return@flow
        }
        journal.takeWhile { replay(it, position) }.collect()
    }

    private fun position(after: HistoryCheckpoint): JournalPosition? {
        if (!after.value.startsWith("$generation:")) return null
        val sequence = after.value.substringAfterLast(':').toLongOrNull() ?: return null
        return if (sequence in 0..this.sequence) JournalPosition(sequence) else null
    }

    private suspend fun FlowCollector<SessionEvent>.replay(journal: Journal, position: JournalPosition): Boolean {
        if (position.sequence < journal.floor) {
            emit(SessionEvent.HistoryInvalidated(checkpoint(), HistoryFailureReason.CursorExpired))
            return false
        }
        for (event in journal.events) {
            val sequence = event.sequence()
            if (sequence <= position.sequence) continue
            emit(event)
            position.advanceTo(sequence)
            if (event is SessionEvent.HistoryInvalidated) return false
        }
        return true
    }

    private class JournalPosition(initial: Long) {
        var sequence: Long = initial
            private set
        fun advanceTo(value: Long) {
            require(value >= sequence)
            sequence = value
        }
    }

    fun publish(make: (HistoryCheckpoint) -> SessionEvent) {
        log.d { "Codex history revision advanced" }
        sequence++
        val event = make(checkpoint())
        val current = journal.value
        // Streaming deltas upsert the same item back to back; the newest snapshot supersedes the previous one.
        val last = current.events.lastOrNull()
        val isSuperseded = event is SessionEvent.ItemUpserted && last is SessionEvent.ItemUpserted &&
            last.item.info.id == event.item.info.id
        val events = (if (isSuperseded) current.events.dropLast(1) else current.events) + event
        val overflow = events.size - JOURNAL_LIMIT
        journal.value = if (overflow > 0) {
            Journal(events[overflow - 1].sequence(), events.drop(overflow))
        } else {
            Journal(current.floor, events)
        }
    }

    private fun SessionEvent.sequence(): Long = checkpoint.value.substringAfterLast(':').toLong()

    fun invalidate() {
        isInvalid = true
        publish { SessionEvent.HistoryInvalidated(it, HistoryFailureReason.Unavailable) }
    }

    fun nativeItem(native: JsonObject, turn: TurnId?) {
        val id = ItemId(native.text("id") ?: protocolFailure())
        val old = items[id]
        val info = ItemInfo(
            id,
            old?.info?.position ?: items.size.toLong(),
            (old?.info?.revision ?: -1) + 1,
            turn ?: old?.info?.turn,
        )
        val item = when (val kind = native.text("type")) {
            "agentMessage" -> SessionItem.Message(
                info,
                MessageRole.Assistant,
                listOf(ContentPart.Text(native.text("text").orEmpty())),
            )

            "userMessage" -> SessionItem.Message(
                info,
                MessageRole.User,
                native.array("content").map { part ->
                    val value = part as? JsonObject ?: protocolFailure()
                    ContentPart.Text(value.text("text") ?: "[Unsupported input]")
                },
            )

            "commandExecution" -> SessionItem.ToolCall(
                info,
                ToolCallId(id.value),
                "command",
                native.text("command").orEmpty(),
                toolStatus(native.text("status")),
            )

            else -> SessionItem.UnsupportedItem(info, kind?.take(MAX_KIND_LENGTH) ?: "unknown")
        }
        items[id] = item
        publish { SessionEvent.ItemUpserted(it, item) }
    }

    fun delta(native: JsonObject, turn: TurnId?) {
        val id = ItemId(native.text("itemId") ?: protocolFailure())
        val previous = items[id] as? SessionItem.Message
        val text = previous?.parts?.filterIsInstance<ContentPart.Text>()?.joinToString("") { it.text }.orEmpty()
        nativeItem(
            json(
                "id" to id.value.json(),
                "type" to "agentMessage".json(),
                "text" to (text + native.text("delta").orEmpty()).json(),
            ),
            turn,
        )
    }

    private fun toolStatus(status: String?): ToolCallStatus = when (status) {
        "completed" -> ToolCallStatus.Succeeded
        "failed" -> ToolCallStatus.Failed
        "declined" -> ToolCallStatus.Cancelled
        else -> ToolCallStatus.Running
    }

    private companion object {
        const val JOURNAL_LIMIT = 512
        const val MAX_KIND_LENGTH = 80
    }
}
