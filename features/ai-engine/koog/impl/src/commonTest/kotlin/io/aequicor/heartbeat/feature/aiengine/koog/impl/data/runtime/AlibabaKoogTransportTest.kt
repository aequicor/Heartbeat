package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.streaming.StreamFrame
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.sse.SSECapability
import io.ktor.client.plugins.sse.SSESession
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.sse.ServerSentEvent
import io.ktor.util.date.GMTDate
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AlibabaKoogTransportTest {
    @Test
    fun `model discovery uses the token plan host and compatible API prefix`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("token-plan.ap-southeast-1.maas.aliyuncs.com", request.url.host)
            assertEquals("/compatible-mode/v1/models", request.url.encodedPath)
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("Bearer mock-alibaba-key", request.headers[HttpHeaders.Authorization])
            respond(
                """{"object":"list","data":[{"id":"qwen-test","object":"model","created":0,"owned_by":"alibaba"}]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        HttpClient(engine).use { http ->
            SdkKoogTransport(http).open(KoogProvider.AlibabaQwen, "mock-alibaba-key").use { client ->
                assertEquals(listOf("qwen-test"), client.models().map { it.id })
            }
            assertTrue(http.coroutineContext[kotlinx.coroutines.Job]?.isActive == true)
        }
    }

    @Test
    fun `chat streaming assembles Qwen tool fragments with empty text on the token plan host`() = runTest {
        val exchange = ResearchExchange()
        val config = MockEngineConfig().apply { addHandler { exchange.respond(it) } }
        val engine = object : MockEngine(config) {
            override val supportedCapabilities = super.supportedCapabilities + SSECapability
        }
        HttpClient(engine).use { http ->
            SdkKoogTransport(http).open(KoogProvider.AlibabaQwen, "mock-alibaba-key", "qwen-test").use { client ->
                val frames = client.executor.executeStreaming(
                    prompt("research") { user("Find Kotlin docs") },
                    KoogProvider.AlibabaQwen.textModel("qwen-test", tools = true),
                    koogSearchTools,
                ).toList()
                assertEquals(
                    "SearchingReading",
                    frames.filterIsInstance<StreamFrame.TextComplete>().joinToString("") { it.text },
                )
                val calls = frames.filterIsInstance<StreamFrame.ToolCallComplete>()
                assertEquals(2, calls.size)
                val call = calls.first()
                assertEquals("search-1", call.id)
                assertEquals("web_search", call.name)
                assertEquals("Kotlin", call.contentJson["query"]?.jsonPrimitive?.content)
                assertEquals("fetch-1", calls.last().id)
                assertEquals("web_fetch", calls.last().name)
                assertEquals("https://kotlinlang.org", calls.last().contentJson["url"]?.jsonPrimitive?.content)
                assertTrue(frames.any { it is StreamFrame.End })
                val followup = client.executor.executeStreaming(
                    prompt("research") {
                        user("Find Kotlin docs")
                        assistant("SearchingReading")
                        calls.forEach { toolCall(tool = it.name, args = it.content, id = it.id) }
                        calls.forEach { toolResult(tool = it.name, output = "Fixture result", id = it.id) }
                    },
                    KoogProvider.AlibabaQwen.textModel("qwen-test", tools = true),
                    koogSearchTools,
                ).toList()
                assertEquals("Found docs", followup.filterIsInstance<StreamFrame.TextComplete>().single().text)
                assertEquals(2, exchange.requests)
            }
        }
    }

    @Test
    fun `rejected credentials never produce a fallback model list`() = runTest {
        val engine = MockEngine {
            respond(
                """{"error":{"message":"Invalid API key","type":"invalid_request_error","code":"invalid_api_key"}}""",
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        HttpClient(engine).use { http ->
            SdkKoogTransport(http).open(KoogProvider.AlibabaQwen, "mock-alibaba-key").use { client ->
                val error = assertFailsWith<EngineException> { koogCall { client.models() } }
                val auth = assertIs<EngineFailure.Authentication>(error.failure)
                assertEquals(AuthFailureReason.CredentialsRejected, auth.reason.reason)
            }
        }
    }
}

private suspend fun streamResponse(chunks: List<String>): HttpResponseData {
    val responseContext = currentCoroutineContext()
    return HttpResponseData(
        statusCode = HttpStatusCode.OK,
        requestTime = GMTDate(),
        headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
        version = HttpProtocolVersion.HTTP_1_1,
        body = object : SSESession {
            override val coroutineContext = responseContext
            override val incoming = chunks.asFlow().map { ServerSentEvent(data = it) }
        },
        callContext = responseContext,
    )
}

private class ResearchExchange {
    var requests = 0
        private set

    suspend fun respond(request: HttpRequestData): HttpResponseData {
        assertEquals("token-plan.ap-southeast-1.maas.aliyuncs.com", request.url.host)
        assertEquals("/compatible-mode/v1/chat/completions", request.url.encodedPath)
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("Bearer mock-alibaba-key", request.headers[HttpHeaders.Authorization])
        val sent = request.body.toByteArray().decodeToString()
        assertTrue(sent.contains("\"model\":\"qwen-test\""))
        assertTrue(sent.contains("\"stream\":true"))
        assertTrue(sent.contains("\"name\":\"web_search\""))
        assertTrue(sent.contains("\"name\":\"web_fetch\""))
        if (requests++ > 0) {
            val messages = Json.parseToJsonElement(sent).jsonObject.getValue("messages").jsonArray
            val results = messages.map { it.jsonObject }.filter { it["role"]?.jsonPrimitive?.content == "tool" }
            assertEquals(listOf("search-1", "fetch-1"), results.map { it["tool_call_id"]?.jsonPrimitive?.content })
            return streamResponse(
                listOf(
                    """{"id":"reply","object":"chat.completion.chunk","created":0,"model":"qwen-test",
                    "choices":[{"index":0,"delta":{"role":"assistant","content":"Found docs"},
                    "finish_reason":"stop"}]}""",
                    "[DONE]",
                ),
            )
        }
        return streamResponse(FragmentedToolChunks)
    }
}

private val FragmentedToolChunks = listOf(
    """{"id":"reply","object":"chat.completion.chunk","created":0,"model":"qwen-test",
        "choices":[{"index":0,"delta":{"role":"assistant","content":"Searching"},"finish_reason":null}]}""",
    """{"id":"reply","object":"chat.completion.chunk","created":0,"model":"qwen-test",
        "choices":[{"index":0,"delta":{"content":"","tool_calls":[{"index":0,
        "id":"search-1","type":"function","function":{"name":"web_search","arguments":""}}]},
        "finish_reason":null}]}""",
    """{"id":"reply","object":"chat.completion.chunk","created":0,"model":"qwen-test",
        "choices":[{"index":0,"delta":{"content":"","tool_calls":[{"index":0,
        "function":{"arguments":"{\"query\":\"Kot"}}]},"finish_reason":null}]}""",
    """{"id":"reply","object":"chat.completion.chunk","created":0,"model":"qwen-test",
        "choices":[{"index":0,"delta":{"content":"","tool_calls":[{"index":0,
        "function":{"arguments":"lin\"}"}}]},"finish_reason":null}]}""",
    """{"id":"reply","object":"chat.completion.chunk","created":0,"model":"qwen-test",
        "choices":[{"index":0,"delta":{"content":"Reading"},"finish_reason":null}]}""",
    """{"id":"reply","object":"chat.completion.chunk","created":0,"model":"qwen-test",
        "choices":[{"index":0,"delta":{"content":"","tool_calls":[{"index":1,
        "id":"fetch-1","type":"function","function":{"name":"web_fetch","arguments":""}}]},
        "finish_reason":null}]}""",
    """{"id":"reply","object":"chat.completion.chunk","created":0,"model":"qwen-test",
        "choices":[{"index":0,"delta":{"content":"","tool_calls":[{"index":1,
        "function":{"arguments":"{\"url\":\"https://kotlinlang.org\"}"}}]},"finish_reason":null}]}""",
    """{"id":"reply","object":"chat.completion.chunk","created":0,"model":"qwen-test",
        "choices":[{"index":0,"delta":{"content":""},"finish_reason":"tool_calls"}]}""",
    "[DONE]",
)
