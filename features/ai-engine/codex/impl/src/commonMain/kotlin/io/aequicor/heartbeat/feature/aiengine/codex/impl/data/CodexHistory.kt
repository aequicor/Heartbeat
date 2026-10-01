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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid

/**
 * Bounded journal; slow consumers receive invalidation rather than a silently truncated stream. Main confined.
 * Items are never dropped or reseeded. Only a canonical full replay can seed a resumed thread; later reads
 * may lower coverage when unseen native content is discovered without replacing the richer live journal.
 */
internal class CodexHistory : SessionHistory {
    private val log = Log.tag("CodexHistory")

    /** Complete for a new thread or a canonical full replay, until a read discovers missing native content. */
    var coverage: HistoryCoverage = HistoryCoverage.Partial
        private set

    private val generation = Uuid.random().toString()
    private var sequence = 0L
    private val items = linkedMapOf<ItemId, SessionItem>()
    private val nativeSnapshots = mutableMapOf<ItemId, JsonObject>()
    private val reasoning = CodexReasoning()
    private val originalInputs = mutableMapOf<TurnId, List<ContentPart>>()

    fun rememberOriginals(turn: TurnId, parts: List<ContentPart>) {
        if (parts.any { it is ContentPart.Image || it is ContentPart.Resource }) originalInputs[turn] = parts.toList()
    }

    fun originals(turn: TurnId): List<ContentPart>? = originalInputs[turn]

    /** [floor] is the last sequence lost to truncation; checkpoints below it cannot be replayed. */
    private data class Journal(val floor: Long, val events: List<SessionEvent>)
    private val journal = MutableStateFlow(Journal(0, emptyList()))
    private var isInvalid = false
    private val cursors = linkedMapOf<String, Int>()
    private fun checkpoint() = HistoryCheckpoint("$generation:$sequence")

    /** Records whether the seeded items hold the whole native thread. */
    fun seeded(isComplete: Boolean) {
        coverage = if (isComplete) HistoryCoverage.Complete else HistoryCoverage.Partial
    }

    /** Conservative snapshot comparison: even changed metadata makes completeness uncertain, without reseeding. */
    fun matches(native: JsonObject): Boolean = nativeSnapshots[ItemId(native.text("id") ?: protocolFailure())] == native

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
            coverage,
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

    /** [isStarted] marks an `item/started` snapshot; items without their own status are running until completed. */
    fun nativeItem(native: JsonObject, turn: TurnId?, isStarted: Boolean = false) {
        val id = ItemId(native.text("id") ?: protocolFailure())
        val old = items[id]
        val info = ItemInfo(
            id,
            old?.info?.position ?: items.size.toLong(),
            (old?.info?.revision ?: -1) + 1,
            turn ?: old?.info?.turn,
        )
        val kind = native.text("type")
        val item = decodeNativeItem(native, kind, info, isStarted)
        nativeSnapshots[id] = native
        items[id] = item
        publish { SessionEvent.ItemUpserted(it, item) }
        if (kind == "dynamicToolCall" && native.text("status") in setOf("completed", "failed")) {
            recordDynamicResult(native, info)
        }
    }

    private fun decodeNativeItem(native: JsonObject, kind: String?, info: ItemInfo, isStarted: Boolean): SessionItem =
        when (kind) {
            "agentMessage" -> SessionItem.Message(
                info,
                MessageRole.Assistant,
                listOf(ContentPart.Text(native.text("text").orEmpty())),
            )

            "userMessage" -> SessionItem.Message(
                info,
                MessageRole.User,
                originalInputs[info.turn] ?: native.array("content").map { part ->
                    val value = part as? JsonObject ?: protocolFailure()
                    ContentPart.Text(value.text("text") ?: "[Unsupported input]")
                },
            )

            "reasoning" -> reasoningMessage(info, reasoning.snapshot(info.id, native))

            "commandExecution", "dynamicToolCall" -> SessionItem.ToolCall(
                info,
                ToolCallId(info.id.value),
                if (kind == "commandExecution") "command" else native.text("tool").orEmpty(),
                if (kind == "commandExecution") {
                    native.text("command").orEmpty()
                } else {
                    native["arguments"]?.toString().orEmpty()
                },
                toolStatus(native.text("status")),
            )

            // Hosted search: the query is the only observable part; results reach the model, not the protocol.
            "webSearch" -> SessionItem.ToolCall(
                info,
                ToolCallId(info.id.value),
                NATIVE_WEB_SEARCH,
                buildJsonObject { put("query", native.text("query").orEmpty()) }.toString(),
                if (isStarted) ToolCallStatus.Running else ToolCallStatus.Succeeded,
            )

            else -> SessionItem.UnsupportedItem(info, kind?.take(MAX_KIND_LENGTH) ?: "unknown")
        }

    private fun recordDynamicResult(native: JsonObject, info: ItemInfo) {
        val resultId = ItemId("${info.id.value}:result")
        val prior = items[resultId]
        val content = (native["contentItems"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.text("text") }
            .joinToString("\n")
        val result = SessionItem.ToolResult(
            ItemInfo(
                resultId,
                prior?.info?.position ?: items.size.toLong(),
                (prior?.info?.revision ?: -1) + 1,
                info.turn,
            ),
            ToolCallId(info.id.value),
            listOf(ContentPart.Text(content)),
            if (native.text("status") == "failed") EngineFailure.Unknown() else null,
        )
        items[resultId] = result
        publish { SessionEvent.ItemUpserted(it, result) }
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

    fun reasoningDelta(native: JsonObject, turn: TurnId?) {
        val id = ItemId(native.text("itemId") ?: protocolFailure())
        // Deltas do not carry the full native snapshot; completion will restore an auditable snapshot.
        nativeSnapshots.remove(id)
        val old = items[id]
        val info = ItemInfo(
            id,
            old?.info?.position ?: items.size.toLong(),
            (old?.info?.revision ?: -1) + 1,
            turn ?: old?.info?.turn,
        )
        val item = reasoningMessage(info, reasoning.append(id, native))
        items[id] = item
        publish { SessionEvent.ItemUpserted(it, item) }
    }

    private fun reasoningMessage(info: ItemInfo, parts: List<ContentPart.Reasoning>): SessionItem =
        if (parts.isEmpty()) {
            SessionItem.UnsupportedItem(info, "reasoning")
        } else {
            SessionItem.Message(info, MessageRole.Assistant, parts)
        }

    private fun toolStatus(status: String?): ToolCallStatus = when (status) {
        "completed" -> ToolCallStatus.Succeeded
        "failed" -> ToolCallStatus.Failed
        "declined" -> ToolCallStatus.Cancelled
        else -> ToolCallStatus.Running
    }

    private companion object {
        /** Distinct from the dynamic `web_search`, whose structured results the research chat imports. */
        const val NATIVE_WEB_SEARCH = "codex_web_search"
        const val JOURNAL_LIMIT = 512
        const val MAX_KIND_LENGTH = 80
    }
}
