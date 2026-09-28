package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.prompt.dsl.prompt
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SdkKoogTransportTest {
    @Test
    fun `cloud SDK serializers send native image and PDF content`() = runTest {
        val parts = listOf(
            ContentPart.Text("Read these sources"),
            ContentPart.Image(ResourceRef("data:image/png;base64,aW1hZ2U=", "image/png")),
            ContentPart.Resource(ResourceRef("data:application/pdf;base64,JVBERi0=", "application/pdf")),
        )
        listOf(KoogProvider.Anthropic, KoogProvider.OpenAI).forEach { provider ->
            val engine = MockEngine { request ->
                val sent = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                assertTrue(sent.contains("aW1hZ2U="))
                assertTrue(sent.contains("JVBERi0="))
                assertTrue(sent.contains(if (provider == KoogProvider.Anthropic) "\"type\":\"image\"" else "image_url"))
                assertTrue(
                    sent.contains(if (provider == KoogProvider.Anthropic) "\"type\":\"document\"" else "file_data"),
                )
                val body = if (provider == KoogProvider.Anthropic) {
                    """{"id":"msg","type":"message","role":"assistant","model":"test","content":[{"type":"text","text":"read"}],"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":1}}"""
                } else {
                    """{"id":"msg","object":"chat.completion","created":0,"model":"test","choices":[{"index":0,"message":{"role":"assistant","content":"read"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""
                }
                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
            HttpClient(engine).use { http ->
                SdkKoogTransport(http).open(provider, "mock-key", "test").use { client ->
                    val response = client.executor.execute(
                        prompt("resources") { user(parts.koogUserParts(provider)) },
                        provider.textModel("test", attachments = true),
                    )
                    assertEquals("read", response.textContent())
                }
            }
        }
    }

    @Test
    fun anthropicSdkExecutesWithExplicitModelMapping() = runTest {
        val body = """
            {"id":"msg","type":"message","role":"assistant","model":"test","content":[{"type":"text","text":"hello"}],"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":1}}
        """.trimIndent()
        val engine = MockEngine { request ->
            assertEquals("api.anthropic.com", request.url.host)
            assertEquals("/v1/messages", request.url.encodedPath)
            assertEquals("mock-key", request.headers["x-api-key"])
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        HttpClient(engine).use { http ->
            SdkKoogTransport(http).open(KoogProvider.Anthropic, "mock-key", "test").use { client ->
                val response = client.executor.execute(
                    prompt("test") { user("hello") },
                    KoogProvider.Anthropic.textModel("test"),
                )
                assertEquals("hello", response.textContent())
            }
            // Closing an SDK lease must not close the injected application transport.
            assertTrue(http.coroutineContext[kotlinx.coroutines.Job]?.isActive == true)
        }
    }

    @Test
    fun openAiModelDiscoveryUsesExactOriginAndClosesOnlyItsLease() = runTest {
        val engine = MockEngine { request ->
            assertEquals("api.openai.com", request.url.host)
            assertEquals("Bearer mock-key", request.headers[HttpHeaders.Authorization])
            val body = """{"object":"list","data":[{"id":"test","object":"model","created":0,"owned_by":"test"}]}"""
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        HttpClient(engine).use { http ->
            SdkKoogTransport(http).open(KoogProvider.OpenAI, "mock-key").use { client ->
                assertEquals(1, client.models().size)
            }
            assertTrue(http.coroutineContext[kotlinx.coroutines.Job]?.isActive == true)
        }
    }

    @Test
    fun ollamaDiscoveryUsesLocalOriginWithoutAuthorization() = runTest {
        val engine = MockEngine { request ->
            assertEquals("localhost", request.url.host)
            assertEquals(11434, request.url.port)
            assertEquals("/api/tags", request.url.encodedPath)
            assertEquals(null, request.headers[HttpHeaders.Authorization])
            respond("""{"models":[]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        HttpClient(engine).use { http ->
            SdkKoogTransport(http).open(KoogProvider.Ollama, null).use { client ->
                assertEquals(0, client.models().size)
            }
        }
    }

    @Test
    fun `compatible providers call the user origin with the native protocol`() = runTest {
        val origin = EndpointOrigin("https://llm.example:8443")
        listOf(KoogProvider.OpenAICompatible, KoogProvider.AnthropicCompatible).forEach { provider ->
            val isAnthropic = provider == KoogProvider.AnthropicCompatible
            val engine = MockEngine { request ->
                assertEquals(
                    "https://llm.example:8443/v1/" + if (isAnthropic) "messages" else "chat/completions",
                    request.url.toString(),
                )
                val body = if (isAnthropic) {
                    """{"id":"msg","type":"message","role":"assistant","model":"m","content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":1}}"""
                } else {
                    """{"id":"msg","object":"chat.completion","created":0,"model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""
                }
                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
            HttpClient(engine).use { http ->
                SdkKoogTransport(http).open(provider, "mock-key", "m", origin).use { client ->
                    val response = client.executor.execute(prompt("p") { user("hi") }, provider.textModel("m"))
                    assertEquals("ok", response.textContent())
                }
            }
        }
    }

    @Test
    fun `transport refuses a foreign origin for fixed routes and plain http for remote servers`() {
        HttpClient(MockEngine { error("no request expected") }).use { http ->
            val transport = SdkKoogTransport(http)
            assertFailsWith<IllegalArgumentException> {
                transport.open(KoogProvider.OpenAI, "mock-key", origin = EndpointOrigin("https://llm.example"))
            }
            assertFailsWith<IllegalArgumentException> {
                transport.open(KoogProvider.OpenAICompatible, "mock-key", origin = EndpointOrigin("http://llm.example"))
            }
        }
    }

    @Test
    fun `compatible providers honor a custom base path`() = runTest {
        val origin = EndpointOrigin("https://llm.example")
        listOf(
            Triple(KoogProvider.OpenAICompatible, "/api/v1", "https://llm.example/api/v1/chat/completions"),
            Triple(KoogProvider.AnthropicCompatible, "/anthropic", "https://llm.example/anthropic/v1/messages"),
        ).forEach { (provider, path, expected) ->
            val isAnthropic = provider == KoogProvider.AnthropicCompatible
            val engine = MockEngine { request ->
                assertEquals(expected, request.url.toString())
                val body = if (isAnthropic) {
                    """{"id":"msg","type":"message","role":"assistant","model":"m","content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":1}}"""
                } else {
                    """{"id":"msg","object":"chat.completion","created":0,"model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""
                }
                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
            HttpClient(engine).use { http ->
                SdkKoogTransport(http).open(provider, "mock-key", "m", origin, path).use { client ->
                    val response = client.executor.execute(prompt("p") { user("hi") }, provider.textModel("m"))
                    assertEquals("ok", response.textContent())
                }
            }
        }
    }
}
