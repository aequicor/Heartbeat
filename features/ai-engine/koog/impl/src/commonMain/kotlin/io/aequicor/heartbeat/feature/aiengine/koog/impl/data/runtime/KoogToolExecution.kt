package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallStatus
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.Uuid

/**
 * Runs the tool calls of one turn and records them in the transcript. The approval policy of mutating tools stays
 * with the session and arrives as [approve]; main dispatcher only, like everything else of a session turn.
 */
internal class KoogToolExecution(
    private val history: KoogHistory,
    private val approve: suspend (Turn, KoogTool, JsonObject) -> String?,
) {
    private val log = Log.tag("KoogSession")

    /** Parses [call] arguments; null when the provider sent unparsable or non-object arguments. */
    fun arguments(call: StreamFrame.ToolCallComplete): JsonObject? = try {
        call.contentJson
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "Malformed tool arguments for ${call.name}" }
        null
    }

    /**
     * Prompt continuing the turn after [round]; null when the model produced no executable call and finished.
     * Nameless calls and unparsable arguments are provider noise: they never run, never reach the transcript and
     * are not echoed back, since a broken echo makes the provider refuse the next request. A round of nothing but
     * unusable calls and no text is a protocol violation instead of a silent empty answer.
     */
    suspend fun nextPrompt(turn: Turn, tools: KoogToolbox, round: ToolRound, input: Prompt): Prompt? {
        val executable = round.calls.filter { it.name.isNotBlank() && arguments(it) != null }
        if (executable.size != round.calls.size) {
            log.w { "Dropped ${round.calls.size - executable.size} unusable tool calls" }
        }
        if (executable.isEmpty()) {
            if (round.calls.isNotEmpty() && round.text.isBlank()) {
                fail(EngineFailure.Transport(TransportFailureReason.ProtocolViolation))
            }
            return null
        }
        val results = executable.map { call -> execute(turn, tools, call) }
        return continuePrompt(input, round.text, results)
    }

    private suspend fun execute(turn: Turn, tools: KoogToolbox, call: StreamFrame.ToolCallComplete): HandledToolCall {
        val info = ItemInfo(ItemId(Uuid.random().toString()), history.items.size.toLong(), 0, turn.id)
        val id = ToolCallId(call.id?.takeIf { it.isNotBlank() } ?: Uuid.random().toString())
        val tool = tools[call.name]
        val args = arguments(call)
        val isApprovalNeeded = tool != null && args != null && tool.isMutating
        val started = SessionItem.ToolCall(
            info,
            id,
            call.name,
            call.content,
            if (isApprovalNeeded) ToolCallStatus.Pending else ToolCallStatus.Running,
        )
        history.append { SessionEvent.ItemUpserted(it, started) }
        val result = when {
            tool == null -> {
                log.w { "Model called an unknown tool ${call.name}" }
                KoogToolResult("Unknown tool: ${call.name}", true)
            }

            args == null -> KoogToolResult("InvalidInput: arguments must be a JSON object", true)

            isApprovalNeeded -> approve(turn, tool, args)?.let { KoogToolResult(it, true) } ?: run(tool, args, started)

            else -> {
                log.i { "Running tool ${call.name}" }
                tool.run(args)
            }
        }
        val status = if (result.isFailed) ToolCallStatus.Failed else ToolCallStatus.Succeeded
        history.append { SessionEvent.ItemUpserted(it, started.copy(info = info.copy(revision = 2), status = status)) }
        val output = SessionItem.ToolResult(
            ItemInfo(ItemId(Uuid.random().toString()), history.items.size.toLong(), 0, turn.id),
            id,
            listOf(ContentPart.Text(result.text)) + result.resources.map { ContentPart.Resource(it) } +
                result.images.map { ContentPart.Image(ResourceRef(it.dataUrl, it.mimeType)) },
            if (result.isFailed) EngineFailure.Unknown() else null,
        )
        history.append { SessionEvent.ItemUpserted(it, output) }
        return HandledToolCall(id.value, call.name, call.content, result)
    }

    private suspend fun run(tool: KoogTool, args: JsonObject, pending: SessionItem.ToolCall): KoogToolResult {
        val running = pending.copy(info = pending.info.copy(revision = 1), status = ToolCallStatus.Running)
        history.append { SessionEvent.ItemUpserted(it, running) }
        log.i { "Running approved tool ${tool.descriptor.name}" }
        return tool.run(args)
    }

    private fun continuePrompt(input: Prompt, text: String, calls: List<HandledToolCall>): Prompt = prompt(
        "heartbeat",
        input.params,
    ) {
        if (input.messages.none { it is Message.System && it.textContent() == KOOG_RESOURCE_BOUNDARY }) {
            system(KOOG_RESOURCE_BOUNDARY)
        }
        messages(input.messages)
        if (text.isNotBlank()) assistant(text)
        calls.forEach { toolCall(tool = it.name, args = it.arguments, id = it.id) }
        calls.forEach { toolResult(tool = it.name, output = it.result.text, id = it.id, isError = it.result.isFailed) }
        // OpenAI and Ollama SDK serializers discard image parts inside tool results.
        // Send them after all results in this same request, explicitly attributed to their tool call.
        calls.filter { it.result.images.isNotEmpty() }.forEach { call ->
            user(listOf(MessagePart.Text(toolImageSource(call.id))) + call.result.images.map { it.koogPart() })
        }
    }
}

internal data class HandledToolCall(val id: String, val name: String, val arguments: String, val result: KoogToolResult)
