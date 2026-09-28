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
 * SDK model for the chosen text route. Anthropic 1.3 requires Tools even for an empty tool list;
 * this is an SDK precondition, not an advertised adapter capability.
 */
internal fun KoogProvider.textModel(id: String): LLModel = LLModel(
    provider = llmProvider,
    id = id,
    capabilities = buildList {
        add(LLMCapability.Completion)
        if (this@textModel == KoogProvider.Anthropic) add(LLMCapability.Tools)
    },
)
