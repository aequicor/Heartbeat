package io.aequicor.heartbeat.platform.desktop

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.components.HbDiagramFailure
import io.aequicor.heartbeat.ds.components.HbDiagramLanguage
import io.aequicor.heartbeat.ds.components.HbDiagramRenderer
import io.aequicor.heartbeat.ds.components.HbDiagramRequest
import io.aequicor.heartbeat.ds.components.HbDiagramResult
import io.aequicor.heartbeat.ds.components.HbDiagramStyle
import io.aequicor.heartbeat.ds.components.decodeHbImageBitmap
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlFailure
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlRenderer
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlRequest
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlResult
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Connects the PlantUML feature to the design system's diagram rows: maps the theme and density of a row to a
 * [PlantUmlRequest] and decodes the PNG on [decoder], off the UI thread. The feature decides what is drawn.
 */
internal class PlantUmlDiagramRenderer(
    private val plantUml: PlantUmlRenderer,
    private val decoder: CoroutineDispatcher,
) : HbDiagramRenderer {
    override suspend fun render(request: HbDiagramRequest): HbDiagramResult = when (request.language) {
        HbDiagramLanguage.PlantUml -> plantUml.render(request.toPlantUml()).toDiagram()
    }

    private suspend fun PlantUmlResult.toDiagram(): HbDiagramResult = when (this) {
        is PlantUmlResult.Image -> decode(this)
        is PlantUmlResult.SyntaxError -> HbDiagramResult.SyntaxError(line, message)
        is PlantUmlResult.Failed -> HbDiagramResult.Failed(reason.toDiagram())
        PlantUmlResult.Unsupported -> HbDiagramResult.Unsupported
    }

    private suspend fun decode(image: PlantUmlResult.Image): HbDiagramResult = try {
        val bitmap = withContext(decoder) { decodeHbImageBitmap(image.png) }
        HbDiagramResult.Image(bitmap, DpSize(Dp(image.width), Dp(image.height)))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "PlantUML image could not be decoded (${image.png.size} bytes)" }
        HbDiagramResult.Failed(HbDiagramFailure.Internal)
    }

    private companion object {
        val log = Log.tag("PlantUmlDiagrams")
    }
}

private fun HbDiagramRequest.toPlantUml(): PlantUmlRequest = PlantUmlRequest(
    source = source,
    style = style.toPlantUml(),
    scale = density,
)

private fun HbDiagramStyle.toPlantUml(): PlantUmlStyle = PlantUmlStyle(
    text = text.toArgb(),
    secondaryText = secondaryText.toArgb(),
    line = line.toArgb(),
    fill = fill.toArgb(),
    border = border.toArgb(),
    accentFill = accentFill.toArgb(),
    fontSize = fontSize,
    isDark = isDark,
)

private fun PlantUmlFailure.toDiagram(): HbDiagramFailure = when (this) {
    PlantUmlFailure.TooLarge -> HbDiagramFailure.TooLarge
    PlantUmlFailure.Timeout -> HbDiagramFailure.Timeout
    PlantUmlFailure.Busy -> HbDiagramFailure.Busy
    PlantUmlFailure.Internal -> HbDiagramFailure.Internal
}
