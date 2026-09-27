package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Finite, deterministic pastel light scene. The owner supplies normalized animation progress. */
@Composable
fun HbCinematicBackdrop(progress: () -> Float, modifier: Modifier = Modifier) {
    val colors = HbTheme.colors
    Canvas(modifier.fillMaxSize().clearAndSetSemantics { }) {
        val phase = progress().coerceIn(0f, 1f)
        drawRect(colors.background)
        val drift = sin(phase * PI).toFloat()
        drawRect(
            Brush.radialGradient(
                listOf(colors.glassBackdropLavender.copy(alpha = 0.75f), Color.Transparent),
                center = Offset(size.width * (0.25f + drift * 0.15f), size.height * 0.3f),
                radius = size.maxDimension * 0.65f,
            ),
        )
        drawRect(
            Brush.radialGradient(
                listOf(colors.glassBackdropBlue.copy(alpha = 0.65f), Color.Transparent),
                center = Offset(size.width * 0.78f, size.height * (0.6f - drift * 0.16f)),
                radius = size.maxDimension * 0.6f,
            ),
        )
        drawRect(
            Brush.radialGradient(
                listOf(colors.glassBackdropPeach.copy(alpha = 0.32f), Color.Transparent),
                center = Offset(size.width * 0.48f, size.height),
                radius = size.maxDimension * 0.55f,
            ),
        )
        // A broad light sweep disappears before the final static frame.
        val light = colors.shadowLight.copy(alpha = 0.3f * (1f - phase))
        drawRect(
            Brush.linearGradient(
                listOf(Color.Transparent, light, Color.Transparent),
                start = Offset(size.width * (phase - 0.5f), 0f),
                end = Offset(size.width * (phase + 0.5f), size.height),
            ),
        )
    }
}

/** Glass orbit with a moving highlight. Pure drawing keeps frame updates out of the screen composition. */
@Composable
fun HbGlassOrbit(progress: () -> Float, modifier: Modifier = Modifier) {
    val colors = HbTheme.colors
    val tokens = HbTheme.welcome
    Canvas(modifier.clearAndSetSemantics { }) {
        val phase = progress().coerceIn(0f, 1f)
        val reveal = ((phase - tokens.symbolStart) / (tokens.titleStart - tokens.symbolStart)).coerceIn(0f, 1f)
        val diameter = size.minDimension * 0.7f
        val ringSize = Size(diameter, diameter)
        val origin = center - Offset(diameter / 2f, diameter / 2f)
        val thickness = diameter * 0.1f
        scale(0.85f + reveal * 0.15f) {
            drawCircle(
                Brush.radialGradient(listOf(colors.brand.copy(alpha = 0.16f * reveal), Color.Transparent)),
                radius = size.minDimension * 0.5f,
            )
            rotate(-25f + phase * 30f) {
                drawOval(
                    Brush.sweepGradient(
                        listOf(
                            colors.shadowLight.copy(alpha = 0.95f * reveal),
                            colors.dataCyan.copy(alpha = 0.32f * reveal),
                            colors.brand.copy(alpha = 0.6f * reveal),
                            colors.shadowLight.copy(alpha = 0.95f * reveal),
                        ),
                    ),
                    topLeft = origin,
                    size = ringSize,
                    style = Stroke(thickness),
                )
                drawOval(
                    colors.shadowLight.copy(alpha = 0.8f * reveal),
                    topLeft = origin - Offset(thickness / 2f, thickness / 2f),
                    size = Size(diameter + thickness, diameter + thickness),
                    style = Stroke(size.minDimension * 0.004f),
                )
                val angle = phase * PI * 2
                val point = center + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * (diameter / 2f)
                drawCircle(
                    Brush.radialGradient(
                        listOf(colors.shadowLight.copy(alpha = reveal), Color.Transparent),
                        center = point,
                        radius = thickness * 1.8f,
                    ),
                    radius = thickness * 1.8f,
                    center = point,
                )
            }
        }
    }
}

@Preview
@Composable
private fun GlassOrbitLightPreview() {
    HbTheme(darkTheme = false) { HbGlassOrbit({ 1f }, Modifier.size(HbTheme.welcome.symbolSize)) }
}

@Preview
@Composable
private fun GlassOrbitDarkPreview() {
    HbTheme(darkTheme = true) { HbGlassOrbit({ 1f }, Modifier.size(HbTheme.welcome.symbolSize)) }
}
