package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider

internal interface KoogTransport {
    fun open(provider: KoogProvider, key: String?, model: String? = null): KoogClient
}

internal class KoogClient(val executor: PromptExecutor, val models: suspend () -> List<LLModel>) : AutoCloseable {
    override fun close() = executor.close()
}

/**
 * SDK model for the chosen text route. [tools] is set only when search tools are sent to a model that supports them.
 * Anthropic 1.3 requires Tools even for an empty tool list; this is an SDK precondition, not an advertised
 * adapter capability. OpenAI receives the explicit Chat Completions endpoint required by the SDK for arbitrary
 * provider model ids. [attachments] permits native image/document serialization; the provider still validates
 * whether the selected model supports the requested modality.
 */
internal fun KoogProvider.textModel(id: String, tools: Boolean = false, attachments: Boolean = false): LLModel =
    LLModel(
        provider = llmProvider,
        id = id,
        capabilities = buildList {
            add(LLMCapability.Completion)
            if (this@textModel == KoogProvider.OpenAI) add(LLMCapability.OpenAIEndpoint.Completions)
            if (tools || this@textModel == KoogProvider.Anthropic) add(LLMCapability.Tools)
            if (attachments) {
                add(LLMCapability.Vision.Image)
                if (this@textModel != KoogProvider.Ollama) add(LLMCapability.Document)
            }
        },
    )
