package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp

/** Actual pill perimeter, rather than a stretched circular arc; animation is read only when drawing. */
internal fun Modifier.usageQuotaBorder(progress: State<Float>, color: Color, track: Color, strokeWidth: Dp): Modifier =
    drawWithCache {
        val stroke = strokeWidth.toPx()
        val inset = stroke / 2f
        val diameter = size.height - stroke
        val left = Rect(inset, inset, inset + diameter, size.height - inset)
        val right = Rect(size.width - inset - diameter, inset, size.width - inset, size.height - inset)
        val path = Path().apply {
            moveTo(size.width / 2f, inset)
            lineTo(right.center.x, inset)
            arcTo(right, -90f, 180f, false)
            lineTo(left.center.x, size.height - inset)
            arcTo(left, 90f, 180f, false)
            close()
        }
        val measure = PathMeasure().apply { setPath(path, false) }
        val segment = Path()
        onDrawBehind {
            drawPath(path, track, style = Stroke(stroke))
            val fraction = progress.value.coerceIn(0f, 1f)
            if (fraction >= 1f) {
                drawPath(path, color, style = Stroke(stroke))
            } else if (fraction > 0f) {
                segment.reset()
                measure.getSegment(0f, measure.length * fraction, segment, true)
                drawPath(segment, color, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
    }
