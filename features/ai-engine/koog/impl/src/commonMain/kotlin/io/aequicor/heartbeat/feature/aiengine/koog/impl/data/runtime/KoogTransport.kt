package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider

internal interface KoogTransport {
    /** [origin] and [basePath] differ from the defaults only for editable routes; the caller validated the scope. */
    fun open(
        provider: KoogProvider,
        key: String?,
        model: String? = null,
        origin: EndpointOrigin = provider.origin,
        basePath: String? = null,
    ): KoogClient
}

/**
 * [reasoning] asks the provider API which of the given models accept reasoning parameters; null when the API has
 * no such information or the request failed.
 */
internal class KoogClient(
    val executor: PromptExecutor,
    val reasoning: suspend (models: List<String>) -> Map<String, List<String>>? = { null },
    val usage: KoogUsageCapture = KoogUsageCapture(),
    val models: suspend () -> List<LLModel>,
) : AutoCloseable {
    override fun close() = executor.close()
}

/**
 * SDK model for the chosen text route. [tools] is set only when search tools are sent to a model that supports them.
 * Anthropic 1.3 requires Tools even for an empty tool list; this is an SDK precondition, not an advertised
 * adapter capability. OpenAI-compatible providers receive the explicit Chat Completions endpoint for arbitrary
 * provider model ids. [attachments] permits native image/document serialization; the provider still validates
 * whether the selected model supports the requested modality.
 */
internal fun KoogProvider.textModel(id: String, tools: Boolean = false, attachments: Boolean = false): LLModel =
    LLModel(
        provider = llmProvider,
        id = id,
        capabilities = buildList {
            add(LLMCapability.Completion)
            if (llmProvider == LLMProvider.OpenAI) {
                add(LLMCapability.OpenAIEndpoint.Completions)
            }
            if (tools || llmProvider == LLMProvider.Anthropic) add(LLMCapability.Tools)
            if (attachments) {
                add(LLMCapability.Vision.Image)
                if (this@textModel != KoogProvider.Ollama) add(LLMCapability.Document)
            }
        },
    )
