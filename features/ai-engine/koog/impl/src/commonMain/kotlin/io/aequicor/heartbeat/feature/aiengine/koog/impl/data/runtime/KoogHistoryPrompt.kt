package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.PromptBuilder
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.params.LLMParams
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceResolver
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallStatus
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider

/** Builds transient provider input while keeping stable application parts in the session record. */
internal suspend fun koogHistoryPrompt(
    items: List<SessionItem>,
    provider: KoogProvider,
    support: PromptInputSupport,
    resources: ResourceResolver,
    instructions: String?,
): Prompt {
    val userInputs = koogUserInputs(items, provider, support, resources)
    return prompt("heartbeat", LLMParams()) {
        instructions?.let { system(it) }
        if (items.hasSourceMaterial()) system(KOOG_RESOURCE_BOUNDARY)
        val calls = items.filterIsInstance<SessionItem.ToolCall>().associateBy { it.call }
        items.forEach { item ->
            when (item) {
                is SessionItem.Message -> {
                    val text = item.parts.filterIsInstance<ContentPart.Text>()
                        .joinToString("") { it.text }
                    when (item.role) {
                        MessageRole.User -> user(userInputs.getValue(item.info.id))
                        MessageRole.Assistant -> assistant(text)
                        MessageRole.System -> system(text)
                    }
                }

                is SessionItem.ToolCall -> if (item.status == ToolCallStatus.Succeeded ||
                    item.status == ToolCallStatus.Failed
                ) {
                    toolCall(tool = item.name, args = item.arguments, id = item.call.value)
                }

                is SessionItem.ToolResult -> calls[item.call]?.let { call ->
                    historyToolResult(item, call, provider)
                }

                is SessionItem.Plan, is SessionItem.Notice, is SessionItem.UnsupportedItem -> Unit
            }
        }
    }
}

private suspend fun koogUserInputs(
    items: List<SessionItem>,
    provider: KoogProvider,
    support: PromptInputSupport,
    resources: ResourceResolver,
): Map<ItemId, List<MessagePart.RequestPart>> {
    val inputs = mutableMapOf<ItemId, List<MessagePart.RequestPart>>()
    items.filterIsInstance<SessionItem.Message>().filter { it.role == MessageRole.User }.forEach { item ->
        inputs[item.info.id] = resolveKoogInputs(item.parts, support, resources).koogUserParts(provider)
    }
    return inputs
}

private fun PromptBuilder.historyToolResult(
    item: SessionItem.ToolResult,
    call: SessionItem.ToolCall,
    provider: KoogProvider,
) {
    val text = item.parts.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
    toolResult(tool = call.name, output = text, id = item.call.value, isError = item.failure != null)
    val images = item.parts.filterIsInstance<ContentPart.Image>()
    if (images.isNotEmpty()) {
        user(listOf(MessagePart.Text(toolImageSource(item.call.value))) + images.koogUserParts(provider))
    }
}
