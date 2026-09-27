package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.theme.HbVisualStyle

/** Draws real, opposing blurred shadows without clipping them to the surface bounds. */
@Composable
@ReadOnlyComposable
public fun Modifier.hbSurface(
    background: Color,
    shape: Shape,
    isInset: Boolean = false,
    isQuiet: Boolean = false,
): Modifier {
    if (HbTheme.visualStyle == HbVisualStyle.Glass) {
        return hbGlassSurface(background, shape, isQuiet)
    }
    if (HbTheme.visualStyle == HbVisualStyle.Platform) {
        val borderColor = HbTheme.colors.outlineSubtle.copy(alpha = if (isQuiet) 0f else 1f)
        return background(background, shape).border(HbTheme.dimensions.borderWidth, borderColor, shape).clip(shape)
    }
    return hbSoftSurface(background, shape, if (isInset) 1f else 0f, if (isQuiet) 0f else 1f)
}

/** Translucent fill and a fine rim; shadows extend beyond the rounded surface. */
@Composable
@ReadOnlyComposable
internal fun Modifier.hbGlassSurface(background: Color, shape: Shape, isQuiet: Boolean = false): Modifier {
    val colors = HbTheme.colors
    val dimensions = HbTheme.dimensions
    val shadow = Shadow(
        radius = dimensions.glassShadowRadius,
        color = colors.glassShadow,
        offset = DpOffset(dimensions.borderWidth, dimensions.glassShadowOffset),
        alpha = if (isQuiet) 0f else 1f,
    )
    return dropShadow(shape, shadow)
        .background(background.copy(alpha = colors.glassTint.alpha), shape)
        .border(
            dimensions.borderWidth,
            Brush.verticalGradient(listOf(colors.glassHighlight, colors.glassBorder)),
            shape,
        )
}

/** Stable draw nodes let animated shadows retarget without cancelling a pointer gesture. */
@Composable
@ReadOnlyComposable
internal fun Modifier.hbSoftSurface(
    background: Color,
    shape: Shape,
    insetFraction: Float,
    visibility: Float,
): Modifier {
    val shadows = HbTheme.shadows
    val raisedAlpha = (1f - insetFraction) * visibility
    val insetAlpha = insetFraction * visibility
    val raisedDark = Shadow(
        radius = shadows.blurRadius,
        color = shadows.dark,
        offset = DpOffset(shadows.offset, shadows.offset),
        alpha = raisedAlpha,
    )
    val raisedLight = Shadow(
        radius = shadows.blurRadius,
        color = shadows.light,
        offset = DpOffset(-shadows.offset, -shadows.offset),
        alpha = raisedAlpha,
    )
    val insetDark = Shadow(
        radius = shadows.pressedBlurRadius,
        color = shadows.dark,
        offset = DpOffset(shadows.pressedOffset, shadows.pressedOffset),
        alpha = insetAlpha,
    )
    val insetLight = Shadow(
        radius = shadows.pressedBlurRadius,
        color = shadows.light,
        offset = DpOffset(-shadows.pressedOffset, -shadows.pressedOffset),
        alpha = insetAlpha,
    )
    // Keep node identities stable during a pointer gesture; only draw parameters change on press.
    return dropShadow(shape, raisedLight)
        .dropShadow(shape, raisedDark)
        .background(background, shape)
        .innerShadow(shape, insetDark)
        .innerShadow(shape, insetLight)
        .clip(shape)
}

/** An explicit outline keeps keyboard focus distinguishable from soft decorative shadows. */
@Composable
@ReadOnlyComposable
internal fun Modifier.hbFocusOutline(isFocused: Boolean, shape: Shape): Modifier = border(
    HbTheme.dimensions.borderWidth,
    HbTheme.colors.outline.copy(alpha = if (isFocused) 1f else 0f),
    shape,
)
