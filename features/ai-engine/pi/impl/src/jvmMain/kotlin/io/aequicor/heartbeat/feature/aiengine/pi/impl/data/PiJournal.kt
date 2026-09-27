package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

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
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/** Atomic live transcript with bounded replay. Native files remain owned by Pi. */
internal class PiJournal : SessionHistory {
    private val log = Log.tag("PiJournal")
    private val lock = Any()
    private val generation = UUID.randomUUID().toString()
    private val version = MutableStateFlow(0L)
    private val items = mutableListOf<SessionItem>()
    private val events = ArrayDeque<SessionEvent>()
    private var currentMessage: Int? = null
    private val blocks = sortedMapOf<Int, ContentPart>()

    fun record(record: JsonObject, turn: TurnId?) = synchronized(lock) {
        when (record.string("type")) {
            "message_start" -> startMessage(record, turn)
            "message_update" -> updateMessage(record)
            "message_end" -> endMessage(record, turn)
            "tool_execution_start", "tool_execution_end" -> updateTool(record)
        }
    }

    fun started(turn: Turn) = synchronized(lock) {
        append { SessionEvent.TurnStarted(it, turn) }
    }

    fun finished(turn: TurnId, outcome: TurnOutcome) = synchronized(lock) {
        append { SessionEvent.TurnFinished(it, turn, outcome) }
    }

    override suspend fun page(request: HistoryPageRequest): HistoryPage = synchronized(lock) {
        val (forward, offset) = cursor(request.cursor)
        val end = if (forward) (offset + request.limit).coerceAtMost(items.size) else offset
        val start = if (forward) offset else (end - request.limit).coerceAtLeast(0)
        HistoryPage(
            items.subList(start, end).toList(),
            if (start > 0) HistoryCursor("$generation:b:$start") else null,
            if (end < items.size) HistoryCursor("$generation:f:$end") else null,
            HistoryCheckpoint(token(version.value)),
            HistoryCoverage.Partial,
        )
    }

    override fun watch(after: HistoryCheckpoint): Flow<SessionEvent> = flow {
        var cursor = parse(after.value)
        while (true) {
            val batch = replay(cursor)
            for (event in batch) {
                emit(event)
                if (event is SessionEvent.HistoryInvalidated) return@flow
                cursor = parse(event.checkpoint.value)
            }
            val observed = cursor ?: return@flow
            version.first { it > observed }
        }
    }

    private fun replay(cursor: Long?): List<SessionEvent> = synchronized(lock) {
        val current = version.value
        if (cursor == null || cursor !in (current - events.size)..current) {
            listOf(
                SessionEvent.HistoryInvalidated(
                    HistoryCheckpoint(token(current)),
                    HistoryFailureReason.CursorExpired,
                ),
            )
        } else {
            events.drop((cursor - (current - events.size)).toInt())
        }
    }

    private fun cursor(cursor: HistoryCursor?): Pair<Boolean, Int> {
        if (cursor == null) return false to items.size
        val fields = cursor.value.split(':')
        if (fields.size != CURSOR_FIELDS || fields.first() != generation) expiredCursor()
        val direction = fields[1]
        val offset = fields[2].toIntOrNull()
        if (direction !in setOf("b", "f") || offset == null || offset !in 0..items.size) expiredCursor()
        return (direction == "f") to offset
    }

    private fun expiredCursor(): Nothing = piFailure(EngineFailure.History(HistoryFailureReason.CursorExpired))

    private fun startMessage(record: JsonObject, turn: TurnId?) {
        val message = record["message"] as? JsonObject ?: return
        blocks.clear()
        currentMessage = items.size
        publish(PiMessages.message(message, info(items.size, turn)))
    }

    private fun updateMessage(record: JsonObject) {
        val index = currentMessage ?: return
        val update = record["assistantMessageEvent"] as? JsonObject ?: return
        val block = update.string("contentIndex")?.toIntOrNull() ?: return
        val delta = update.string("delta") ?: return
        when (update.string("type")) {
            "text_delta" ->
                blocks[block] = ContentPart.Text((blocks[block] as? ContentPart.Text)?.text.orEmpty() + delta)

            "thinking_delta" ->
                blocks[block] = ContentPart.Reasoning((blocks[block] as? ContentPart.Reasoning)?.text.orEmpty() + delta)

            else -> return
        }
        val previous = items[index]
        publish(
            SessionItem.Message(
                previous.info.copy(revision = previous.info.revision + 1),
                MessageRole.Assistant,
                blocks.values.toList(),
            ),
        )
    }

    private fun endMessage(record: JsonObject, turn: TurnId?) {
        val index = currentMessage ?: items.size
        val message = record["message"] as? JsonObject ?: return
        val metadata = items.getOrNull(index)?.info?.let { it.copy(revision = it.revision + 1) } ?: info(index, turn)
        publish(PiMessages.message(message, metadata))
        PiMessages.tools(message).forEach { tool ->
            publish(
                SessionItem.ToolCall(
                    info(items.size, turn),
                    ToolCallId(requireNotNull(tool.string("id"))),
                    tool.string("name").orEmpty(),
                    tool["arguments"]?.toString().orEmpty(),
                    ToolCallStatus.Pending,
                ),
            )
        }
        currentMessage = null
        blocks.clear()
    }

    private fun updateTool(record: JsonObject) {
        val call = record.string("toolCallId") ?: return
        val previous = items.filterIsInstance<SessionItem.ToolCall>().firstOrNull { it.call.value == call } ?: return
        val status = when {
            record.string("type") == "tool_execution_start" -> ToolCallStatus.Running
            record.string("isError") == "true" -> ToolCallStatus.Failed
            else -> ToolCallStatus.Succeeded
        }
        publish(previous.copy(info = previous.info.copy(revision = previous.info.revision + 1), status = status))
    }

    private fun publish(item: SessionItem) {
        val index = item.info.position.toInt()
        if (index == items.size) items += item else items[index] = item
        append { SessionEvent.ItemUpserted(it, item) }
    }

    private fun append(event: (HistoryCheckpoint) -> SessionEvent) {
        val next = version.value + 1
        events += event(HistoryCheckpoint(token(next)))
        if (events.size > REPLAY_LIMIT) events.removeFirst()
        log.d { "Pi history revision: $next" }
        version.value = next
    }

    private fun info(position: Int, turn: TurnId?): ItemInfo =
        ItemInfo(ItemId("$generation-$position"), position.toLong(), 0, turn)

    private fun token(position: Long): String = "$generation:$position"
    private fun parse(token: String): Long? =
        token.takeIf { it.startsWith("$generation:") }?.substringAfter(':')?.toLongOrNull()?.takeIf { it >= 0 }

    private companion object {
        const val REPLAY_LIMIT = 512
        const val CURSOR_FIELDS = 3
    }
}

internal object PiMessages {
    fun message(message: JsonObject, info: ItemInfo): SessionItem {
        val content = message["content"]
        val parts = when (content) {
            is JsonPrimitive -> listOf(ContentPart.Text(content.content))

            is JsonArray -> content.mapNotNull { part ->
                val block = part as? JsonObject ?: return@mapNotNull null
                when (block.string("type")) {
                    "text" -> ContentPart.Text(block.string("text").orEmpty())
                    "thinking" -> ContentPart.Reasoning(block.string("thinking").orEmpty())
                    else -> null
                }
            }

            is JsonObject, null -> emptyList()
        }
        return when (message.string("role")) {
            "user" -> SessionItem.Message(info, MessageRole.User, parts)

            "assistant" -> SessionItem.Message(info, MessageRole.Assistant, parts)

            "toolResult" -> SessionItem.ToolResult(
                info,
                ToolCallId(message.string("toolCallId") ?: "unknown"),
                parts,
                if (message.string("isError") == "true") EngineFailure.Unknown() else null,
            )

            else -> SessionItem.UnsupportedItem(info, "pi.message")
        }
    }

    fun tools(message: JsonObject): List<JsonObject> =
        (message["content"] as? JsonArray).orEmpty().asSequence().mapNotNull { it as? JsonObject }
            .filter { it.string("type") == "toolCall" && it.string("id") != null }.toList()
}
