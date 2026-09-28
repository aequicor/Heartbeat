package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
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
