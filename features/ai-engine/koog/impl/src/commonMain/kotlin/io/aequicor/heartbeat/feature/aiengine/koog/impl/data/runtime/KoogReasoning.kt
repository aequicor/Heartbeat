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

        KoogProvider.Anthropic, KoogProvider.AnthropicCompatible -> anthropicParams(level, maxOutputTokens)

        KoogProvider.Ollama -> OllamaParams(think = level == "on")
    }
}

/**
 * Anthropic requires max_tokens above the thinking budget. With a known [maxOutputTokens] the request uses the whole
 * limit and never exceeds it; a limit too small for the minimum budget sends no thinking.
 */
private fun anthropicParams(level: String, maxOutputTokens: Long?): LLMParams {
    val limit = maxOutputTokens?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
        ?: return anthropicBudget(level).let {
            AnthropicParams(maxTokens = it + ANSWER_TOKENS, thinking = AnthropicThinking.Enabled(it))
        }
    if (limit < 2 * MIN_BUDGET) return LLMParams()
    val budget = anthropicBudget(level).coerceIn(MIN_BUDGET, limit - minOf(ANSWER_TOKENS, limit / 2))
    return AnthropicParams(maxTokens = limit, thinking = AnthropicThinking.Enabled(budget))
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
