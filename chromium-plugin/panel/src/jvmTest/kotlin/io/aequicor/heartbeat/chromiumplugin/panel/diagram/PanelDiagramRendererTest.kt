package io.aequicor.heartbeat.chromiumplugin.panel.diagram

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.chromiumplugin.panel.FakePanelHost
import io.aequicor.heartbeat.chromiumplugin.panel.link.PanelLink
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.ds.components.HbDiagramFailure
import io.aequicor.heartbeat.ds.components.HbDiagramLanguage
import io.aequicor.heartbeat.ds.components.HbDiagramLink
import io.aequicor.heartbeat.ds.components.HbDiagramRequest
import io.aequicor.heartbeat.ds.components.HbDiagramResult
import io.aequicor.heartbeat.ds.components.HbDiagramStyle
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.BridgeErrorCode
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.DiagramFailure
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.DiagramLink
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.DiagramRenderRequest
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.DiagramRenderResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PanelDiagramRendererTest {
    private val request = HbDiagramRequest(
        HbDiagramLanguage.PlantUml,
        "@startuml\nA -> B\n@enduml",
        HbDiagramStyle(Color.Black, Color.Gray, Color.Gray, Color.White, Color.Black, Color.Blue, 14f, false),
        2f,
    )

    @Test
    fun `a disconnected or unsupported app leaves the source without requesting a drawing`() = runTest {
        val host = FakePanelHost()
        val renderer = renderer(host)
        for (status in listOf(
            """{"type":"status","status":"connecting"}""",
            """{"type":"status","status":"ready","features":{"diagrams":false}}""",
            """{"type":"status","status":"ready","features":{"studio":true}}""",
        )) {
            host.deliver(status)
            assertEquals(HbDiagramResult.Unsupported, renderer.render(request))
            assertFalse(renderer.openFullSize(request))
        }
        assertTrue(host.posted.isEmpty())
    }

    @Test
    fun `native PNG replies preserve physical pixels logical bounds and links`() = runTest {
        val host = readyHost()
        val renderer = renderer(host)
        val rendering = async { renderer.render(request) }
        runCurrent()

        val call = host.posted.single()
        assertEquals("diagram.render", call.getValue("method").jsonPrimitive.content)
        val wire = diagramJson.decodeFromJsonElement<DiagramRenderRequest>(call.getValue("params"))
        assertEquals(request.source, wire.source)
        assertEquals(2f, wire.scale)
        assertEquals(Color.Black.toArgb(), wire.style.text)
        assertEquals(14f, wire.style.fontSize)
        host.reply(image())

        val drawn = assertIs<HbDiagramResult.Image>(rendering.await())
        assertEquals(4, drawn.bitmap.width)
        assertEquals(2, drawn.bitmap.height)
        assertEquals(DpSize(2.dp, 1.dp), drawn.size)
        assertEquals(
            listOf(HbDiagramLink(Rect(0f, 0f, 2f, 1f), "https://example.com/diagram", "Details")),
            drawn.links,
        )
    }

    @Test
    fun `syntax and retryable drawing failures preserve their user-facing meaning`() = runTest {
        val host = readyHost()
        val renderer = renderer(host)
        val outcomes = listOf(
            DiagramRenderResult.SyntaxError(
                2,
                "Unexpected token",
            ) to HbDiagramResult.SyntaxError(2, "Unexpected token"),
            DiagramRenderResult.Failed(DiagramFailure.Busy) to HbDiagramResult.Failed(HbDiagramFailure.Busy),
            DiagramRenderResult.Failed(DiagramFailure.Timeout) to HbDiagramResult.Failed(HbDiagramFailure.Timeout),
            DiagramRenderResult.Unsupported to HbDiagramResult.Unsupported,
        )
        for ((reply, expected) in outcomes) {
            val rendering = async { renderer.render(request) }
            runCurrent()
            host.reply(reply)
            assertEquals(expected, rendering.await())
        }
    }

    @Test
    fun `browser zoom above the native render limit clamps the requested scale`() = runTest {
        val host = readyHost()
        val renderer = renderer(host)
        val rendering = async { renderer.render(request.copy(density = 4f)) }
        runCurrent()
        val wire = diagramJson.decodeFromJsonElement<DiagramRenderRequest>(host.posted.single().getValue("params"))
        assertEquals(3f, wire.scale)
        host.reply(DiagramRenderResult.Unsupported)
        assertEquals(HbDiagramResult.Unsupported, rendering.await())
    }

    @Test
    fun `bridge refusals distinguish retryable failures from an unavailable renderer`() = runTest {
        val host = readyHost()
        val renderer = renderer(host)
        val errors = listOf(
            BridgeErrorCode.BUSY to HbDiagramResult.Failed(HbDiagramFailure.Busy),
            BridgeErrorCode.TIMEOUT to HbDiagramResult.Failed(HbDiagramFailure.Timeout),
            BridgeErrorCode.UNAVAILABLE to HbDiagramResult.Unsupported,
            BridgeErrorCode.UNKNOWN_METHOD to HbDiagramResult.Unsupported,
        )
        for ((code, expected) in errors) {
            val rendering = async { renderer.render(request) }
            runCurrent()
            val id = host.posted.last().getValue("id").jsonPrimitive.int
            host.deliver("""{"type":"reply","id":$id,"error":{"code":"$code"}}""")
            assertEquals(expected, rendering.await())
        }
    }

    @Test
    fun `opening a browser diagram passes PNG and an opaque padded RGB background`() = runTest {
        val host = readyHost()
        val renderer = renderer(host)
        val opened = mutableListOf<JsonObject>()
        host.openDiagram = { opened += Json.parseToJsonElement(it).jsonObject }
        val opening = async { renderer.openFullSize(request) }
        runCurrent()
        val image = image()
        host.reply(image)

        assertTrue(opening.await())
        assertEquals(image.png, opened.single().getValue("png").jsonPrimitive.content)
        assertEquals("#0000ff", opened.single().getValue("background").jsonPrimitive.content)
        assertFalse(opened.single().containsKey("source"))
    }

    @Test
    fun `a failed drawing cannot open an empty browser diagram`() = runTest {
        val host = readyHost()
        val renderer = renderer(host)
        var opened = false
        host.openDiagram = { opened = true }
        val opening = async { renderer.openFullSize(request) }
        runCurrent()
        host.reply(DiagramRenderResult.Failed(DiagramFailure.Busy))

        assertFalse(opening.await())
        assertFalse(opened)
    }

    @Test
    fun `a drawing may take forty seconds without hitting the ordinary panel call timeout`() = runTest {
        val host = readyHost()
        val renderer = renderer(host)
        val rendering = async { renderer.render(request) }
        runCurrent()
        advanceTimeBy(40_000)
        runCurrent()
        assertFalse(rendering.isCompleted)
        host.reply(DiagramRenderResult.SyntaxError(2, "Bad source"))
        assertEquals(HbDiagramResult.SyntaxError(2, "Bad source"), rendering.await())
    }

    @Test
    fun `a missing diagram reply eventually becomes a retryable timeout`() = runTest {
        val host = readyHost()
        assertEquals(HbDiagramResult.Failed(HbDiagramFailure.Timeout), renderer(host).render(request))
        assertTrue(testScheduler.currentTime >= 40_000)
    }

    @Test
    fun `cancelling a pending drawing propagates cancellation`() = runTest {
        val host = readyHost()
        val renderer = renderer(host)
        var returnedResult = false
        val rendering = async { renderer.render(request).also { returnedResult = true } }
        runCurrent()
        rendering.cancel()
        assertFailsWith<CancellationException> { rendering.await() }
        assertFalse(returnedResult)
    }

    @Test
    fun `cancellation while opening a browser diagram is not reported as success`() = runTest {
        val host = readyHost()
        val renderer = renderer(host)
        host.openDiagram = { throw CancellationException("Panel closed") }
        val opening = async { renderer.openFullSize(request) }
        runCurrent()
        host.reply(image())

        assertFailsWith<CancellationException> { opening.await() }
    }

    private fun TestScope.renderer(host: FakePanelHost): PanelDiagramRenderer {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = object : DispatcherProvider {
            override val main = dispatcher
            override val default = dispatcher
            override val io = dispatcher
        }
        return PanelDiagramRenderer(PanelLink(host), host, dispatchers, Color.Blue.toArgb())
    }

    private fun readyHost(): FakePanelHost = FakePanelHost().apply {
        deliver("""{"type":"status","status":"ready","features":{"diagrams":true}}""")
    }

    private fun FakePanelHost.reply(result: DiagramRenderResult) {
        deliver(
            buildJsonObject {
                put("type", "reply")
                put("id", posted.last().getValue("id"))
                put("result", diagramJson.encodeToJsonElement(result))
            }.toString(),
        )
    }

    private fun image(): DiagramRenderResult.Image {
        val bytes = ByteArrayOutputStream().use { output ->
            check(ImageIO.write(BufferedImage(4, 2, BufferedImage.TYPE_INT_ARGB), "png", output))
            output.toByteArray()
        }
        return DiagramRenderResult.Image(
            Base64.encode(bytes),
            2f,
            1f,
            2f,
            listOf(DiagramLink(0f, 0f, 2f, 1f, "https://example.com/diagram", "Details")),
        )
    }
}
