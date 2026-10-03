package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.executor.ollama.client.OllamaParams
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
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
            KoogProvider.OpenAI.origin,
            listOf("o4-mini", "gpt-4o"),
            mapOf("o4-mini" to listOf("low")),
        )
        assertEquals(mapOf("o4-mini" to listOf("low"), "gpt-4o" to emptyList()), levels)
        val openAI = KoogProvider.OpenAI.origin
        assertEquals(listOf("low"), fixture.reasoning.levels(KoogProvider.OpenAI, openAI, "o4-mini"))
    }

    @Test
    fun `catalog answers when the api is silent and the guess covers unknown models`() = runTest {
        val fixture = KoogTestFixture(this)
        fixture.catalogLevels = mapOf("gpt-9" to listOf("minimal", "high"), "o3" to emptyList())
        val levels = fixture.reasoning.discover(
            KoogProvider.OpenAI,
            KoogProvider.OpenAI.origin,
            listOf("gpt-9", "o3", "o5-new"),
            null,
        )
        assertEquals(listOf("minimal", "high"), levels["gpt-9"])
        assertEquals(emptyList(), levels["o3"])
        assertEquals(KoogBudgetLevels, levels["o5-new"])
    }

    @Test
    fun `a compatible route learns catalog levels of the vendor origin it targets`() = runTest {
        val fixture = KoogTestFixture(this)
        fixture.catalogLevels = mapOf("qwen3.8-max" to listOf("low", "medium"))
        val levels = fixture.reasoning.discover(
            KoogProvider.OpenAICompatible,
            KoogProvider.AlibabaQwen.origin,
            listOf("qwen3.8-max"),
            null,
        )
        assertEquals(listOf("low", "medium"), levels["qwen3.8-max"])
        assertEquals(
            listOf("low", "medium"),
            fixture.reasoning.levels(KoogProvider.OpenAICompatible, KoogProvider.AlibabaQwen.origin, "qwen3.8-max"),
        )
        // The same model id on an unrelated server keeps the family guess of the compatible route: none.
        val foreign = fixture.reasoning.discover(
            KoogProvider.OpenAICompatible,
            EndpointOrigin("https://example.com"),
            listOf("qwen3.8-max"),
            null,
        )
        assertEquals(emptyList(), foreign["qwen3.8-max"])
    }

    @Test
    fun `catalog sections follow the vendor origin of editable routes`() {
        assertEquals("openai", catalogSection(KoogProvider.OpenAI, KoogProvider.OpenAI.origin))
        assertEquals(
            "alibaba-token-plan",
            catalogSection(KoogProvider.OpenAICompatible, KoogProvider.AlibabaQwen.origin),
        )
        assertEquals("openai", catalogSection(KoogProvider.OpenAICompatible, KoogProvider.OpenAI.origin))
        assertEquals(null, catalogSection(KoogProvider.OpenAICompatible, EndpointOrigin("https://example.com")))
        assertEquals(null, catalogSection(KoogProvider.Ollama, KoogProvider.Ollama.origin))
    }

    @Test
    fun `rejected models offer no effort until the provider api reports it`() = runTest {
        val fixture = KoogTestFixture(this)
        fixture.catalogLevels = mapOf("o4-mini" to listOf("high"))
        val openAI = KoogProvider.OpenAI.origin
        fixture.reasoning.reject(KoogProvider.OpenAI, openAI, "o4-mini")
        assertEquals(emptyList(), fixture.reasoning.levels(KoogProvider.OpenAI, openAI, "o4-mini"))
        val rediscovered = fixture.reasoning.discover(KoogProvider.OpenAI, openAI, listOf("o4-mini"), null)
        assertEquals(emptyList(), rediscovered["o4-mini"])
        val levels = fixture.reasoning.discover(
            KoogProvider.OpenAI,
            openAI,
            listOf("o4-mini"),
            mapOf("o4-mini" to listOf("low")),
        )
        assertEquals(listOf("low"), levels["o4-mini"])
        assertEquals(listOf("low"), fixture.reasoning.levels(KoogProvider.OpenAI, openAI, "o4-mini"))
    }

    @Test
    fun `a rejection the retry also hits keeps effort available`() = runTest {
        val fixture = KoogTestFixture(this)
        fixture.reasoning.discover(
            KoogProvider.Ollama,
            KoogProvider.Ollama.origin,
            listOf("test-model"),
            mapOf("test-model" to KoogToggleLevels),
        )
        fixture.executor.failure = KoogHttpClientException(statusCode = 400, errorBody = "context too long")
        val session = fixture.session()
        session.features.require(SendsPrompts).send(fixture.request().copy(reasoningEffort = "on"))
        runCurrent()
        assertEquals(2, fixture.executor.prompts.size)
        assertEquals(
            KoogToggleLevels,
            fixture.reasoning.levels(KoogProvider.Ollama, KoogProvider.Ollama.origin, "test-model"),
        )
    }

    @Test
    fun `rejected reasoning parameters are retried once without effort`() = runTest {
        val fixture = KoogTestFixture(this)
        fixture.reasoning.discover(
            KoogProvider.Ollama,
            KoogProvider.Ollama.origin,
            listOf("test-model"),
            mapOf("test-model" to KoogToggleLevels),
        )
        fixture.executor.nextFailure = KoogHttpClientException(statusCode = 400, errorBody = "unknown field think")
        val session = fixture.session()
        session.features.require(SendsPrompts).send(fixture.request().copy(reasoningEffort = "on"))
        runCurrent()
        fixture.executor.complete()
        runCurrent()
        assertEquals(TurnOutcome.Completed, assertIs<ActiveSessionState.Ready>(session.state.value).lastTurn?.outcome)
        assertEquals(OllamaParams(think = true), fixture.executor.prompts.first().params)
        assertEquals(ai.koog.prompt.params.LLMParams(), fixture.executor.prompts.last().params)
        val ollama = KoogProvider.Ollama.origin
        assertEquals(emptyList(), fixture.reasoning.levels(KoogProvider.Ollama, ollama, "test-model"))
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
