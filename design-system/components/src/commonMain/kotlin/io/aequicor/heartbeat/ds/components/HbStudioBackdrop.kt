package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.theme.HbTheme

/**
 * Still, broad blue and lavender light fields beneath the studio's floating pearl panels.
 * The shared Foundation drawing is identical in Material, Fluent and macOS visual styles.
 * Decorative light is excluded from accessibility and never intercepts content interactions.
 * [isAmbient] switches the light fields off when the caller needs the same plain neutral backdrop.
 */
@Composable
fun HbStudioBackdrop(
    modifier: Modifier = Modifier,
    isAmbient: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = HbTheme.studioColors
    Box(modifier) {
        Canvas(Modifier.matchParentSize().clearAndSetSemantics { }) {
            drawRect(colors.backdrop)
            if (isAmbient && size.minDimension > 0f) {
                drawRect(
                    Brush.radialGradient(
                        listOf(colors.ambientBlue, Color.Transparent),
                        center = Offset(size.width * 0.04f, size.height * 0.62f),
                        radius = size.maxDimension * 0.86f,
                    ),
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(colors.ambientLavender, Color.Transparent),
                        center = Offset(size.width * 0.94f, size.height * 0.08f),
                        radius = size.maxDimension * 0.74f,
                    ),
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(colors.ambientPeach, Color.Transparent),
                        center = Offset(size.width * 0.64f, size.height),
                        radius = size.maxDimension * 0.7f,
                    ),
                )
            }
        }
        content()
    }
}

@Preview
@Composable
private fun StudioBackdropLightPreview() {
    HbTheme(darkTheme = false) {
        HbStudioBackdrop(Modifier.size(HbTheme.studioDimensions.sidebarWidth)) { }
    }
}

@Preview
@Composable
private fun StudioBackdropDarkPreview() {
    HbTheme(darkTheme = true) {
        HbStudioBackdrop(Modifier.size(HbTheme.studioDimensions.sidebarWidth)) { }
    }
}
