package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.chat

import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.protocol.BridgeProtocol
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeCall
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeReply
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.server.BridgeRequestHandler
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.BridgeErrorCode
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.DiagramFailure
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.DiagramLink
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.DiagramRenderLimits
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.DiagramRenderRequest
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.DiagramRenderResult
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.DiagramStyle
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlFailure
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlRenderer
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlRequest
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlResult
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlStyle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64

/**
 * Draws panel diagrams through the same local sandbox, toggle, cache and resource limits as desktop Markdown.
 * The wire contains only source, theme and raster data; it never opens URLs or reads files. Large replies use the
 * connection's normal chunk transport. Source text and image contents are never logged.
 */
internal class DiagramRenderHandler(private val renderer: PlantUmlRenderer, private val encoder: CoroutineDispatcher) :
    BridgeRequestHandler {
    override val methods: Set<String> = setOf(METHOD)

    override suspend fun handle(call: BridgeCall): BridgeReply {
        if (call.method != METHOD) return BridgeReply.Refused(BridgeErrorCode.UNKNOWN_METHOD, "Unknown method")
        val request = BridgeProtocol.json.decodeFromJsonElement(DiagramRenderRequest.serializer(), call.params)
        require(request.scale.isFinite() && request.scale > 0f && request.scale <= DiagramRenderLimits.MAX_SCALE) {
            "Invalid diagram scale"
        }
        require(
            request.style.fontSize.isFinite() && request.style.fontSize > 0f &&
                request.style.fontSize <= DiagramRenderLimits.MAX_FONT_SIZE,
        ) { "Invalid diagram font size" }
        val result = if (request.source.length > DiagramRenderLimits.MAX_SOURCE_CHARACTERS) {
            PlantUmlResult.Failed(PlantUmlFailure.TooLarge)
        } else {
            renderer.render(PlantUmlRequest(request.source, request.style.toPlantUml(), request.scale))
        }
        return withContext(encoder) {
            BridgeReply.Ok(BridgeProtocol.json.encodeToJsonElement(DiagramRenderResult.serializer(), result.toWire()))
        }
    }

    private fun PlantUmlResult.toWire(): DiagramRenderResult = when (this) {
        is PlantUmlResult.Image -> if (png.size > DiagramRenderLimits.MAX_PNG_BYTES) {
            DiagramRenderResult.Failed(DiagramFailure.TooLarge)
        } else {
            DiagramRenderResult.Image(
                Base64.encode(png),
                width,
                height,
                scale,
                links.map { DiagramLink(it.left, it.top, it.right, it.bottom, it.destination, it.tooltip) },
            )
        }

        is PlantUmlResult.SyntaxError -> DiagramRenderResult.SyntaxError(line, message)

        is PlantUmlResult.Failed -> DiagramRenderResult.Failed(reason.toWire())

        PlantUmlResult.Unsupported -> DiagramRenderResult.Unsupported
    }

    private companion object {
        const val METHOD = "diagram.render"
    }
}

private fun DiagramStyle.toPlantUml(): PlantUmlStyle = PlantUmlStyle(
    text,
    secondaryText,
    line,
    fill,
    border,
    accentFill,
    fontSize,
    isDark,
)

private fun PlantUmlFailure.toWire(): DiagramFailure = when (this) {
    PlantUmlFailure.TooLarge -> DiagramFailure.TooLarge
    PlantUmlFailure.Timeout -> DiagramFailure.Timeout
    PlantUmlFailure.Busy -> DiagramFailure.Busy
    PlantUmlFailure.Internal -> DiagramFailure.Internal
}
