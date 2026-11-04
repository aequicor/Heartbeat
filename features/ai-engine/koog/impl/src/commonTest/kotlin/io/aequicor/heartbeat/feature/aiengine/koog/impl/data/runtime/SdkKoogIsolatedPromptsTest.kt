package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSecretId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.withoutLabel
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedAnswer
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompt
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPromptConstraints
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPromptFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineContext
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogConnection
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineId
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.sse.SSECapability
import io.ktor.client.plugins.sse.SSESession
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.sse.ServerSentEvent
import io.ktor.util.date.GMTDate
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class SdkKoogIsolatedPromptsTest {
    @Test
    fun `two fresh SDK contexts per provider finish and release their own clients`() = runTest {
        for (provider in KoogProvider.entries) {
            val f = KoogTestFixture(this)
            val source = source(f, provider)
            f.connections.values[0] = KoogConnection(f.binding, source)
            var requests = 0
            val config = MockEngineConfig().apply {
                addHandler {
                    requests++
                    if (provider == KoogProvider.Ollama) {
                        respond(OLLAMA_RESPONSE, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    } else {
                        sseResponse(events(provider).asFlow())
                    }
                }
            }
            val engine = object : MockEngine(config) {
                override val supportedCapabilities = super.supportedCapabilities + SSECapability
            }
            HttpClient(engine).use { http ->
                f.isolatedTransport = SdkKoogTransport(http, TestKoogPlatform)
                repeat(2) { assertEquals(IsolatedAnswer.Answered("READY"), f.ask(source), provider.name) }
                assertTrue(http.coroutineContext.job.isActive)
            }
            assertEquals(2, requests)
            assertEquals(if (provider == KoogProvider.Ollama) 0 else 2, f.secrets.reads)
            assertEquals(0, f.connections.puts)
            assertEquals(0, f.records.saves)
            assertEquals(0, f.opens)
        }
    }

    @Test
    fun `a broken SDK stream has no retry or fallback round`() = runTest {
        val f = KoogTestFixture(this)
        val source = source(f, KoogProvider.OpenAI)
        f.connections.values[0] = KoogConnection(f.binding, source)
        var requests = 0
        val config = MockEngineConfig().apply {
            addHandler {
                requests++
                sseResponse(
                    flow {
                        emit(events(KoogProvider.OpenAI).first())
                        throw IOException("private provider stream failure")
                    },
                )
            }
        }
        val engine = object : MockEngine(config) {
            override val supportedCapabilities = super.supportedCapabilities + SSECapability
        }
        HttpClient(engine).use { http ->
            f.isolatedTransport = SdkKoogTransport(http, TestKoogPlatform)
            assertFailsWith<Exception> { f.ask(source) }
            assertTrue(http.coroutineContext.job.isActive)
        }
        assertEquals(1, requests)
    }

    @Test
    fun `source removal during SDK client construction stops dispatch before the HTTP engine`() = runTest {
        val f = KoogTestFixture(this)
        val source = source(f, KoogProvider.OpenAI)
        f.connections.values[0] = KoogConnection(f.binding, source)
        val engine = MockEngine { error("credentials must not reach an HTTP request") }
        HttpClient(engine).use { http ->
            val sdk = SdkKoogTransport(http, TestKoogPlatform)
            f.isolatedTransport = object : KoogTransport by sdk {
                override suspend fun openIsolated(
                    key: String?,
                    origin: EndpointOrigin,
                    basePath: String?,
                    request: IsolatedKoogRequest,
                ): KoogClient? {
                    val client = sdk.openIsolated(key, origin, basePath, request)
                    f.connections.values.clear()
                    return client
                }
            }
            assertFailsWith<EngineException> { f.ask(source) }
            assertTrue(http.coroutineContext.job.isActive)
        }
    }

    @Test
    fun `facade guard runs after SDK setup before a POST while the adapter route remains unchanged`() = runTest {
        val f = KoogTestFixture(this)
        val source = source(f, KoogProvider.OpenAI)
        f.connections.values[0] = KoogConnection(f.binding, source)
        var isConstructed = false
        val config = MockEngineConfig().apply { addHandler { error("dispatch must be refused") } }
        val engine = object : MockEngine(config) {
            override val supportedCapabilities = super.supportedCapabilities + SSECapability
        }
        HttpClient(engine).use { http ->
            val sdk = SdkKoogTransport(http, TestKoogPlatform)
            f.isolatedTransport = object : KoogTransport by sdk {
                override suspend fun openIsolated(
                    key: String?,
                    origin: EndpointOrigin,
                    basePath: String?,
                    request: IsolatedKoogRequest,
                ): KoogClient? = sdk.openIsolated(key, origin, basePath, request).also { isConstructed = true }
            }
            assertFailsWith<EngineException> {
                f.ask(source) {
                    assertTrue(isConstructed)
                    throw EngineException(EngineFailure.Access(AccessFailureReason.OperationNotAllowed))
                }
            }
        }
        assertEquals(source, f.connections.values.single().source)
    }

    @Test
    fun `Ollama rejects quoted termination truncated answers and tool roles instead of accepting text`() = runTest {
        val original = Json.parseToJsonElement(OLLAMA_RESPONSE).jsonObject
        val message = original.getValue("message").jsonObject
        val variants = listOf(
            original + ("done" to JsonPrimitive("true")),
            original + ("done" to JsonPrimitive("false")),
            original + ("done" to JsonPrimitive(false)),
            original + ("done_reason" to JsonPrimitive("length")),
            original + ("message" to JsonObject(message + ("role" to JsonPrimitive("tool")))),
            original + ("message" to JsonObject(message + ("role" to JsonPrimitive("user")))),
        )
        for (variant in variants) {
            assertEquals(
                IsolatedAnswer.Unavailable(IsolatedPromptFailure.Incomplete),
                ollama(JsonObject(variant).toString()),
            )
        }
        val excessive = original + ("message" to JsonObject(message + ("content" to JsonPrimitive("READY-EXCESS"))))
        assertEquals(
            IsolatedAnswer.Unavailable(IsolatedPromptFailure.ResponseTooLarge),
            ollama(JsonObject(excessive).toString(), maxChars = 5),
        )
    }

    @Test
    fun `Ollama cannot skip malformed or missing DTO fields and then accept a valid suffix`() = runTest {
        val original = Json.parseToJsonElement(OLLAMA_RESPONSE).jsonObject
        val variants = listOf(
            JsonObject(original - "model").toString(),
            OLLAMA_RESPONSE + "\n" + OLLAMA_RESPONSE,
            "not-json\n" + OLLAMA_RESPONSE,
        )
        for (body in variants) assertFailsWith<Exception> { ollama(body) }
        assertEquals(
            IsolatedAnswer.Unavailable(IsolatedPromptFailure.Incomplete),
            ollama(JsonObject(original + ("message" to JsonPrimitive("invalid"))).toString()),
        )
    }

    private suspend fun kotlinx.coroutines.test.TestScope.ollama(body: String, maxChars: Int = 1024): IsolatedAnswer {
        val f = KoogTestFixture(this)
        var requests = 0
        val engine = MockEngine {
            requests++
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val answer = HttpClient(engine).use { http ->
            f.isolatedTransport = SdkKoogTransport(http, TestKoogPlatform)
            f.adapter.answerIsolated(
                IsolatedPrompt(
                    f.target,
                    "READY",
                    maxAnswerChars = maxChars,
                    constraints = IsolatedPromptConstraints(f.source.withoutLabel(), 32),
                ),
                f.source,
                EngineContext(KoogEngineId, f.binding.id, model = f.target.model),
                TimeSource.Monotonic.markNow() + 5.seconds,
                beforeDispatch = {},
            )
        }
        assertEquals(1, requests)
        return answer
    }

    private fun source(f: KoogTestFixture, provider: KoogProvider): AuthSource = if (provider == KoogProvider.Ollama) {
        f.source
    } else {
        AuthSource.ManagedKey(
            f.source.info,
            AuthScope(
                provider.id,
                if (provider.isOriginEditable) EndpointOrigin("https://llm.example") else provider.origin,
            ),
            AuthSecretId("key"),
        )
    }

    private suspend fun KoogTestFixture.ask(
        source: AuthSource,
        guard: suspend () -> Unit = {
        },
    ): IsolatedAnswer = adapter.answerIsolated(
        IsolatedPrompt(target, "READY", constraints = IsolatedPromptConstraints(source.withoutLabel(), 32)),
        source,
        EngineContext(KoogEngineId, binding.id, model = target.model),
        TimeSource.Monotonic.markNow() + 5.seconds,
        beforeDispatch = guard,
    )
}

private suspend fun sseResponse(events: Flow<String>): HttpResponseData {
    val context = currentCoroutineContext()
    return HttpResponseData(
        HttpStatusCode.OK,
        GMTDate(),
        headersOf(HttpHeaders.ContentType, "text/event-stream"),
        HttpProtocolVersion.HTTP_1_1,
        object : SSESession {
            override val coroutineContext = context
            override val incoming = events.map { ServerSentEvent(data = it) }
        },
        context,
    )
}

private fun events(provider: KoogProvider): List<String> = if (provider == KoogProvider.Anthropic ||
    provider == KoogProvider.AnthropicCompatible
) {
    ANTHROPIC_EVENTS
} else {
    OPENAI_EVENTS
}

private val ANTHROPIC_EVENTS = listOf(
    """
        {
          "type": "message_start",
          "message": {
            "id": "m",
            "type": "message",
            "role": "assistant",
            "model": "test-model",
            "content": [],
            "stop_reason": null,
            "stop_sequence": null,
            "usage": {
              "input_tokens": 1,
              "output_tokens": 0
            }
          }
        }
    """.trimIndent(),
    """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
    """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"READY"}}""",
    """{"type":"content_block_stop","index":0}""",
    """
        {
          "type": "message_delta",
          "delta": {
            "stop_reason": "end_turn",
            "stop_sequence": null
          },
          "usage": {
            "output_tokens": 1
          }
        }
    """.trimIndent(),
    """{"type":"message_stop"}""",
)

private val OPENAI_EVENTS = listOf(
    """
        {
          "id": "m",
          "object": "chat.completion.chunk",
          "created": 0,
          "model": "test-model",
          "choices": [
            {
              "index": 0,
              "delta": {
                "role": "assistant",
                "content": "READY"
              },
              "finish_reason": null
            }
          ]
        }
    """.trimIndent(),
    """
        {
          "id": "m",
          "object": "chat.completion.chunk",
          "created": 0,
          "model": "test-model",
          "choices": [
            {
              "index": 0,
              "delta": {},
              "finish_reason": "stop"
            }
          ]
        }
    """.trimIndent(),
    "[DONE]",
)

private val OLLAMA_RESPONSE = """
    {
      "model": "test-model",
      "created_at": "2026-01-01T00:00:00Z",
      "message": { "role": "assistant", "content": "READY" },
      "done": true,
      "done_reason": "stop",
      "prompt_eval_count": 1,
      "eval_count": 1
    }
""".trimIndent()
