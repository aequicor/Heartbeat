package io.aequicor.heartbeat.ds.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.theme.HbVisualStyle

/** Presses change only drawing parameters; geometry and pointer nodes remain unchanged. */
@Composable
internal fun Modifier.hbControlSurface(
    background: Color,
    shape: Shape,
    isPressed: Boolean,
    isQuiet: Boolean,
    isEnabled: Boolean,
): Modifier {
    if (HbTheme.visualStyle == HbVisualStyle.Glass) {
        return background(background, shape).border(
            HbTheme.dimensions.borderWidth,
            HbTheme.colors.glassBorder.copy(
                alpha = if (isQuiet && !isPressed) 0f else HbTheme.colors.glassBorder.alpha,
            ),
            shape,
        )
    }
    val motion = HbTheme.motion
    val animation = if (motion.isReducedMotion) snap<Float>() else tween<Float>(motion.fastMillis)
    val insetFraction by animateFloatAsState(
        targetValue = if (isPressed) 1f else 0f,
        animationSpec = animation,
        label = "controlInset",
    )
    val visibility by animateFloatAsState(
        targetValue = if (isEnabled && (!isQuiet || isPressed)) 1f else 0f,
        animationSpec = animation,
        label = "controlShadow",
    )
    return hbSoftSurface(background, shape, insetFraction, visibility)
}
