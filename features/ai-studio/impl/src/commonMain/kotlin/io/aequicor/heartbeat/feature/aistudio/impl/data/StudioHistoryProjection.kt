package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallStatus
import io.aequicor.heartbeat.feature.aiengine.facade.api.hostedToolName
import io.aequicor.heartbeat.feature.aiengine.facade.api.stripHostDirectives
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioMessage
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioReplyPart
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioToolRun
import io.aequicor.heartbeat.feature.aistudio.impl.domain.ToolRunStatus
import io.aequicor.heartbeat.feature.feedback.api.FeedbackOutcome
import io.aequicor.heartbeat.feature.feedback.api.FeedbackRecord
import kotlin.time.Instant

/**
 * Projects one native turn into one answer, preserving the engine's item/part order. Tool results update
 * the corresponding invocation in place. Unsupported protocol items never become invented reasoning.
 * Native ItemInfo has no timestamp: [time] is retained only for ordering compatibility, never displayed.
 * Items are projected in stored order: [StudioHistoryMirror] keeps earlier generations first, whose positions
 * overlap the engine window's.
 */
internal fun List<SessionItem>.toStudioMessages(
    time: Instant,
    isRunning: Boolean,
    feedback: List<FeedbackRecord> = emptyList(),
): List<StudioMessage> {
    val projection = NativeHistoryProjection(time)
    val anchored = feedback.groupBy { entry ->
        if (entry.anchor.after == null) {
            -1
        } else {
            val index = indexOfFirst { it.info.id == entry.anchor.after }
            if (index >= 0) index else lastIndex
        }
    }
    anchored[-1].orEmpty().forEach(projection::appendFeedback)
    forEachIndexed { index, item ->
        projection.append(item)
        anchored[index].orEmpty().forEach(projection::appendFeedback)
    }
    return projection.finish(isRunning)
}

/** Owns one snapshot's grouping and links late results back to already emitted answers. */
private class NativeHistoryProjection(private val time: Instant) {
    private val messages = mutableListOf<StudioMessage>()
    private var answer: NativeAnswer? = null
    private var streamingAnswer: String? = null
    private val answers = mutableMapOf<String, NativeAnswer>()
    private val toolOwners = mutableMapOf<Pair<String?, String>, NativeAnswer>()

    fun appendFeedback(record: FeedbackRecord) {
        flush()
        val status = when (record.outcome) {
            FeedbackOutcome.Pending -> ToolRunStatus.Running
            is FeedbackOutcome.Applied -> ToolRunStatus.Done
            is FeedbackOutcome.Failed -> ToolRunStatus.Failed
            FeedbackOutcome.Unknown -> ToolRunStatus.Cancelled
        }
        val tool = StudioToolRun("feedback:${record.id}", "feedback", status, feedback = record)
        messages += StudioMessage.Reply(
            id = "feedback:${record.id}",
            createdAt = record.createdAt,
            tools = listOf(tool),
            parts = listOf(StudioReplyPart.Tool(tool)),
        )
    }

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
        flush()
        val index = messages.indexOfLast { it.id == streamingAnswer }
        val latest = messages.getOrNull(index) as? StudioMessage.Reply
        if (isRunning && latest != null) {
            messages[index] = latest.copy(isStreaming = true)
        }
        return messages.map(::refreshAnswer)
    }

    private fun appendMessage(item: SessionItem.Message) {
        when (item.role) {
            MessageRole.User -> {
                flush()
                streamingAnswer = null
                // Host directives (a /remember request, learning hints) go to the engine, not the transcript; each
                // text part is stripped on its own.
                val text = item.parts.mapNotNull {
                    when (it) {
                        is ContentPart.Text -> stripHostDirectives(it.text)
                        is ContentPart.Reasoning -> it.text
                        is ContentPart.Image, is ContentPart.Resource -> null
                    }
                }.joinToString("\n")
                val attachments = item.parts.mapNotNull {
                    when (it) {
                        is ContentPart.Image -> it.resource
                        is ContentPart.Resource -> it.resource
                        is ContentPart.Text, is ContentPart.Reasoning -> null
                    }
                }
                // A message that carried only host directives is not the user's prompt.
                if (text.isNotBlank() || attachments.isNotEmpty()) {
                    messages += StudioMessage.Prompt(
                        item.info.id.value,
                        time,
                        text,
                        isTimestampKnown = false,
                        attachments = attachments,
                    )
                }
            }

            MessageRole.System -> appendNotice(item.info.id.value, item.parts.text())

            MessageRole.Assistant -> appendAnswer(item)
        }
    }

    private fun appendNotice(id: String, text: String) {
        flush()
        streamingAnswer = null
        messages += StudioMessage.Reply(id, time, text, isTimestampKnown = false)
    }

    private fun appendAnswer(item: SessionItem) {
        val turn = item.info.turn?.value
        if (answer?.matchesTurn(turn) == false) flush()
        val current = answer ?: NativeAnswer(item.info.id.value, turn).also {
            answer = it
            answers[it.id] = it
        }
        streamingAnswer = current.id
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

            is SessionItem.ToolCall -> parts += StudioReplyPart.Tool(toolRun(item))

            is SessionItem.ToolResult -> updateToolResult(item)

            is SessionItem.Plan -> parts += StudioReplyPart.Text(
                item.info.id.value,
                item.steps.joinToString("\n") { it.text },
            )

            is SessionItem.Notice, is SessionItem.UnsupportedItem -> Unit
        }
    }

    /** A learning call shows its arguments as a card, so only the tool result goes to the console. */
    private fun toolRun(item: SessionItem.ToolCall): StudioToolRun {
        val learning = learningCall(item.name, item.arguments)
        val output = if (learning == null) item.arguments else ""
        return StudioToolRun(item.call.value, item.name, item.status.toStudio(), output, learning = learning)
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
            learning = previous?.learning,
            createdChecklistId = item.createdChecklistId(previous) ?: previous?.createdChecklistId,
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
        historyTurn = turn,
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

/** The host result survives native history reloads even when the adapter loses turn and bridge-call identities. */
private fun SessionItem.ToolResult.createdChecklistId(invocation: StudioToolRun?): String? {
    if (failure != null || invocation == null || hostedToolName(invocation.title) != "checklist_create") return null
    val result = (parts.singleOrNull() as? ContentPart.Text)?.text ?: return null
    return CHECKLIST_CREATED.matchEntire(result)?.groupValues?.get(1)
}

private val CHECKLIST_CREATED = Regex("Checklist ([a-z0-9_-]{1,32}) created\\. End your turn and wait for the user\\.")
