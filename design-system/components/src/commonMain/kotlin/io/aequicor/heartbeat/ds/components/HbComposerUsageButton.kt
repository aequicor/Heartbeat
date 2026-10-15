package io.aequicor.heartbeat.ds.components

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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
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
import io.aequicor.heartbeat.ds.theme.HbTheme

private val log = Log.tag("DS/ComposerUsage")

/**
 * Controlled usage disclosure in a composer. A known [contextPercent] renders a rounded rectangular button
 * with a determinate perimeter, filled clockwise from the top center by the fraction of its length;
 * null renders the caller's localized [label] (for example, "Limits") without implying zero usage.
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
                ComposerUsageMeter(contextPercent, { changeExpanded(!isOpen) }, trigger, enabled)
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
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interactions = remember { MutableInteractionSource() }
    val isFocused by interactions.collectIsFocusedAsState()
    val isHovered by interactions.collectIsHoveredAsState()
    val isPressed by interactions.collectIsPressedAsState()
    val colors = HbTheme.colors
    val base = HbTheme.surfaces.composerPill
    val background = when {
        enabled && isPressed -> colors.pressedOverlay.compositeOver(base)
        enabled && isHovered -> colors.interactionHoverOverlay.compositeOver(base)
        else -> base
    }
    val foreground = if (enabled) colors.brand else colors.textSecondary
    val track = colors.outlineSubtle
    val cornerRadius = HbTheme.dimensions.controlCornerRadius
    val shape = RoundedCornerShape(cornerRadius)
    val value = percent.coerceIn(0, 100)
    val strokeWidth = HbTheme.dimensions.usageRingStrokeWidth
    Box(
        modifier.heightIn(min = controlTargetSize(HbTheme.dimensions.composerPillHeight))
            .widthIn(min = maxOf(HbTheme.dimensions.composerUsageMinWidth, HbTheme.dimensions.touchTarget))
            .hbFocusOutline(isFocused, shape)
            .background(background, shape)
            .drawWithCache {
                val stroke = strokeWidth.toPx()
                val perimeter = usagePerimeter(size, stroke, cornerRadius.toPx())
                val progress = Path()
                if (value in 1..99) {
                    val measure = PathMeasure().apply { setPath(perimeter, false) }
                    measure.getSegment(0f, measure.length * value / 100f, progress, true)
                }
                onDrawBehind {
                    drawPath(perimeter, track, style = Stroke(stroke))
                    if (value > 0) {
                        drawPath(
                            if (value == 100) perimeter else progress,
                            foreground,
                            style = Stroke(stroke, cap = StrokeCap.Round),
                        )
                    }
                }
            }
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(value / 100f, 0f..1f) }
            .clickable(interactions, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = HbTheme.spacing.m, vertical = HbTheme.spacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        HbText("$value%", style = HbTheme.typography.caption, color = colors.textPrimary, maxLines = 1)
    }
}

/** Insets the stroke so its outer edge follows the same rounded rectangle as the button fill. */
private fun usagePerimeter(size: Size, stroke: Float, cornerRadius: Float): Path {
    val inset = stroke / 2f
    val bounds = Rect(inset, inset, size.width - inset, size.height - inset)
    val radius = (cornerRadius - inset).coerceIn(0f, bounds.minDimension.coerceAtLeast(0f) / 2f)
    val diameter = radius * 2f
    return Path().apply {
        moveTo(size.width / 2f, bounds.top)
        lineTo(bounds.right - radius, bounds.top)
        arcTo(
            Rect(bounds.right - diameter, bounds.top, bounds.right, bounds.top + diameter),
            -QUARTER_TURN,
            QUARTER_TURN,
            false,
        )
        lineTo(bounds.right, bounds.bottom - radius)
        arcTo(
            Rect(bounds.right - diameter, bounds.bottom - diameter, bounds.right, bounds.bottom),
            0f,
            QUARTER_TURN,
            false,
        )
        lineTo(bounds.left + radius, bounds.bottom)
        arcTo(
            Rect(bounds.left, bounds.bottom - diameter, bounds.left + diameter, bounds.bottom),
            QUARTER_TURN,
            QUARTER_TURN,
            false,
        )
        lineTo(bounds.left, bounds.top + radius)
        arcTo(
            Rect(bounds.left, bounds.top, bounds.left + diameter, bounds.top + diameter),
            HALF_TURN,
            QUARTER_TURN,
            false,
        )
        close()
    }
}

private const val QUARTER_TURN = 90f
private const val HALF_TURN = 180f
