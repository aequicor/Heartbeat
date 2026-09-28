package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.prompt.executor.clients.anthropic.AnthropicParams
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicThinking
import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import ai.koog.prompt.params.LLMParams
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider

/**
 * Effort levels Heartbeat can express for a provider model. Provider catalogs do not advertise reasoning support,
 * so it is recognised by model family; unknown families keep the provider default and show no selector.
 */
internal fun KoogProvider.reasoningEfforts(model: String): List<String> = when (this) {
    KoogProvider.OpenAI -> if (OpenAIReasoning.containsMatchIn(model)) KoogEffortLevels else emptyList()
    KoogProvider.Anthropic -> if (AnthropicReasoning.containsMatchIn(model)) KoogEffortLevels else emptyList()
    KoogProvider.AlibabaQwen, KoogProvider.Ollama -> emptyList()
}

/** Request parameters carrying [effort]; default parameters when no effort is selected. */
internal fun KoogProvider.reasoningParams(effort: String?): LLMParams {
    val level = effort ?: return LLMParams()
    return when (this) {
        KoogProvider.OpenAI -> OpenAIChatParams(reasoningEffort = openAIEffort(level))

        KoogProvider.Anthropic -> {
            val budget = anthropicBudget(level)
            // Anthropic requires max_tokens above the thinking budget; the rest is left for the answer.
            AnthropicParams(maxTokens = budget + ANSWER_TOKENS, thinking = AnthropicThinking.Enabled(budget))
        }

        KoogProvider.AlibabaQwen, KoogProvider.Ollama -> error("Effort is not advertised for $this")
    }
}

private fun openAIEffort(level: String): ReasoningEffort = when (level) {
    "low" -> ReasoningEffort.LOW
    "medium" -> ReasoningEffort.MEDIUM
    "high" -> ReasoningEffort.HIGH
    else -> error("Unknown effort level")
}

private fun anthropicBudget(level: String): Int = when (level) {
    "low" -> LOW_BUDGET
    "medium" -> MEDIUM_BUDGET
    "high" -> HIGH_BUDGET
    else -> error("Unknown effort level")
}

private val KoogEffortLevels = listOf("low", "medium", "high")
private val OpenAIReasoning = Regex("^(o\\d|gpt-5)")
private val AnthropicReasoning = Regex("^claude-(3-7-sonnet|(opus|sonnet|haiku)-4)")
private const val LOW_BUDGET = 2_048
private const val MEDIUM_BUDGET = 8_192
private const val HIGH_BUDGET = 24_576
private const val ANSWER_TOKENS = 8_192
