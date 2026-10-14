package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.chat

import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.BridgeProtocol
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeCall
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeClient
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeDispatcher
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeIncomingEvent
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeReply
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeSession
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.BridgeErrorCode
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.DiagramFailure
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.DiagramLink
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.DiagramRenderLimits
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.DiagramRenderRequest
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.DiagramRenderResult
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.DiagramStyle
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlFailure
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlLink
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlRenderer
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlRequest
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlResult
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DiagramRenderHandlerTest {
    private val source = "@startuml\nA -> B\n@enduml"
    private val style = DiagramStyle(1, 2, 3, 4, 5, 6, 14f, false)
    private val request = DiagramRenderRequest(source, style, 2f)
    private val renderer = Renderer()
    private fun TestScope.handler(): DiagramRenderHandler =
        DiagramRenderHandler(renderer, StandardTestDispatcher(testScheduler))

    @Test
    fun `preview returns local PNG and logical link coordinates without changing source or theme`() = runTest {
        val png = byteArrayOf(1, 2, 3)
        renderer.result = PlantUmlResult.Image(
            png,
            200f,
            100f,
            2f,
            listOf(PlantUmlLink(1f, 2f, 3f, 4f, "https://example.com", "Example")),
        )

        val reply = assertIs<BridgeReply.Ok>(handler().handle(call(backgroundScope)))
        assertEquals("image", reply.result.jsonObject.getValue("kind").jsonPrimitive.content)
        val image = assertIs<DiagramRenderResult.Image>(decode(reply))
        assertContentEquals(png, Base64.decode(image.png))
        assertEquals(200f, image.width)
        assertEquals(100f, image.height)
        assertEquals(2f, image.scale)
        assertEquals(listOf(DiagramLink(1f, 2f, 3f, 4f, "https://example.com", "Example")), image.links)
        assertEquals(
            listOf(PlantUmlRequest(source, PlantUmlStyle(1, 2, 3, 4, 5, 6, 14f, false), 2f)),
            renderer.requests,
        )
    }

    @Test
    fun `disabled and failed renderers preserve source fallback syntax lines and retry reasons`() = runTest {
        val outcomes = listOf(
            PlantUmlResult.Unsupported to DiagramRenderResult.Unsupported,
            PlantUmlResult.SyntaxError(3, "Syntax error") to DiagramRenderResult.SyntaxError(3, "Syntax error"),
        ) + PlantUmlFailure.entries.map { failure ->
            PlantUmlResult.Failed(failure) to DiagramRenderResult.Failed(DiagramFailure.valueOf(failure.name))
        }
        for ((result, expected) in outcomes) {
            renderer.result = result
            assertEquals(expected, decode(assertIs<BridgeReply.Ok>(handler().handle(call(backgroundScope)))))
        }
    }

    @Test
    fun `oversized sources never reach the renderer and oversized images never cross the bridge`() = runTest {
        val oversized = request.copy(source = "x".repeat(DiagramRenderLimits.MAX_SOURCE_CHARACTERS + 1))
        assertEquals(
            DiagramRenderResult.Failed(DiagramFailure.TooLarge),
            decode(assertIs<BridgeReply.Ok>(handler().handle(call(backgroundScope, oversized)))),
        )
        assertEquals(emptyList(), renderer.requests)
        renderer.result = PlantUmlResult.Image(ByteArray(DiagramRenderLimits.MAX_PNG_BYTES + 1), 1f, 1f, 1f)
        assertEquals(
            DiagramRenderResult.Failed(DiagramFailure.TooLarge),
            decode(assertIs<BridgeReply.Ok>(handler().handle(call(backgroundScope)))),
        )
    }

    @Test
    fun `invalid rendering dimensions and requests before handshake are refused without drawing`() = runTest {
        val dispatcher = BridgeDispatcher(setOf(handler()))
        val invalid = listOf(
            request.copy(scale = 0f),
            request.copy(scale = DiagramRenderLimits.MAX_SCALE + 1f),
            request.copy(style = style.copy(fontSize = -1f)),
            request.copy(style = style.copy(fontSize = DiagramRenderLimits.MAX_FONT_SIZE + 1f)),
        )
        for (params in invalid) {
            assertEquals(
                BridgeErrorCode.BAD_REQUEST,
                assertIs<BridgeReply.Refused>(dispatcher.dispatch(call(backgroundScope, params))).code,
            )
        }
        val call = call(backgroundScope)
        (call.session as Session).client.value = null
        assertEquals(BridgeErrorCode.NOT_READY, assertIs<BridgeReply.Refused>(dispatcher.dispatch(call)).code)
        assertEquals(emptyList(), renderer.requests)
    }

    private fun call(scope: CoroutineScope, params: DiagramRenderRequest = request): BridgeCall = BridgeCall(
        "diagram.render",
        BridgeProtocol.json.encodeToJsonElement(DiagramRenderRequest.serializer(), params).jsonObject,
        Session(scope),
    )

    private fun decode(reply: BridgeReply.Ok): DiagramRenderResult =
        BridgeProtocol.json.decodeFromJsonElement(DiagramRenderResult.serializer(), reply.result)

    private class Renderer : PlantUmlRenderer {
        override val availability = flowOf(true)
        var result: PlantUmlResult = PlantUmlResult.Unsupported
        val requests = mutableListOf<PlantUmlRequest>()

        override suspend fun render(request: PlantUmlRequest): PlantUmlResult {
            requests += request
            return result
        }
    }

    private class Session(override val scope: CoroutineScope) : BridgeSession {
        override val id = 1L
        override val origin = "chrome-extension://test/"
        override val client = MutableStateFlow<BridgeClient?>(BridgeClient(origin, "1", "Chrome", "1", setOf("studio")))
        override val events = MutableSharedFlow<BridgeIncomingEvent>()

        override fun accept(client: BridgeClient) {
            this.client.value = client
        }

        override suspend fun emit(event: String, params: JsonObject) = Unit

        override suspend fun call(method: String, params: JsonObject, timeoutMillis: Long): JsonElement = error(
            "unused",
        )
    }
}
