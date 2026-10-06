package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.MessagePart
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolImage
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KoogToolImageTransportTest {
    @Test
    fun `SDK serializers preserve the image accompanying a tool result`() = runTest {
        for (provider in listOf(KoogProvider.OpenAI, KoogProvider.Anthropic, KoogProvider.Ollama)) {
            var requests = 0
            val image = AgentToolImage("image/png", "aW1hZ2U=")
            val engine = MockEngine { request ->
                requests++
                val sent = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                assertTrue(sent.contains(image.data), "$provider dropped the image")
                assertTrue(sent.contains("geometry"))
                assertTrue(sent.contains(toolImageSource("capture")))
                val imageField = when (provider) {
                    KoogProvider.OpenAI -> "image_url"
                    KoogProvider.Anthropic -> "\"type\":\"image\""
                    else -> "\"images\""
                }
                assertTrue(sent.contains(imageField))
                respond(reply(provider), headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
            HttpClient(engine).use { http ->
                SdkKoogTransport(http).open(provider, "mock-key", "test").use { client ->
                    val input = prompt("capture") {
                        system(KOOG_RESOURCE_BOUNDARY)
                        user("Inspect the screen")
                        toolCall(tool = "computer_screenshot", args = "{}", id = "capture")
                        toolResult(tool = "computer_screenshot", output = "geometry", id = "capture")
                        user(listOf(MessagePart.Text(toolImageSource("capture")), image.koogPart()))
                    }
                    client.executor.execute(input, provider.textModel("test", attachments = true))
                }
            }
            assertEquals(1, requests)
        }
    }

    private fun reply(provider: KoogProvider): String = when (provider) {
        KoogProvider.Anthropic ->
            """{"id":"msg","type":"message","role":"assistant","model":"test","content":[{"type":"text","text":"seen"}],"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":1}}"""

        KoogProvider.Ollama ->
            """{"model":"test","created_at":"2026-01-01T00:00:00Z","message":{"role":"assistant","content":"seen"},"done":true,"done_reason":"stop"}"""

        else ->
            """{"id":"msg","object":"chat.completion","created":0,"model":"test","choices":[{"index":0,"message":{"role":"assistant","content":"seen"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""
    }
}
