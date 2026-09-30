package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.streaming.StreamFrame
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
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
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KoogUsageTransportTest {
    @Test
    fun `raw Anthropic usage survives SDK decoding while disabled capture stays empty`() = runTest {
        val config = MockEngineConfig().apply { addHandler { usageStreamResponse() } }
        val engine = object : MockEngine(config) {
            override val supportedCapabilities = super.supportedCapabilities + SSECapability
        }
        HttpClient(engine).use { http ->
            for (enabled in listOf(false, true)) {
                SdkKoogTransport(http).open(KoogProvider.Anthropic, "test-key", "test").use { client ->
                    client.usage.enable(enabled)
                    val frames = client.executor.executeStreaming(
                        prompt("usage") { user("hello") },
                        KoogProvider.Anthropic.textModel("test"),
                    ).toList()
                    assertTrue(frames.any { it is StreamFrame.End })
                    if (enabled) assertEquals(137L, client.usage.total) else assertNull(client.usage.total)
                }
            }
        }
    }
}

private suspend fun usageStreamResponse(): HttpResponseData {
    val context = currentCoroutineContext()
    val chunks = SSE.lineSequence().filter { it.startsWith("data: ") }.map { it.removePrefix("data: ") }.toList()
    return HttpResponseData(
        HttpStatusCode.OK,
        GMTDate(),
        headersOf(HttpHeaders.ContentType, "text/event-stream"),
        HttpProtocolVersion.HTTP_1_1,
        object : SSESession {
            override val coroutineContext = context
            override val incoming = chunks.asFlow().map { ServerSentEvent(data = it) }
        },
        context,
    )
}

private val SSE = """
    event: message_start
    data: {"type":"message_start","message":{"id":"m","type":"message","role":"assistant","model":"test","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":7,"cache_creation_input_tokens":10,"cache_read_input_tokens":100,"output_tokens":0}}}

    event: content_block_start
    data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

    event: content_block_delta
    data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hello"}}

    event: content_block_stop
    data: {"type":"content_block_stop","index":0}

    event: message_delta
    data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":20}}

    event: message_stop
    data: {"type":"message_stop"}


""".trimIndent()
