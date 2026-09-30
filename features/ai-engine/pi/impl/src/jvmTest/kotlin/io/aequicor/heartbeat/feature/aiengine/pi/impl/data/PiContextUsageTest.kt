package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class PiContextUsageTest {
    private val now = Instant.fromEpochSeconds(1)
    private val model = json("""{"provider":"anthropic","id":"test","contextWindow":1000}""")

    @Test
    fun `native total replaces earlier requests without estimating a history tail`() {
        val usage = piContextUsage(message("""{"totalTokens":610}"""), model, now)
        assertEquals(610L, usage?.usedTokens)
        assertEquals(1000L, usage?.capacityTokens)
        assertEquals(100L, piContextUsage(message("""{"totalTokens":100}"""), model, now)?.usedTokens)
    }

    @Test
    fun `cache tokens are included when the native total is absent`() {
        val message = message("""{"input":10,"output":20,"cacheRead":300,"cacheWrite":40}""")
        assertEquals(370L, piContextUsage(message, model, now)?.usedTokens)
        assertNull(piContextUsage(message("""{"input":10,"output":20}"""), model, now))
    }

    @Test
    fun `Pi default zero counters without provider usage remain unknown`() {
        val zeroCounts = "\"input\":0,\"output\":0,\"cacheRead\":0,\"cacheWrite\":0"
        assertNull(piContextUsage(message("{\"totalTokens\":0,$zeroCounts}"), model, now))
        assertNull(piContextUsage(message("{$zeroCounts}"), model, now))
    }

    @Test
    fun `compatible model defaults do not confirm context capacity`() {
        for (provider in listOf(PiProvider.OpenAiCompatible.id, PiProvider.AnthropicCompatible.id)) {
            val model = json("""{"provider":"$provider","id":"test","contextWindow":128000}""")
            assertNull(piContextUsage(message("""{"totalTokens":4096}""", provider = provider), model, now))
        }
    }

    @Test
    fun `fixed native provider capacity equal to compatible fallback remains known`() {
        val provider = PiProvider.OpenAi.id
        val model = json("""{"provider":"$provider","id":"test","contextWindow":128000}""")
        val usage = piContextUsage(message("""{"totalTokens":4096}""", provider = provider), model, now)
        assertEquals(4096L, usage?.usedTokens)
        assertEquals(128000L, usage?.capacityTokens)
    }

    @Test
    fun `unknown window wrong model failed output and estimated context stay hidden`() {
        assertNull(piContextUsage(message("""{"totalTokens":610}"""), null, now))
        assertNull(piContextUsage(message("""{"totalTokens":610}"""), json("""{"id":"other"}"""), now))
        assertNull(piContextUsage(message("""{"totalTokens":610}""", "aborted"), model, now))
        assertNull(piContextUsage(json("""{"contextUsage":{"tokens":610,"contextWindow":1000}}"""), model, now))
    }

    private fun message(usage: String, reason: String = "stop", provider: String = "anthropic") = json(
        """{"role":"assistant","model":"test","provider":"$provider","stopReason":"$reason","usage":$usage}""",
    )

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
}
