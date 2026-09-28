package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.prompt.executor.clients.anthropic.AnthropicParams
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicThinking
import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import ai.koog.prompt.params.LLMParams
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KoogReasoningTest {
    @Test
    fun `family guess covers known reasoning families`() {
        val levels = listOf("low", "medium", "high")
        assertEquals(levels, KoogProvider.OpenAI.fallbackReasoningEfforts("o4-mini"))
        assertEquals(levels, KoogProvider.OpenAI.fallbackReasoningEfforts("gpt-5.1"))
        assertTrue(KoogProvider.OpenAI.fallbackReasoningEfforts("gpt-4o").isEmpty())
        assertEquals(levels, KoogProvider.Anthropic.fallbackReasoningEfforts("claude-sonnet-4-5"))
        assertTrue(KoogProvider.Anthropic.fallbackReasoningEfforts("claude-3-5-haiku-latest").isEmpty())
        assertTrue(KoogProvider.Ollama.fallbackReasoningEfforts("o3").isEmpty())
    }

    @Test
    fun `selected effort becomes provider parameters`() {
        val openAI = assertIs<OpenAIChatParams>(KoogProvider.OpenAI.reasoningParams("high"))
        assertEquals(ReasoningEffort.HIGH, openAI.reasoningEffort)
        val anthropic = assertIs<AnthropicParams>(KoogProvider.Anthropic.reasoningParams("low"))
        val thinking = assertIs<AnthropicThinking.Enabled>(anthropic.thinking)
        assertTrue(requireNotNull(anthropic.maxTokens) > thinking.budgetTokens)
        assertEquals(LLMParams(), KoogProvider.OpenAI.reasoningParams(null))
    }

    @Test
    fun `anthropic uses the model output limit and keeps the budget below it`() {
        val anthropic = assertIs<AnthropicParams>(KoogProvider.Anthropic.reasoningParams("high", 32_000))
        val thinking = assertIs<AnthropicThinking.Enabled>(anthropic.thinking)
        assertEquals(32_000, anthropic.maxTokens)
        assertTrue(thinking.budgetTokens < 32_000)
    }
}
