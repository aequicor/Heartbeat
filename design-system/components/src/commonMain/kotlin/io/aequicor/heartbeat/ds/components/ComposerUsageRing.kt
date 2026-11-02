package io.aequicor.heartbeat.ds.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import io.aequicor.heartbeat.ds.theme.HbTheme

/**
 * Determinate ring filled clockwise from 12 o'clock by [progress]. While [isCompacting] the filled part fades
 * back and a comet-like sweep circles the ring; under reduced motion the sweep stays still.
 */
@Composable
internal fun UsageRing(progress: Float, color: Color, isCompacting: Boolean, modifier: Modifier = Modifier) {
    val track = HbTheme.colors.outlineSubtle
    val strokeWidth = HbTheme.dimensions.usageRingStrokeWidth
    val fillAlpha by animateFloatAsState(
        if (isCompacting) COMPACTING_FILL_ALPHA else 1f,
        usageSpec(HbTheme.motion.normalMillis, HbTheme.motion),
        label = "usage-fill",
    )
    Box(
        modifier.size(HbTheme.dimensions.usageRingSize).drawBehind {
            val stroke = strokeWidth.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawCircle(track, radius = (size.minDimension - stroke) / 2f, style = Stroke(stroke))
            if (progress > 0f) {
                drawArc(
                    color.copy(alpha = color.alpha * fillAlpha),
                    startAngle = -QUARTER_TURN,
                    sweepAngle = FULL_TURN * progress.coerceAtMost(1f),
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        },
    ) {
        if (isCompacting) CompactionSweep(color, Modifier.matchParentSize())
    }
}

/** The sweep turns in the draw phase only, so the meter does not recompose on every frame. */
@Composable
private fun CompactionSweep(color: Color, modifier: Modifier = Modifier) {
    val motion = HbTheme.motion
    val strokeWidth = HbTheme.dimensions.usageRingStrokeWidth
    val transition = rememberInfiniteTransition(label = "usage-compaction")
    val turn = transition.animateFloat(
        initialValue = 0f,
        targetValue = if (motion.isReducedMotion) 0f else FULL_TURN,
        animationSpec = infiniteRepeatable(tween(motion.usageCompactionMillis, easing = LinearEasing)),
        label = "usage-compaction-turn",
    )
    Box(
        modifier.graphicsLayer { rotationZ = turn.value }.drawBehind {
            val stroke = strokeWidth.toPx()
            val inset = stroke / 2f
            val brush = Brush.sweepGradient(
                0f to color.copy(alpha = 0f),
                COMET_SWEEP / FULL_TURN to color,
                center = center,
            )
            drawArc(
                brush,
                startAngle = 0f,
                sweepAngle = COMET_SWEEP,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        },
    )
}

private const val QUARTER_TURN = 90f
private const val FULL_TURN = 360f
private const val COMET_SWEEP = 140f
private const val COMPACTING_FILL_ALPHA = 0.35f
