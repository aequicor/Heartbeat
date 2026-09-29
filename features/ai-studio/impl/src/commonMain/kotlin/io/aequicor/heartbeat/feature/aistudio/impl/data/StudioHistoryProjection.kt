package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallStatus
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioMessage
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioReplyPart
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioToolRun
import io.aequicor.heartbeat.feature.aistudio.impl.domain.ToolRunStatus
import kotlin.time.Instant

/**
 * Projects one native turn into one answer, preserving the engine's item/part order. Tool results update
 * the corresponding invocation in place. Unsupported protocol items never become invented reasoning.
 * Native ItemInfo has no timestamp: [time] is retained only for ordering compatibility, never displayed.
 * Items are projected in stored order: [StudioHistoryMirror] keeps earlier generations first, whose positions
 * overlap the engine window's.
 */
internal fun List<SessionItem>.toStudioMessages(time: Instant, isRunning: Boolean): List<StudioMessage> {
    val projection = NativeHistoryProjection(time)
    forEach(projection::append)
    return projection.finish(isRunning)
}

/** Owns one snapshot's grouping and links late results back to already emitted answers. */
private class NativeHistoryProjection(private val time: Instant) {
    private val messages = mutableListOf<StudioMessage>()
    private var answer: NativeAnswer? = null
    private val answers = mutableMapOf<String, NativeAnswer>()
    private val toolOwners = mutableMapOf<Pair<String?, String>, NativeAnswer>()

    fun append(item: SessionItem) {
        if (updateOwnedTool(item)) return
        when (item) {
            is SessionItem.UnsupportedItem -> Unit
            is SessionItem.Message -> appendMessage(item)
            is SessionItem.Notice -> appendNotice(item.info.id.value, item.text)
            is SessionItem.Plan, is SessionItem.ToolCall, is SessionItem.ToolResult -> appendAnswer(item)
        }
    }

    fun finish(isRunning: Boolean): List<StudioMessage> {
        val hasLatestAnswer = answer != null
        flush()
        val latest = messages.lastOrNull() as? StudioMessage.Reply
        if (isRunning && hasLatestAnswer && latest != null) {
            messages[messages.lastIndex] = latest.copy(isStreaming = true)
        }
        return messages.map(::refreshAnswer)
    }

    private fun appendMessage(item: SessionItem.Message) {
        when (item.role) {
            MessageRole.User -> {
                flush()
                messages += StudioMessage.Prompt(item.info.id.value, time, item.parts.text(), isTimestampKnown = false)
            }

            MessageRole.System -> appendNotice(item.info.id.value, item.parts.text())

            MessageRole.Assistant -> appendAnswer(item)
        }
    }

    private fun appendNotice(id: String, text: String) {
        flush()
        messages += StudioMessage.Reply(id, time, text, isTimestampKnown = false)
    }

    private fun appendAnswer(item: SessionItem) {
        val turn = item.info.turn?.value
        if (answer?.matchesTurn(turn) == false) flush()
        val current = answer ?: NativeAnswer(item.info.id.value, turn).also {
            answer = it
            answers[it.id] = it
        }
        current.append(item)
        if (item is SessionItem.ToolCall) toolOwners[current.turn to item.call.value] = current
    }

    private fun updateOwnedTool(item: SessionItem): Boolean {
        val result = item as? SessionItem.ToolResult ?: return false
        val owner = toolOwners.entries.lastOrNull { (key, owner) ->
            key.second == result.call.value && owner.matchesTurn(result.info.turn?.value)
        }?.value ?: return false
        owner.append(item)
        return true
    }

    private fun flush() {
        answer?.let { messages += it.message(time) }
        answer = null
    }

    private fun refreshAnswer(message: StudioMessage): StudioMessage {
        if (message !is StudioMessage.Reply) return message
        return answers[message.id]?.message(time)?.copy(isStreaming = message.isStreaming) ?: message
    }
}

private class NativeAnswer(val id: String, initialTurn: String?) {
    var turn: String? = initialTurn
        private set
    private val parts = mutableListOf<StudioReplyPart>()

    fun matchesTurn(other: String?): Boolean = other == null || turn == null || other == turn

    fun append(item: SessionItem) {
        if (turn == null) turn = item.info.turn?.value
        when (item) {
            is SessionItem.Message -> item.parts.forEachIndexed { index, part ->
                val partId = "${item.info.id.value}:$index"
                when (part) {
                    is ContentPart.Reasoning -> parts += StudioReplyPart.Reasoning(partId, part.text)

                    is ContentPart.Text, is ContentPart.Image, is ContentPart.Resource ->
                        parts += StudioReplyPart.Text(partId, listOf(part).text())
                }
            }

            is SessionItem.ToolCall -> parts += StudioReplyPart.Tool(
                StudioToolRun(item.call.value, item.name, item.status.toStudio(), item.arguments),
            )

            is SessionItem.ToolResult -> updateToolResult(item)

            is SessionItem.Plan -> parts += StudioReplyPart.Text(
                item.info.id.value,
                item.steps.joinToString("\n") { it.text },
            )

            is SessionItem.Notice, is SessionItem.UnsupportedItem -> Unit
        }
    }

    private fun updateToolResult(item: SessionItem.ToolResult) {
        val index = parts.indexOfFirst { it is StudioReplyPart.Tool && it.tool.id == item.call.value }
        val previous = (parts.getOrNull(index) as? StudioReplyPart.Tool)?.tool
        val updated = StudioToolRun(
            id = item.call.value,
            title = previous?.title ?: item.call.value,
            status = if (item.failure != null) {
                ToolRunStatus.Failed
            } else {
                previous?.status?.takeIf {
                    it == ToolRunStatus.Failed || it == ToolRunStatus.Cancelled
                } ?: ToolRunStatus.Done
            },
            output = listOfNotNull(previous?.output?.takeIf(String::isNotBlank), item.parts.text())
                .filter(String::isNotBlank).joinToString("\n"),
        )
        if (index >= 0) parts[index] = StudioReplyPart.Tool(updated) else parts += StudioReplyPart.Tool(updated)
    }

    fun message(time: Instant): StudioMessage.Reply = StudioMessage.Reply(
        id = id,
        createdAt = time,
        text = parts.filterIsInstance<StudioReplyPart.Text>().joinToString("\n\n") { it.text },
        tools = parts.filterIsInstance<StudioReplyPart.Tool>().map { it.tool },
        parts = parts.toList(),
        isTimestampKnown = false,
    )
}

private fun ToolCallStatus.toStudio(): ToolRunStatus = when (this) {
    ToolCallStatus.Pending -> ToolRunStatus.Pending
    ToolCallStatus.Running -> ToolRunStatus.Running
    ToolCallStatus.Succeeded -> ToolRunStatus.Done
    ToolCallStatus.Failed -> ToolRunStatus.Failed
    ToolCallStatus.Cancelled -> ToolRunStatus.Cancelled
}

private fun List<ContentPart>.text(): String = joinToString("\n") {
    when (it) {
        is ContentPart.Text -> it.text
        is ContentPart.Reasoning -> it.text
        is ContentPart.Image -> "[${it.resource.mediaType}]"
        is ContentPart.Resource -> "[${it.resource.mediaType}]"
    }
}
