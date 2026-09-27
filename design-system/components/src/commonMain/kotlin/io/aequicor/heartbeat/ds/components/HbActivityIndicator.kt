package io.aequicor.heartbeat.ds.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import io.aequicor.heartbeat.ds.theme.HbTheme

/**
 * Indeterminate progress for background work such as a running agent. The arc rotates in the draw phase only,
 * so rows hosting it never recompose per frame; reduced motion shows a static arc.
 * Pass [contentDescription] when no adjacent text announces the activity.
 */
@Composable
public fun HbActivityIndicator(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    color: Color = HbTheme.colors.brand,
    size: Dp = HbTheme.dimensions.iconSmallSize,
) {
    val isReducedMotion = HbTheme.motion.isReducedMotion
    val track = HbTheme.colors.outlineSubtle
    val strokeWidth = HbTheme.dimensions.borderWidth * STROKE_FACTOR
    val rotation = if (isReducedMotion) {
        null
    } else {
        rememberInfiniteTransition(label = "activity").animateFloat(
            initialValue = 0f,
            targetValue = FULL_TURN,
            animationSpec = infiniteRepeatable(tween(ROTATION_MILLIS, easing = LinearEasing), RepeatMode.Restart),
            label = "activityRotation",
        )
    }
    Canvas(
        modifier = modifier
            .size(size)
            .semantics {
                if (contentDescription != null) {
                    this.contentDescription = contentDescription
                    liveRegion = LiveRegionMode.Polite
                }
            }
            .graphicsLayer { rotationZ = rotation?.value ?: 0f },
    ) {
        val stroke = strokeWidth.toPx()
        val inset = stroke / 2f
        val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
        drawArc(track, 0f, FULL_TURN, useCenter = false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        drawArc(
            color,
            startAngle = -QUARTER_TURN,
            sweepAngle = SWEEP,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}

private const val FULL_TURN = 360f
private const val QUARTER_TURN = 90f
private const val SWEEP = 110f
private const val ROTATION_MILLIS = 900
private const val STROKE_FACTOR = 2f
