package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.params.LLMParams
import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeSource

class IsolatedKoogWireTest {
    @Test
    fun `every provider sends one user message and a protocol token cap on the selected origin`() = runTest {
        for (provider in KoogProvider.entries) {
            val origin = if (provider.isOriginEditable) EndpointOrigin("https://llm.example:8443") else provider.origin
            val basePath = if (provider.isOriginEditable) "/custom" else null
            var requests = 0
            var guards = 0
            val engine = MockEngine { request ->
                requests++
                assertEquals(HttpMethod.Post, request.method)
                assertTrue(request.url.toString().startsWith(origin.value + "/"))
                val sent = Json.parseToJsonElement(
                    (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString(),
                ).jsonObject
                val messages = sent.getValue("messages").jsonArray
                assertEquals(1, messages.size)
                assertEquals("user", messages.single().jsonObject.getValue("role").jsonPrimitive.content)
                assertNull(sent["tools"])
                assertNull(sent["system"])
                assertTokenBudget(provider, sent)
                respond(response(provider), headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
            HttpClient(engine).use { http ->
                val spec = request(provider) { guards++ }
                val transport = SdkKoogTransport(http, TestKoogPlatform)
                assertNotNull(
                    transport.openIsolated(
                        if (provider == KoogProvider.Ollama) null else "mock-key",
                        origin,
                        basePath,
                        spec,
                    ),
                ).use { client ->
                    assertEquals(
                        "READY",
                        client.executor.execute(
                            prompt("check", LLMParams(maxTokens = 32)) { user("READY") },
                            provider.textModel("m"),
                        ).textContent(),
                    )
                }
            }
            assertEquals(1, requests)
            assertEquals(1, guards)
        }
    }

    @Test
    fun `a lease cannot send a second request even when the first succeeded`() = runTest {
        var requests = 0
        val engine = MockEngine {
            requests++
            respond(response(KoogProvider.OpenAI), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        HttpClient(engine).use { http ->
            val provider = KoogProvider.OpenAI
            assertNotNull(
                SdkKoogTransport(
                    http,
                    TestKoogPlatform,
                ).openIsolated("mock-key", provider.origin, null, request(provider)),
            ).use { client ->
                val input = prompt("check", LLMParams(maxTokens = 32)) { user("READY") }
                client.executor.execute(input, provider.textModel("m"))
                assertFailsWith<Exception> { client.executor.execute(input, provider.textModel("m")) }
            }
        }
        assertEquals(1, requests)
    }

    @Test
    fun `HTTP errors do not retry even when the injected client would retry POST`() = runTest {
        for (status in listOf(HttpStatusCode.TooManyRequests, HttpStatusCode.InternalServerError)) {
            var requests = 0
            val engine = MockEngine {
                requests++
                respond("{}", status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
            HttpClient(engine) {
                install(HttpRequestRetry) {
                    maxRetries = 3
                    retryIf { _, _ -> true }
                    delayMillis { 0 }
                }
            }.use { http ->
                val provider = KoogProvider.OpenAI
                assertNotNull(
                    SdkKoogTransport(
                        http,
                        TestKoogPlatform,
                    ).openIsolated("mock-key", provider.origin, null, request(provider)),
                ).use { client ->
                    assertFailsWith<Exception> {
                        client.executor.execute(
                            prompt("check") { user("READY") },
                            provider.textModel("m"),
                        )
                    }
                }
            }
            assertEquals(1, requests)
        }
    }

    @Test
    fun `discovery and inherited instructions are refused before any HTTP call`() = runTest {
        var requests = 0
        val engine = MockEngine {
            requests++
            error("unexpected request")
        }
        HttpClient(engine).use { http ->
            val provider = KoogProvider.OpenAI
            val transport = SdkKoogTransport(http, TestKoogPlatform)
            assertNotNull(transport.openIsolated("mock-key", provider.origin, null, request(provider))).use { client ->
                assertFailsWith<Exception> { client.models() }
            }
            assertNotNull(transport.openIsolated("mock-key", provider.origin, null, request(provider))).use { client ->
                assertFailsWith<Exception> {
                    client.executor.execute(
                        prompt("check") {
                            system("inherited")
                            user("READY")
                        },
                        provider.textModel("m"),
                    )
                }
            }
        }
        assertEquals(0, requests)
    }

    @Test
    fun `source guard and absolute deadline are checked at the wire before sending credentials`() = runTest {
        var requests = 0
        val engine = MockEngine {
            requests++
            error("unexpected request")
        }
        val provider = KoogProvider.OpenAI
        val clock = TestTimeSource()
        val spec = IsolatedKoogRequest(provider, "m", "READY", 32, clock.markNow() + 5.seconds) { clock += 6.seconds }
        HttpClient(engine).use { http ->
            assertNotNull(
                SdkKoogTransport(http, TestKoogPlatform).openIsolated("mock-key", provider.origin, null, spec),
            ).use { client ->
                assertFailsWith<Exception> {
                    client.executor.execute(
                        prompt("check") { user("READY") },
                        provider.textModel("m"),
                    )
                }
            }
        }
        assertEquals(0, requests)
    }

    @Test
    fun `unproven mobile logging refuses strict execution without a request`() = runTest {
        val engine = MockEngine { error("unexpected request") }
        HttpClient(engine).use { http ->
            for (host in listOf(HostPlatform.Ios, HostPlatform.Android)) {
                val platform = object : PlatformInfo {
                    override val host = host
                }
                assertNull(
                    SdkKoogTransport(
                        http,
                        platform,
                    ).openIsolated("mock-key", KoogProvider.OpenAI.origin, null, request(KoogProvider.OpenAI)),
                )
            }
        }
    }

    private fun assertTokenBudget(provider: KoogProvider, sent: kotlinx.serialization.json.JsonObject) {
        when (provider) {
            KoogProvider.Ollama -> {
                assertEquals(
                    "32",
                    sent.getValue("options").jsonObject.getValue("num_predict").jsonPrimitive.content,
                )
                assertEquals("false", sent.getValue("think").jsonPrimitive.content)
            }

            KoogProvider.OpenAI -> {
                assertEquals("32", sent.getValue("max_completion_tokens").jsonPrimitive.content)
                assertNull(sent["max_tokens"])
            }

            else -> {
                assertEquals("32", sent.getValue("max_tokens").jsonPrimitive.content)
                assertNull(sent["max_completion_tokens"])
            }
        }
    }

    private fun request(provider: KoogProvider, guard: suspend () -> Unit = {}) = IsolatedKoogRequest(
        provider,
        "m",
        "READY",
        32,
        TimeSource.Monotonic.markNow() + 30.seconds,
        beforeDispatch = guard,
    )
}

private fun response(provider: KoogProvider): String = when (provider) {
    KoogProvider.Anthropic, KoogProvider.AnthropicCompatible ->
        """{"id":"msg","type":"message","role":"assistant","model":"m","content":[{"type":"text","text":"READY"}],"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":1}}"""

    KoogProvider.Ollama ->
        """{"model":"m","created_at":"2026-01-01T00:00:00Z","message":{"role":"assistant","content":"READY"},"done":true,"done_reason":"stop","prompt_eval_count":1,"eval_count":1}"""

    else ->
        """{"id":"msg","object":"chat.completion","created":0,"model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"READY"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""
}
