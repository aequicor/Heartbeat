package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.executor.ollama.client.OllamaParams
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

class KoogReasoningLevelsTest {
    @Test
    fun `provider api wins over catalog and family guess`() = runTest {
        val fixture = KoogTestFixture(this)
        fixture.catalogLevels = mapOf("o4-mini" to listOf("high"))
        val levels = fixture.reasoning.discover(
            KoogProvider.OpenAI,
            listOf("o4-mini", "gpt-4o"),
            mapOf("o4-mini" to listOf("low")),
        )
        assertEquals(mapOf("o4-mini" to listOf("low"), "gpt-4o" to emptyList()), levels)
        assertEquals(listOf("low"), fixture.reasoning.levels(KoogProvider.OpenAI, "o4-mini"))
    }

    @Test
    fun `catalog answers when the api is silent and the guess covers unknown models`() = runTest {
        val fixture = KoogTestFixture(this)
        fixture.catalogLevels = mapOf("gpt-9" to listOf("minimal", "high"), "o3" to emptyList())
        val levels = fixture.reasoning.discover(KoogProvider.OpenAI, listOf("gpt-9", "o3", "o5-new"), null)
        assertEquals(listOf("minimal", "high"), levels["gpt-9"])
        assertEquals(emptyList(), levels["o3"])
        assertEquals(KoogBudgetLevels, levels["o5-new"])
    }

    @Test
    fun `rejected models offer no effort until the provider api reports it`() = runTest {
        val fixture = KoogTestFixture(this)
        fixture.catalogLevels = mapOf("o4-mini" to listOf("high"))
        fixture.reasoning.reject(KoogProvider.OpenAI, "o4-mini")
        assertEquals(emptyList(), fixture.reasoning.levels(KoogProvider.OpenAI, "o4-mini"))
        assertEquals(emptyList(), fixture.reasoning.discover(KoogProvider.OpenAI, listOf("o4-mini"), null)["o4-mini"])
        val levels = fixture.reasoning.discover(
            KoogProvider.OpenAI,
            listOf("o4-mini"),
            mapOf("o4-mini" to listOf("low")),
        )
        assertEquals(listOf("low"), levels["o4-mini"])
        assertEquals(listOf("low"), fixture.reasoning.levels(KoogProvider.OpenAI, "o4-mini"))
    }

    @Test
    fun `a rejection the retry also hits keeps effort available`() = runTest {
        val fixture = KoogTestFixture(this)
        fixture.reasoning.discover(KoogProvider.Ollama, listOf("test-model"), mapOf("test-model" to KoogToggleLevels))
        fixture.executor.failure = KoogHttpClientException(statusCode = 400, errorBody = "context too long")
        val session = fixture.session()
        session.features.require(SendsPrompts).send(fixture.request().copy(reasoningEffort = "on"))
        runCurrent()
        assertEquals(2, fixture.executor.prompts.size)
        assertEquals(KoogToggleLevels, fixture.reasoning.levels(KoogProvider.Ollama, "test-model"))
    }

    @Test
    fun `rejected reasoning parameters are retried once without effort`() = runTest {
        val fixture = KoogTestFixture(this)
        fixture.reasoning.discover(KoogProvider.Ollama, listOf("test-model"), mapOf("test-model" to KoogToggleLevels))
        fixture.executor.nextFailure = KoogHttpClientException(statusCode = 400, errorBody = "unknown field think")
        val session = fixture.session()
        session.features.require(SendsPrompts).send(fixture.request().copy(reasoningEffort = "on"))
        runCurrent()
        fixture.executor.complete()
        runCurrent()
        assertEquals(TurnOutcome.Completed, assertIs<ActiveSessionState.Ready>(session.state.value).lastTurn?.outcome)
        assertEquals(OllamaParams(think = true), fixture.executor.prompts.first().params)
        assertEquals(ai.koog.prompt.params.LLMParams(), fixture.executor.prompts.last().params)
        assertEquals(emptyList(), fixture.reasoning.levels(KoogProvider.Ollama, "test-model"))
    }

    @Test
    fun `catalog keeps only effort levels koog can send`() {
        val text = """{"openai":{"models":{
            "gpt-x":{"reasoning_options":[{"type":"effort","values":["none","low","high","xhigh"]}]},
            "gpt-4o":{"reasoning":false}}},
          "alibaba-token-plan":{"models":{"qwen":{"reasoning_options":[{"type":"toggle"}]}}}}"""
        val snapshot = requireNotNull(parseCatalog(text, Instant.fromEpochSeconds(0)))
        assertEquals(mapOf("gpt-x" to listOf("low", "high"), "gpt-4o" to emptyList()), snapshot.providers["openai"])
        assertEquals(mapOf("qwen" to emptyList()), snapshot.providers["alibaba-token-plan"])
    }
}
