package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.prompt.executor.clients.anthropic.AnthropicParams
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicThinking
import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import ai.koog.prompt.executor.ollama.client.OllamaParams
import ai.koog.prompt.params.LLMParams
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider

/** `reasoning_effort` values Koog 1.3 can send to OpenAI-compatible routes. */
internal val KoogReasoningEffortLevels = listOf("minimal", "low", "medium", "high")

/** Anthropic levels, each mapped to a thinking budget. */
internal val KoogBudgetLevels = listOf("low", "medium", "high")

/** Ollama `think` switch. */
internal val KoogToggleLevels = listOf("off", "on")

/**
 * Last-resort guess by model family, used only when neither the provider API nor the catalog knows the model.
 * Unknown families keep the provider default and show no selector.
 */
internal fun KoogProvider.fallbackReasoningEfforts(model: String): List<String> = when (this) {
    KoogProvider.OpenAI -> if (OpenAIReasoning.containsMatchIn(model)) KoogBudgetLevels else emptyList()

    KoogProvider.Anthropic -> if (AnthropicReasoning.containsMatchIn(model)) KoogBudgetLevels else emptyList()

    KoogProvider.AlibabaQwen,
    KoogProvider.Ollama,
    KoogProvider.OpenAICompatible,
    KoogProvider.AnthropicCompatible,
    -> emptyList()
}

/**
 * Request parameters carrying [effort]; default parameters when no effort is selected. [maxOutputTokens] is the
 * model's output limit when known: Anthropic then gets the whole limit for thinking plus answer instead of a fixed cap.
 */
internal fun KoogProvider.reasoningParams(effort: String?, maxOutputTokens: Long? = null): LLMParams {
    val level = effort ?: return LLMParams()
    return when (this) {
        KoogProvider.OpenAI, KoogProvider.AlibabaQwen, KoogProvider.OpenAICompatible ->
            OpenAIChatParams(reasoningEffort = openAIEffort(level))

        KoogProvider.Anthropic, KoogProvider.AnthropicCompatible -> {
            // Anthropic requires max_tokens above the thinking budget; the rest is left for the answer.
            val limit = maxOutputTokens?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
            val budget = limit?.let {
                anthropicBudget(
                    level,
                ).coerceAtMost(it - ANSWER_TOKENS).coerceAtLeast(MIN_BUDGET)
            }
                ?: anthropicBudget(level)
            val maxTokens = limit?.coerceAtLeast(budget + MIN_BUDGET) ?: (budget + ANSWER_TOKENS)
            AnthropicParams(maxTokens = maxTokens, thinking = AnthropicThinking.Enabled(budget))
        }

        KoogProvider.Ollama -> OllamaParams(think = level == "on")
    }
}

private fun openAIEffort(level: String): ReasoningEffort = when (level) {
    "minimal" -> ReasoningEffort.MINIMAL
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

private val OpenAIReasoning = Regex("^(o\\d|gpt-5)")
private val AnthropicReasoning = Regex("^claude-(3-7-sonnet|(opus|sonnet|haiku)-4)")
private const val LOW_BUDGET = 2_048
private const val MEDIUM_BUDGET = 8_192
private const val HIGH_BUDGET = 24_576
private const val ANSWER_TOKENS = 8_192
private const val MIN_BUDGET = 1_024
