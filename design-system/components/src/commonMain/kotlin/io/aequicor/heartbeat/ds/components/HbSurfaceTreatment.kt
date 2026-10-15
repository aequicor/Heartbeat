package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
 * The fill follows [shape]; the hairline is drawn over the content only on the start and bottom sides, so the panel
 * continues the container's edges instead of floating above them. Give [shape] square top and end corners.
 */
@Composable
@ReadOnlyComposable
public fun Modifier.hbEdgeSurface(background: Color, shape: Shape): Modifier {
    val outline = HbTheme.colors.outlineSubtle
    val borderWidth = HbTheme.dimensions.borderWidth
    // The shape clip also trims the outer half of the stroke along a rounded corner.
    return clip(shape).drawWithCache {
        val width = borderWidth.toPx()
        // Extending the outline past the top and end edges leaves only the start and bottom strokes inside.
        val extended = shape.createOutline(Size(size.width + width, size.height + width), layoutDirection, this)
        val path = Path().apply {
            addOutline(extended)
            translate(Offset(if (layoutDirection == LayoutDirection.Rtl) -width else 0f, -width))
        }
        val stroke = Stroke(width * 2)
        onDrawWithContent {
            drawRect(background)
            drawContent()
            drawPath(path, outline, style = stroke)
        }
    }
}

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
