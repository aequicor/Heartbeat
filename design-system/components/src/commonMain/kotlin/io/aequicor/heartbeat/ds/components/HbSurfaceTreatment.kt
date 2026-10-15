package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import io.aequicor.heartbeat.ds.adaptive.adaptiveFocusOutline
import io.aequicor.heartbeat.ds.theme.HbTheme

/**
 * Flat surface of the studio style: an opaque fill and, unless [isQuiet], a hairline outline.
 * There are no inner, drop or paired neumorphic shadows; hierarchy comes from fill and weight.
 */
@Composable
@ReadOnlyComposable
public fun Modifier.hbSurface(background: Color, shape: Shape, isQuiet: Boolean = false): Modifier {
    val outlined = if (isQuiet) this else border(HbTheme.dimensions.borderWidth, HbTheme.colors.outlineSubtle, shape)
    return outlined.background(background, shape).clip(shape)
}

/**
 * Flat surface of a panel attached to its container's top and end edges, such as a summary laid over a conversation.
 * Only the bottom-start [corner] is rounded (by default the large shape's corner; a relative size resolves against the
 * panel and never exceeds its shorter side). The hairline is drawn over the content only on the start and bottom
 * sides, so the panel continues the container's edges instead of floating above them.
 */
@Composable
@ReadOnlyComposable
public fun Modifier.hbEdgeSurface(background: Color, corner: CornerSize = HbTheme.shapes.large.bottomStart): Modifier {
    val outline = HbTheme.colors.outlineSubtle
    val borderWidth = HbTheme.dimensions.borderWidth
    val shape = RoundedCornerShape(topStart = zero, topEnd = zero, bottomEnd = zero, bottomStart = corner)
    // The layer clip bounds input and composites once; it also trims the stroke's outer half along the corner.
    return clip(shape).drawWithCache {
        val width = borderWidth.toPx()
        // The clip's own radius, so the stroke's arc coincides with it whatever the corner's unit.
        val radius = corner.toPx(size, this).coerceAtMost(size.minDimension)
        // Extending the outline past the top and end edges leaves only the start and bottom strokes inside.
        val extended = RoundedCornerShape(bottomStart = radius)
            .createOutline(Size(size.width + width, size.height + width), layoutDirection, this)
        val edges = Path().apply {
            addOutline(extended)
            translate(Offset(if (layoutDirection == LayoutDirection.Rtl) -width else 0f, -width))
        }
        val stroke = Stroke(width * 2)
        onDrawWithContent {
            drawRect(background)
            drawContent()
            drawPath(edges, outline, style = stroke)
        }
    }
}

private val zero = CornerSize(0)

/**
 * Floating popup surface (menus, dialogs). The single exception to flat surfaces: a popup overlaps arbitrary
 * content, so a soft [io.aequicor.heartbeat.ds.tokens.HbColors.popupShadow] separates it where a hairline could
 * disappear against content of the same tone. No blur is used.
 */
@Composable
@ReadOnlyComposable
internal fun Modifier.hbPopupSurface(background: Color, shape: Shape): Modifier {
    val dimensions = HbTheme.dimensions
    val shadow = Shadow(
        radius = dimensions.popupShadowRadius,
        color = HbTheme.colors.popupShadow,
        offset = DpOffset(HbTheme.spacing.none, dimensions.popupShadowOffset),
    )
    return dropShadow(shape, shadow).hbSurface(background, shape)
}

/** Keyboard-only outer focus ring; fields pass [isTextInput] to expose pointer focus as well. */
@Composable
internal fun Modifier.hbFocusOutline(isFocused: Boolean, shape: Shape, isTextInput: Boolean = false): Modifier =
    adaptiveFocusOutline(
        isFocused,
        shape,
        HbTheme.colors,
        HbTheme.dimensions,
        isTextInput,
    )
