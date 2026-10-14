package io.aequicor.heartbeat.chromiumplugin.panel.diagram

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.chromiumplugin.panel.link.PanelCallException
import io.aequicor.heartbeat.chromiumplugin.panel.link.PanelHost
import io.aequicor.heartbeat.chromiumplugin.panel.link.PanelLink
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.components.HbDiagramFailure
import io.aequicor.heartbeat.ds.components.HbDiagramLanguage
import io.aequicor.heartbeat.ds.components.HbDiagramLink
import io.aequicor.heartbeat.ds.components.HbDiagramRenderer
import io.aequicor.heartbeat.ds.components.HbDiagramRequest
import io.aequicor.heartbeat.ds.components.HbDiagramResult
import io.aequicor.heartbeat.ds.components.decodeHbImageBitmap
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.BridgeErrorCode
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.DiagramFailure
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.DiagramRenderLimits
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.DiagramRenderRequest
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.DiagramRenderResult
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.DiagramStyle
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.seconds

/**
 * Draws through the app's bounded local PlantUML worker. PNG bytes only travel over native messaging; the panel
 * decodes them without retaining a second encoded cache. The app reuses its drawing when the user opens a tab.
 */
internal class PanelDiagramRenderer(
    private val link: PanelLink,
    private val host: PanelHost,
    private val dispatchers: DispatcherProvider,
    private val background: Int,
) : HbDiagramRenderer {
    override suspend fun render(request: HbDiagramRequest): HbDiagramResult = when (val result = draw(request)) {
        is DiagramRenderResult.Image -> withContext(dispatchers.default) {
            require(result.png.length <= MAX_ENCODED_LENGTH) { "Diagram exceeds byte limit" }
            val bitmap = decodeHbImageBitmap(Base64.decode(result.png))
            require(result.width.isFinite() && result.width > 0 && result.height.isFinite() && result.height > 0) {
                "Invalid diagram size"
            }
            HbDiagramResult.Image(
                bitmap,
                DpSize(result.width.dp, result.height.dp),
                result.links.map { link ->
                    HbDiagramLink(Rect(link.left, link.top, link.right, link.bottom), link.destination, link.tooltip)
                }.toImmutableList(),
            )
        }

        is DiagramRenderResult.SyntaxError -> HbDiagramResult.SyntaxError(result.line, result.message)

        is DiagramRenderResult.Failed -> HbDiagramResult.Failed(result.reason.toFailure())

        DiagramRenderResult.Unsupported -> HbDiagramResult.Unsupported
    }

    override suspend fun openFullSize(request: HbDiagramRequest): Boolean {
        val image = draw(request) as? DiagramRenderResult.Image ?: return false
        host.openDiagram(
            buildJsonObject {
                put("png", image.png)
                put("background", "#" + (background and RGB_MASK).toString(HEX_RADIX).padStart(RGB_DIGITS, '0'))
            }.toString(),
        )
        return true
    }

    private suspend fun draw(request: HbDiagramRequest): DiagramRenderResult {
        if (request.language != HbDiagramLanguage.PlantUml || link.status.value.status != "ready" ||
            link.status.value.features?.isDiagrams != true
        ) {
            return DiagramRenderResult.Unsupported
        }
        return try {
            val response = link.call("diagram.render", diagramJson.encodeToJsonElement(request.toWire()), 50.seconds)
            diagramJson.decodeFromJsonElement<DiagramRenderResult>(response)
        } catch (error: PanelCallException) {
            log.w(error) { "Diagram request failed" }
            when (error.code) {
                BridgeErrorCode.UNAVAILABLE, BridgeErrorCode.UNKNOWN_METHOD -> DiagramRenderResult.Unsupported
                BridgeErrorCode.TIMEOUT -> DiagramRenderResult.Failed(DiagramFailure.Timeout)
                BridgeErrorCode.BUSY -> DiagramRenderResult.Failed(DiagramFailure.Busy)
                else -> DiagramRenderResult.Failed(DiagramFailure.Internal)
            }
        }
    }

    private companion object {
        val log = Log.tag("Panel/Diagram")
        const val MAX_ENCODED_LENGTH = 14 * 1024 * 1024
        const val RGB_MASK = 0xFFFFFF
        const val RGB_DIGITS = 6
        const val HEX_RADIX = 16
    }
}

/** Wire JSON uses the native bridge discriminator, independently from the panel port's `type`. */
internal val diagramJson: Json = Json {
    classDiscriminator = "kind"
    ignoreUnknownKeys = true
}

private fun HbDiagramRequest.toWire(): DiagramRenderRequest = DiagramRenderRequest(
    source,
    style.let {
        DiagramStyle(
            it.text.toArgb(),
            it.secondaryText.toArgb(),
            it.line.toArgb(),
            it.fill.toArgb(),
            it.border.toArgb(),
            it.accentFill.toArgb(),
            it.fontSize,
            it.isDark,
        )
    },
    density.coerceIn(1f, DiagramRenderLimits.MAX_SCALE),
)

private fun DiagramFailure.toFailure(): HbDiagramFailure = when (this) {
    DiagramFailure.TooLarge -> HbDiagramFailure.TooLarge
    DiagramFailure.Timeout -> HbDiagramFailure.Timeout
    DiagramFailure.Busy -> HbDiagramFailure.Busy
    DiagramFailure.Internal -> HbDiagramFailure.Internal
}
