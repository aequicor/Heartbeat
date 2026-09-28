package io.aequicor.heartbeat.ds.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalInputModeManager
import io.aequicor.heartbeat.ds.tokens.HbColors
import io.aequicor.heartbeat.ds.tokens.HbDimensions

/**
 * Focus treatment shared by native adapters and Foundation controls. Place before the focus target
 * and any clipping modifier. Pointer presses suppress a control's ring until a key or new focus;
 * merely moving the pointer preserves keyboard focus. Text inputs always expose their active state.
 */
@Composable
fun Modifier.adaptiveFocusOutline(
    isFocused: Boolean,
    shape: Shape,
    colors: HbColors,
    dimensions: HbDimensions,
    isTextInput: Boolean = false,
): Modifier {
    val inputMode = LocalInputModeManager.current
    var isPointerFocus by remember { mutableStateOf(false) }
    SideEffect(isFocused) {
        if (!isFocused) isPointerFocus = false
    }
    val isVisible = isFocused && (isTextInput || (!isPointerFocus && inputMode.inputMode == InputMode.Keyboard))
    val platform = LocalPlatformUi.current
    return onPreviewKeyEvent {
        isPointerFocus = false
        inputMode.requestInputMode(InputMode.Keyboard)
        false
    }.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) {
                    isPointerFocus = true
                }
            }
        }
    }.drawWithContent {
        drawContent()
        if (isVisible) drawFocusTreatment(platform, shape, colors, dimensions, isTextInput)
    }
}

private fun DrawScope.drawFocusTreatment(
    platform: PlatformUi,
    shape: Shape,
    colors: HbColors,
    dimensions: HbDimensions,
    isTextInput: Boolean,
) {
    if (isTextInput && platform != PlatformUi.MacOs) {
        val width = dimensions.fieldFocusWidth.toPx()
        drawLine(
            colors.focusAccent,
            Offset(0f, size.height - width / 2),
            Offset(size.width, size.height - width / 2),
            width,
        )
        return
    }
    val outset = dimensions.focusOutset.toPx()
    when (platform) {
        PlatformUi.MacOs -> drawFocusContour(shape, colors.focusRing, dimensions.macFocusWidth.toPx(), outset)

        PlatformUi.Fluent -> {
            drawFocusContour(shape, colors.focusOuter, dimensions.fluentFocusOuterWidth.toPx(), outset)
            drawFocusContour(shape, colors.focusInner, dimensions.fluentFocusInnerWidth.toPx(), outset / 2)
        }

        PlatformUi.Material -> drawFocusContour(shape, colors.focusAccent, dimensions.fieldFocusWidth.toPx(), outset)
    }
}

private fun DrawScope.drawFocusContour(shape: Shape, color: Color, width: Float, outset: Float) {
    val outline = shape.createOutline(size, layoutDirection, this)
    val expanded = when (outline) {
        is Outline.Rectangle -> Outline.Rectangle(outline.rect.inflate(outset))

        is Outline.Rounded -> outline.roundRect.run {
            Outline.Rounded(
                RoundRect(
                    left - outset,
                    top - outset,
                    right + outset,
                    bottom + outset,
                    topLeftCornerRadius.expanded(outset),
                    topRightCornerRadius.expanded(outset),
                    bottomRightCornerRadius.expanded(outset),
                    bottomLeftCornerRadius.expanded(outset),
                ),
            )
        }

        is Outline.Generic -> outline
    }
    drawOutline(expanded, color, style = Stroke(width))
}

private fun CornerRadius.expanded(amount: Float): CornerRadius = CornerRadius(x + amount, y + amount)
