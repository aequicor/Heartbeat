package io.aequicor.heartbeat.feature.adaptivesubagents.impl.data.research

import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class ModelsDevFactsTest {
    private val catalog = """
        {
          "anthropic": {"models": {
            "claude-haiku-4-5-20251001": {
              "name": "Claude Haiku 4.5",
              "cost": {"input": 1, "output": 5},
              "limit": {"context": 200000, "output": 64000},
              "modalities": {"input": ["text", "image", "pdf"]},
              "release_date": "2025-10-01"
            }
          }},
          "openai": {"models": {"gpt-5": {"cost": {"input": 1.25, "output": 10}, "limit": {"context": 400000}}}}
        }
    """.trimIndent()

    @Test
    fun `facts are found by exact or normalized id with prices limits and inputs`() = runTest {
        val clock = TestClock()
        val facts = ModelsDevFacts(client { respond(catalog) }, clock)
        val haiku = facts.facts(ModelId("anthropic/claude-haiku-4-5"))
        assertEquals(1.0, haiku?.inputUsdPerMTok)
        assertEquals(5.0, haiku?.outputUsdPerMTok)
        assertEquals(200_000, haiku?.contextTokens)
        assertEquals(setOf("text", "image", "pdf"), haiku?.modalities)
        assertEquals("2025-10-01", haiku?.releaseDate)
        assertEquals(400_000, facts.facts(ModelId("gpt-5"))?.contextTokens)
        assertNull(facts.facts(ModelId("unknown")))
    }

    @Test
    fun `the catalog is read once a day and a failure is not retried for ten minutes`() = runTest {
        val clock = TestClock()
        var requests = 0
        var isDown = true
        val facts = ModelsDevFacts(
            client {
                requests++
                if (isDown) respondError(HttpStatusCode.ServiceUnavailable) else respond(catalog)
            },
            clock,
        )
        assertNull(facts.facts(ModelId("gpt-5")))
        isDown = false
        assertNull(facts.facts(ModelId("gpt-5")))
        assertEquals(1, requests)
        clock.now += 11.minutes
        assertEquals(400_000, facts.facts(ModelId("gpt-5"))?.contextTokens)
        facts.facts(ModelId("gpt-5"))
        assertEquals(2, requests)
        clock.now += 25.hours
        facts.facts(ModelId("gpt-5"))
        assertEquals(3, requests)
    }

    @Test
    fun `text that is not a catalog object has no facts`() {
        assertNull(parseModelsDev("[]", Instant.fromEpochSeconds(0)))
    }

    private fun client(answer: suspend MockRequestHandleScope.() -> HttpResponseData) =
        HttpClient(MockEngine { answer() })

    private class TestClock(var now: Instant = Instant.fromEpochSeconds(1_000)) : Clock {
        override fun now(): Instant = now
    }
}
