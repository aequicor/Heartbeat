package io.aequicor.heartbeat.ds.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbMotion

private val log = Log.tag("DS/ComposerUsage")

/**
 * Controlled usage disclosure in a composer. A known [contextPercent] renders a flat pill with a small
 * determinate ring and the percentage; the ring fills clockwise from 12 o'clock, eases between values and
 * turns amber from 80% and red at 100%. While [isCompacting] the fill fades and a sweep circles the ring
 * until the new, smaller occupancy arrives; the caller states compaction in [accessibleLabel] as well.
 * Null renders the caller's localized [label] (for example, "Limits") without implying zero usage.
 * The owner omits this control when no usage information is available and supplies the detail [content].
 * Enter, Space and arrow keys open the panel; Escape and outside clicks dismiss it and restore focus.
 * The shared Foundation implementation deliberately follows the flat composer style on every platform.
 */
@Composable
public fun HbComposerUsageButton(
    contextPercent: Int?,
    label: String,
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    accessibleLabel: String = label,
    enabled: Boolean = true,
    isCompacting: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val focus = remember { FocusRequester() }
    var isPreviouslyOpen by remember { mutableStateOf(false) }
    val isOpen = isExpanded && enabled
    SideEffect(isOpen) {
        if (!isOpen && isPreviouslyOpen && enabled) focus.requestFocus()
        isPreviouslyOpen = isOpen
    }
    val changeExpanded: (Boolean) -> Unit = {
        log.i { "composer usage expanded=$it" }
        onExpandedChange(it)
    }
    Box(modifier) {
        HbTooltip(accessibleLabel) {
            val trigger = Modifier.focusRequester(focus)
                .semantics { contentDescription = accessibleLabel }
                .usageOpenKeys(enabled) { changeExpanded(true) }
            if (contextPercent == null) {
                HbButton(label, { changeExpanded(!isOpen) }, trigger, style = HbButtonStyle.Ghost, enabled = enabled)
            } else {
                ComposerUsageMeter(contextPercent, isCompacting, { changeExpanded(!isOpen) }, trigger, enabled)
            }
        }
        if (isOpen) ComposerUsagePopup(accessibleLabel, { changeExpanded(false) }, content = content)
    }
}

private fun Modifier.usageOpenKeys(enabled: Boolean, onOpen: () -> Unit): Modifier = onPreviewKeyEvent {
    val isArrow = it.key == Key.DirectionDown || it.key == Key.DirectionUp
    if (enabled && it.type == KeyEventType.KeyDown && isArrow) {
        onOpen()
        true
    } else {
        false
    }
}

@Composable
private fun ComposerUsageMeter(
    percent: Int,
    isCompacting: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interactions = remember { MutableInteractionSource() }
    val isFocused by interactions.collectIsFocusedAsState()
    val isHovered by interactions.collectIsHoveredAsState()
    val isPressed by interactions.collectIsPressedAsState()
    val colors = HbTheme.colors
    val motion = HbTheme.motion
    val base = HbTheme.surfaces.composerPill
    val background = when {
        enabled && isPressed -> colors.pressedOverlay.compositeOver(base)
        enabled && isHovered -> colors.interactionHoverOverlay.compositeOver(base)
        else -> base
    }
    val value = percent.coerceIn(0, PERCENT_MAX)
    val level = when {
        !enabled -> colors.textSecondary
        value >= CRITICAL_PERCENT -> colors.error
        value >= WARNING_PERCENT -> colors.warning
        else -> colors.brand
    }
    val accent by animateColorAsState(level, usageSpec(motion.normalMillis, motion), label = "usage-accent")
    val progress by animateFloatAsState(
        value / PERCENT_MAX.toFloat(),
        usageSpec(motion.slowMillis, motion),
        label = "usage-progress",
    )
    val shownPercent by animateIntAsState(value, usageSpec(motion.slowMillis, motion), label = "usage-percent")
    val shape = RoundedCornerShape(HbTheme.dimensions.controlCornerRadius)
    Box(
        modifier.heightIn(min = controlTargetSize(HbTheme.dimensions.composerPillHeight))
            .widthIn(min = maxOf(HbTheme.dimensions.composerUsageMinWidth, HbTheme.dimensions.touchTarget))
            .hbFocusOutline(isFocused, shape)
            .background(background, shape)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(value / PERCENT_MAX.toFloat(), 0f..1f) }
            .clickable(interactions, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = HbTheme.spacing.m, vertical = HbTheme.spacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        HbRow(gap = HbTheme.spacing.xs) {
            UsageRing(progress, accent, isCompacting)
            HbText(
                "$shownPercent%",
                style = HbTheme.typography.caption,
                color = if (isCompacting) colors.textSecondary else colors.textPrimary,
                maxLines = 1,
            )
        }
    }
}

private fun <T> usageSpec(durationMillis: Int, motion: HbMotion): AnimationSpec<T> =
    if (motion.isReducedMotion) snap() else tween(durationMillis, easing = FastOutSlowInEasing)

/**
 * Determinate ring filled clockwise from 12 o'clock by [progress]. While [isCompacting] the filled part fades
 * back and a comet-like sweep circles the ring; under reduced motion the sweep stays still.
 */
@Composable
private fun UsageRing(progress: Float, color: Color, isCompacting: Boolean, modifier: Modifier = Modifier) {
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

private const val PERCENT_MAX = 100
private const val WARNING_PERCENT = 80
private const val CRITICAL_PERCENT = 100
private const val QUARTER_TURN = 90f
private const val FULL_TURN = 360f
private const val COMET_SWEEP = 140f
private const val COMPACTING_FILL_ALPHA = 0.35f
