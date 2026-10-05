package io.aequicor.heartbeat.ds.catalog

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.ds.components.HbDiagramFailure
import io.aequicor.heartbeat.ds.components.HbDiagramLabels
import io.aequicor.heartbeat.ds.components.HbDiagramRenderer
import io.aequicor.heartbeat.ds.components.HbDiagramRequest
import io.aequicor.heartbeat.ds.components.HbDiagramResult
import io.aequicor.heartbeat.ds.resources.HbString
import io.aequicor.heartbeat.ds.resources.hbString
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Catalog stand-in for a host diagram renderer: draws a two-participant sketch in the requested theme colors and
 * plays the other states by markers in the source (`error` — a syntax error, `busy` — one transient failure,
 * `wide` — a picture larger than the column, fitted inline and scrolled in the full-size viewer).
 */
internal class CatalogDiagramRenderer : HbDiagramRenderer {
    private val busySources = mutableSetOf<String>()

    override suspend fun render(request: HbDiagramRequest): HbDiagramResult {
        delay(RENDER_DELAY_MILLIS)
        return when {
            "error" in request.source -> HbDiagramResult.SyntaxError(line = 2, message = SAMPLE_SYNTAX_ERROR)
            "busy" in request.source && busySources.add(request.source) -> HbDiagramResult.Failed(HbDiagramFailure.Busy)
            "wide" in request.source -> sketch(request, WIDE_WIDTH, WIDE_HEIGHT)
            else -> sketch(request, WIDTH, HEIGHT)
        }
    }

    private fun sketch(request: HbDiagramRequest, width: Float, height: Float): HbDiagramResult.Image {
        val scale = request.density
        val bitmap = ImageBitmap((width * scale).roundToInt(), (height * scale).roundToInt())
        val boxTop = (height - BOX_HEIGHT) / 2
        val canvas = Canvas(bitmap)
        val colors = request.style
        val fill = Paint().apply { color = colors.fill }
        val border = Paint().apply {
            color = colors.border
            style = PaintingStyle.Stroke
            strokeWidth = scale
        }
        val line = Paint().apply {
            color = colors.line
            style = PaintingStyle.Stroke
            strokeWidth = LINE_WIDTH * scale
        }
        listOf(MARGIN, width - MARGIN - BOX_WIDTH).forEach { left ->
            val box = RoundRect(
                Rect(left * scale, boxTop * scale, (left + BOX_WIDTH) * scale, (boxTop + BOX_HEIGHT) * scale),
                CornerRadius(CORNER * scale),
            )
            canvas.drawPath(Path().apply { addRoundRect(box) }, fill)
            canvas.drawPath(Path().apply { addRoundRect(box) }, border)
        }
        val y = (boxTop + BOX_HEIGHT / 2) * scale
        val end = (width - MARGIN - BOX_WIDTH) * scale
        canvas.drawLine(Offset((MARGIN + BOX_WIDTH) * scale, y), Offset(end, y), line)
        canvas.drawLine(Offset(end - ARROW * scale, y - ARROW * scale), Offset(end, y), line)
        canvas.drawLine(Offset(end - ARROW * scale, y + ARROW * scale), Offset(end, y), line)
        return HbDiagramResult.Image(bitmap, DpSize(width.dp, height.dp))
    }

    private companion object {
        const val RENDER_DELAY_MILLIS = 600L
        const val WIDTH = 320f
        const val HEIGHT = 120f
        const val WIDE_WIDTH = 1600f
        const val WIDE_HEIGHT = 900f
        const val MARGIN = 16f
        const val BOX_WIDTH = 104f
        const val BOX_HEIGHT = 40f
        const val CORNER = 6f
        const val LINE_WIDTH = 1.5f
        const val ARROW = 6f
        const val SAMPLE_SYNTAX_ERROR = "Syntax Error? (Assumed diagram type: sequence)"
    }
}

@Composable
internal fun catalogDiagramLabels(): HbDiagramLabels = HbDiagramLabels(
    rendering = hbString(HbString.DiagramRendering),
    diagram = hbString(HbString.DiagramTitle),
    openFullSize = hbString(HbString.DiagramOpenFullSize),
    close = hbString(HbString.DiagramClose),
    syntaxErrorAtLine = hbString(HbString.DiagramSyntaxErrorAtLine),
    syntaxError = hbString(HbString.DiagramSyntaxError),
    tooLarge = hbString(HbString.DiagramTooLarge),
    timeout = hbString(HbString.DiagramTimeout),
    busy = hbString(HbString.DiagramBusy),
    failed = hbString(HbString.DiagramFailed),
    retry = hbString(HbString.RetryAction),
    showSource = hbString(HbString.DiagramShowSource),
    showDiagram = hbString(HbString.DiagramShowDiagram),
    copySource = hbString(HbString.DiagramCopySource),
    sourceCopied = hbString(HbString.DiagramSourceCopied),
    copyFailed = hbString(HbString.DiagramCopyFailed),
)
