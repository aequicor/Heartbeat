package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KoogReasoningProbeTest {
    @Test
    fun `anthropic thinking capability becomes budget levels`() = runTest {
        val client = HttpClient(
            MockEngine { request ->
                assertEquals("key", request.headers["x-api-key"])
                respond(
                    """{"data":[
                      {"id":"thinker","capabilities":{"thinking":{"supported":true,"types":{"enabled":{"supported":true}}}}},
                      {"id":"plain","capabilities":{"thinking":{"supported":false,"types":{"enabled":{"supported":false}}}}},
                      {"id":"legacy"}]}""",
                )
            },
        )
        val levels = probeReasoning(client, KoogProvider.Anthropic, "key", emptyList())
        assertEquals(mapOf("thinker" to KoogBudgetLevels, "plain" to emptyList()), levels)
    }

    @Test
    fun `ollama thinking capability becomes a switch`() = runTest {
        val client = HttpClient(
            MockEngine { request ->
                val body = request.body.toByteArray().decodeToString()
                respond(if ("qwen3" in body) THINKING else PLAIN)
            },
        )
        val levels = probeReasoning(client, KoogProvider.Ollama, null, listOf("qwen3", "llama3"))
        assertEquals(mapOf("qwen3" to KoogToggleLevels, "llama3" to emptyList()), levels)
    }

    @Test
    fun `failed or silent apis defer to the catalog`() = runTest {
        val failing = HttpClient(MockEngine { respond("", HttpStatusCode.InternalServerError) })
        assertNull(probeReasoning(failing, KoogProvider.Anthropic, "key", emptyList()))
        assertNull(probeReasoning(failing, KoogProvider.OpenAI, "key", listOf("o3")))
    }
}

private const val THINKING = """{"capabilities":["completion","thinking"]}"""
private const val PLAIN = """{"capabilities":["completion"]}"""
