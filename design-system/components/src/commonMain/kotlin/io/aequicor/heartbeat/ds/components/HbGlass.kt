package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.theme.HbVisualStyle

private val LocalGlassBackdrop = staticCompositionLocalOf<HazeState?> { null }

/** Shared capture scope with a quiet pastel backdrop. Effects capture only registered sources. */
@Composable
public fun HbGlassScene(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val state = remember { HazeState() }
    val colors = HbTheme.colors
    CompositionLocalProvider(LocalGlassBackdrop provides state) {
        Box(modifier = modifier) {
            Canvas(modifier = Modifier.fillMaxSize().hazeSource(state, zIndex = 0f)) {
                drawRect(colors.background)
                drawRect(
                    Brush.radialGradient(
                        listOf(colors.glassBackdropLavender, Color.Transparent),
                        center = Offset(size.width * 0.8f, size.height * 0.15f),
                        radius = size.maxDimension * 0.7f,
                    ),
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(colors.glassBackdropBlue, Color.Transparent),
                        center = Offset(size.width * 0.2f, size.height * 0.5f),
                        radius = size.maxDimension * 0.6f,
                    ),
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(colors.glassBackdropPeach, Color.Transparent),
                        center = Offset(size.width * 0.75f, size.height),
                        radius = size.maxDimension * 0.55f,
                    ),
                )
            }
            content()
        }
    }
}

/** Registers scrolling content for overlaid glass panels without blurring the content itself. */
@Composable
@ReadOnlyComposable
public fun Modifier.hbGlassSource(): Modifier {
    val state = LocalGlassBackdrop.current
    return if (state != null && HbTheme.visualStyle == HbVisualStyle.Glass) hazeSource(state, zIndex = 1f) else this
}

/** Keeps blur clipped to its rounded background without clipping the foreground's shadows. */
@Composable
public fun HbGlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = HbTheme.shapes.large,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier) {
        Box(modifier = Modifier.matchParentSize().hbGlassBackdrop(shape))
        content()
    }
}

/** Real backdrop blur for a small number of overlay panels; ordinary message rows remain cheap. */
@Composable
internal fun Modifier.hbGlassBackdrop(shape: Shape = HbTheme.shapes.large): Modifier {
    val state = LocalGlassBackdrop.current
    if (state == null || HbTheme.visualStyle != HbVisualStyle.Glass) return this
    val colors = HbTheme.colors
    val radius = HbTheme.dimensions.glassBlurRadius
    val style = remember(colors, radius) {
        HazeBlurStyle {
            blurRadius(radius)
            noiseFactor(0f)
            fallbackColorEffect(HazeColorEffect.tint(colors.surface))
        }
    }
    return clip(shape).hazeBlur(input = HazeInput.Sources(state), style = style)
}
