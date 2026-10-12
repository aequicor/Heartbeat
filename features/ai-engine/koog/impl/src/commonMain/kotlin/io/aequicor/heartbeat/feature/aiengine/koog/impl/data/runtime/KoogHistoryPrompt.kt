package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.PromptBuilder
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.params.LLMParams
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceResolver
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallStatus
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider
import io.aequicor.heartbeat.feature.aiengine.koog.impl.data.KoogContextSummary
import io.aequicor.heartbeat.feature.aiengine.koog.impl.data.KoogRecord

/** The prompt of [record]: its visible context after the last compaction, shaped by the profile's policy. */
internal suspend fun KoogAccess.historyPrompt(
    record: KoogRecord,
    provider: KoogProvider,
    support: PromptInputSupport,
    instructions: String?,
): Prompt = koogHistoryPrompt(
    record.items,
    provider,
    support,
    resources,
    instructions,
    record.context,
    contextPolicy(record.summary.ref),
)

/**
 * Builds transient provider input while keeping stable application parts in the session record. After a compaction
 * ([context]) only the items after its boundary are sent, preceded by its summary.
 */
internal suspend fun koogHistoryPrompt(
    transcript: List<SessionItem>,
    provider: KoogProvider,
    support: PromptInputSupport,
    resources: ResourceResolver,
    instructions: String?,
    context: KoogContextSummary? = null,
    policy: ContextPolicy? = null,
): Prompt {
    val items = transcript.koogContextItems(context).masked(policy)
    val userInputs = koogUserInputs(items, provider, support, resources)
    return prompt("heartbeat", LLMParams()) {
        instructions?.let { system(it) }
        context?.let { system(koogSummaryBlock(it)) }
        if (items.hasSourceMaterial()) system(KOOG_RESOURCE_BOUNDARY)
        val calls = items.filterIsInstance<SessionItem.ToolCall>().associateBy { it.call }
        items.forEach { item -> historyItem(item, userInputs, calls, provider) }
    }
}

private fun PromptBuilder.historyItem(
    item: SessionItem,
    userInputs: Map<ItemId, List<MessagePart.RequestPart>>,
    calls: Map<ToolCallId, SessionItem.ToolCall>,
    provider: KoogProvider,
) {
    when (item) {
        is SessionItem.Message -> {
            val text = item.parts.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
            when (item.role) {
                MessageRole.User -> user(userInputs.getValue(item.info.id))
                MessageRole.Assistant -> assistant(text)
                MessageRole.System -> system(text)
            }
        }

        is SessionItem.ToolCall -> if (item.status in SETTLED_CALLS) {
            toolCall(tool = item.name, args = item.arguments, id = item.call.value)
        }

        is SessionItem.ToolResult -> calls[item.call]?.let { call -> historyToolResult(item, call, provider) }

        is SessionItem.Plan, is SessionItem.Notice, is SessionItem.UnsupportedItem -> Unit
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

private val SETTLED_CALLS = setOf(ToolCallStatus.Succeeded, ToolCallStatus.Failed)
