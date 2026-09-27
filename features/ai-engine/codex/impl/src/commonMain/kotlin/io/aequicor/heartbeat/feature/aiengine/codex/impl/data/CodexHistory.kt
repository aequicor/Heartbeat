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
    private val journal = MutableStateFlow<List<SessionEvent>>(emptyList())
    private var isInvalid = false
    private val cursors = mutableMapOf<String, Pair<Long, Int>>()
    private fun checkpoint() = HistoryCheckpoint("$generation:$sequence")

    override suspend fun page(request: HistoryPageRequest): HistoryPage {
        log.d { "Codex history page" }
        val end = request.cursor?.let { cursor ->
            val (revision, index) = cursors[cursor.value] ?: fail(
                EngineFailure.History(HistoryFailureReason.CursorExpired),
            )
            if (revision != sequence) fail(EngineFailure.History(HistoryFailureReason.CursorExpired))
            index
        } ?: items.size
        val start = (end - request.limit).coerceAtLeast(0)
        val older = if (start > 0) {
            val token = Uuid.random().toString()
            if (cursors.size >= JOURNAL_LIMIT) cursors.clear()
            cursors[token] = sequence to start
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
        val position = position(after)
        if (position == null) {
            emit(SessionEvent.HistoryInvalidated(checkpoint(), HistoryFailureReason.CursorExpired))
            return@flow
        }
        journal.takeWhile { events -> replay(events, position) }.collect()
    }

    private fun position(after: HistoryCheckpoint): JournalPosition? {
        if (isInvalid || !after.value.startsWith("$generation:")) return null
        val sequence = after.value.substringAfterLast(':').toLongOrNull() ?: return null
        return if (sequence in 0..this.sequence) JournalPosition(sequence) else null
    }

    private suspend fun FlowCollector<SessionEvent>.replay(
        events: List<SessionEvent>,
        position: JournalPosition,
    ): Boolean {
        val first = events.firstOrNull()?.checkpoint?.value?.substringAfterLast(':')?.toLongOrNull()
        if (first != null && first > position.sequence + 1) {
            emit(SessionEvent.HistoryInvalidated(checkpoint(), HistoryFailureReason.CursorExpired))
            return false
        }
        for (event in events) {
            val sequence = event.checkpoint.value.substringAfterLast(':').toLong()
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
        journal.value = (journal.value + make(checkpoint())).takeLast(JOURNAL_LIMIT)
    }

    fun invalidate() {
        isInvalid = true
        publish { SessionEvent.HistoryInvalidated(it, HistoryFailureReason.Unavailable) }
    }

    fun nativeItem(native: JsonObject, turn: TurnId?) {
        val id = ItemId(native.text("id") ?: protocolFailure())
        val old = items[id]
        val info = ItemInfo(id, old?.info?.position ?: items.size.toLong(), (old?.info?.revision ?: -1) + 1, turn)
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
