package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlinx.coroutines.awaitCancellation

@Preview(name = "Diagram · light", widthDp = 560, heightDp = 720)
@Composable
private fun DiagramLightPreview() {
    DiagramPreviewContent(isDark = false)
}

@Preview(name = "Diagram · dark", widthDp = 560, heightDp = 720)
@Composable
private fun DiagramDarkPreview() {
    DiagramPreviewContent(isDark = true)
}

/** Drawn, drawing, invalid and transiently failed fences; the stand-in renderer picks the state by a marker. */
@Composable
private fun DiagramPreviewContent(isDark: Boolean) {
    val renderer = remember {
        HbDiagramRenderer { request ->
            when {
                "error" in request.source -> HbDiagramResult.SyntaxError(line = 2, message = "Syntax Error?")
                "busy" in request.source -> HbDiagramResult.Failed(HbDiagramFailure.Busy)
                "slow" in request.source -> awaitCancellation()
                else -> previewSketch(request)
            }
        }
    }
    HbTheme(darkTheme = isDark) {
        HbDiagramsProvider(renderer) {
            HbColumn(modifier = Modifier.background(HbTheme.colors.background).padding(HbTheme.spacing.xl)) {
                listOf("A -> B", "A -> B : slow", "A -> : error", "A -> B : busy").forEach { source ->
                    HbMarkdown("```plantuml\n@startuml\n$source\n@enduml\n```")
                }
            }
        }
    }
}

/** Two filled participant boxes at the edges of a [PREVIEW_WIDTH] × [PREVIEW_HEIGHT] canvas. */
private fun previewSketch(request: HbDiagramRequest): HbDiagramResult.Image {
    val bitmap = ImageBitmap((PREVIEW_WIDTH * request.density).toInt(), (PREVIEW_HEIGHT * request.density).toInt())
    val canvas = Canvas(bitmap)
    val fill = Paint().apply { color = request.style.accentFill }
    val box = bitmap.width * PREVIEW_BOX_FRACTION
    canvas.drawRect(Rect(0f, 0f, box, bitmap.height.toFloat()), fill)
    canvas.drawRect(Rect(bitmap.width - box, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()), fill)
    return HbDiagramResult.Image(bitmap, DpSize(PREVIEW_WIDTH.dp, PREVIEW_HEIGHT.dp))
}

private const val PREVIEW_WIDTH = 240f
private const val PREVIEW_HEIGHT = 96f
private const val PREVIEW_BOX_FRACTION = 1f / 3
