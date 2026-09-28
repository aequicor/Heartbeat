package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.theme.HbTheme

/**
 * Decorative Heartbeat wave shared by the studio navigation and the single agent identity of a reply.
 * The enclosing control or adjacent author label owns accessibility; this mark adds no duplicate label.
 * Foundation drawing is shared by Material, Fluent and macOS, with no platform-specific behavior.
 */
@Composable
fun HbStudioMark(modifier: Modifier = Modifier) {
    val colors = HbTheme.surfaces
    val dimensions = HbTheme.dimensions
    Canvas(modifier.size(dimensions.avatarSize).clearAndSetSemantics { }) {
        val diameter = size.minDimension
        val originX = (size.width - diameter) / 2f
        val originY = (size.height - diameter) / 2f
        drawCircle(colors.avatar, radius = diameter * 0.48f)
        drawCircle(
            colors.accent.copy(alpha = 0.12f),
            radius = diameter * 0.47f,
            style = Stroke(width = dimensions.markStroke.toPx() / 2f),
        )
        val wave = Path().apply {
            moveTo(originX + diameter * 0.20f, originY + diameter * 0.52f)
            cubicTo(
                originX + diameter * 0.37f,
                originY + diameter * 0.55f,
                originX + diameter * 0.32f,
                originY + diameter * 0.29f,
                originX + diameter * 0.43f,
                originY + diameter * 0.29f,
            )
            cubicTo(
                originX + diameter * 0.56f,
                originY + diameter * 0.29f,
                originX + diameter * 0.50f,
                originY + diameter * 0.73f,
                originX + diameter * 0.63f,
                originY + diameter * 0.73f,
            )
            cubicTo(
                originX + diameter * 0.74f,
                originY + diameter * 0.73f,
                originX + diameter * 0.68f,
                originY + diameter * 0.48f,
                originX + diameter * 0.81f,
                originY + diameter * 0.49f,
            )
        }
        drawPath(wave, colors.accent, style = Stroke(dimensions.markStroke.toPx(), cap = StrokeCap.Round))
    }
}

@Preview
@Composable
private fun StudioMarkLightPreview() {
    HbTheme(darkTheme = false) { HbStudioMark() }
}

@Preview
@Composable
private fun StudioMarkDarkPreview() {
    HbTheme(darkTheme = true) { HbStudioMark() }
}
