package io.aequicor.heartbeat.feature.researchchat.impl.presentation.store

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallStatus
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/**
 * Groups exposed native content by answer turn. The stored question already concatenates native segments:
 * positions restart in each segment, so sorting the entire question would reorder earlier conversations.
 */
internal fun List<SessionItem>.toResearchMessages(isRunning: Boolean): ImmutableList<ResearchMessageUi> {
    val projection = ResearchMessageProjection()
    forEach(projection::append)
    return projection.finish(isRunning)
}

private class ResearchMessageProjection {
    private val messages = mutableListOf<ResearchMessageUi>()
    private val answers = mutableMapOf<String, ResearchAnswer>()
    private val owners = mutableMapOf<Pair<String?, String>, ResearchAnswer>()
    private var answer: ResearchAnswer? = null

    fun append(item: SessionItem) {
        if (updateOwnedTool(item)) return
        when (item) {
            is SessionItem.Message -> appendMessage(item)
            is SessionItem.Notice -> appendNotice(item.info.id.value, item.text)
            is SessionItem.ToolCall, is SessionItem.ToolResult, is SessionItem.Plan -> appendAnswer(item)
            is SessionItem.UnsupportedItem -> Unit
        }
    }

    fun finish(isRunning: Boolean): ImmutableList<ResearchMessageUi> {
        val streamingId = answer?.id.takeIf { isRunning }
        flush()
        return messages.map { message ->
            answers[message.id]?.message()?.copy(isStreaming = message.id == streamingId) ?: message
        }.toImmutableList()
    }

    private fun appendMessage(item: SessionItem.Message) {
        when (item.role) {
            MessageRole.User -> {
                flush()
                val text = item.parts.plainText()
                if (text.isNotBlank()) messages += ResearchMessageUi(item.info.id.value, true, text)
            }

            MessageRole.System -> appendNotice(item.info.id.value, item.parts.plainText())

            MessageRole.Assistant -> appendAnswer(item)
        }
    }

    private fun appendNotice(id: String, text: String) {
        flush()
        if (text.isNotBlank()) messages += ResearchMessageUi(id, false, text, isNotice = true)
    }

    private fun appendAnswer(item: SessionItem) {
        val turn = item.info.turn?.value
        if (answer?.matchesTurn(turn) == false) flush()
        val current = answer ?: ResearchAnswer(item.info.id.value, turn).also {
            answer = it
            answers[it.id] = it
        }
        current.append(item)
        if (item is SessionItem.ToolCall) owners[current.turn to item.call.value] = current
    }

    private fun updateOwnedTool(item: SessionItem): Boolean {
        val result = item as? SessionItem.ToolResult ?: return false
        val owner = owners.entries.lastOrNull { (key, owner) ->
            key.second == result.call.value && owner.matchesTurn(result.info.turn?.value)
        }?.value ?: return false
        owner.append(result)
        return true
    }

    private fun flush() {
        answer?.let { current ->
            val message = current.message()
            if (message.parts.isNotEmpty()) messages += message
        }
        answer = null
    }
}

private class ResearchAnswer(val id: String, initialTurn: String?) {
    var turn: String? = initialTurn
        private set
    private val parts = mutableListOf<ResearchPartUi>()

    fun matchesTurn(other: String?): Boolean = other == null || turn == null || other == turn

    fun append(item: SessionItem) {
        if (turn == null) turn = item.info.turn?.value
        when (item) {
            is SessionItem.Message -> item.parts.forEachIndexed { index, part ->
                appendPart("text:${item.info.id.value}:$index", part)
            }

            is SessionItem.ToolCall -> parts += ResearchPartUi.Tool(
                item.call.value,
                item.name,
                item.status.toResearch(),
                item.arguments,
            )

            is SessionItem.ToolResult -> appendResult(item)

            is SessionItem.Plan -> parts += ResearchPartUi.Text(
                "plan:${item.info.id.value}",
                item.steps.joinToString("\n") { it.text },
            )

            is SessionItem.Notice, is SessionItem.UnsupportedItem -> Unit
        }
    }

    private fun appendPart(id: String, part: ContentPart) {
        when (part) {
            is ContentPart.Text -> if (part.text.isNotBlank()) parts += ResearchPartUi.Text(id, part.text)
            is ContentPart.Reasoning -> if (part.text.isNotBlank()) parts += ResearchPartUi.Reasoning(id, part.text)
            is ContentPart.Image, is ContentPart.Resource -> Unit
        }
    }

    private fun appendResult(item: SessionItem.ToolResult) {
        val index = parts.indexOfFirst { it is ResearchPartUi.Tool && it.id == item.call.value }
        val previous = parts.getOrNull(index) as? ResearchPartUi.Tool
        val updated = ResearchPartUi.Tool(
            item.call.value,
            previous?.title ?: item.call.value,
            resultStatus(item, previous),
            listOfNotNull(previous?.output, item.parts.plainText()).filter(String::isNotBlank).joinToString("\n"),
        )
        if (index >= 0) parts[index] = updated else parts += updated
    }

    fun message(): ResearchMessageUi = ResearchMessageUi(
        id = id,
        isUser = false,
        text = parts.filterIsInstance<ResearchPartUi.Text>().joinToString("\n\n") { it.text },
        parts = parts.toImmutableList(),
    )
}

private fun resultStatus(item: SessionItem.ToolResult, previous: ResearchPartUi.Tool?): ResearchToolStatus =
    if (item.failure != null) {
        ResearchToolStatus.Failed
    } else {
        previous?.status?.takeIf {
            it == ResearchToolStatus.Failed || it == ResearchToolStatus.Cancelled
        } ?: ResearchToolStatus.Complete
    }

private fun ToolCallStatus.toResearch(): ResearchToolStatus = when (this) {
    ToolCallStatus.Pending -> ResearchToolStatus.Pending
    ToolCallStatus.Running -> ResearchToolStatus.Running
    ToolCallStatus.Succeeded -> ResearchToolStatus.Complete
    ToolCallStatus.Failed -> ResearchToolStatus.Failed
    ToolCallStatus.Cancelled -> ResearchToolStatus.Cancelled
}

private fun List<ContentPart>.plainText(): String = filterIsInstance<ContentPart.Text>().joinToString("\n") { it.text }
