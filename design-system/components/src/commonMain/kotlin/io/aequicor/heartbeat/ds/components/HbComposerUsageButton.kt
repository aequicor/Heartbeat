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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
 * Controlled usage disclosure in a composer. A known [contextPercent] renders a determinate meter;
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
    val base = HbTheme.surfaces.composer
    val background = when {
        enabled && isPressed -> colors.pressedOverlay.compositeOver(base)
        enabled && isHovered -> colors.interactionHoverOverlay.compositeOver(base)
        else -> base
    }
    val foreground = if (enabled) colors.brand else colors.textSecondary
    val track = colors.outlineSubtle
    val shape = RoundedCornerShape(percent = 50)
    val value = percent.coerceIn(0, 100)
    val strokeWidth = HbTheme.dimensions.usageRingStrokeWidth
    Box(
        modifier.heightIn(min = controlTargetSize(HbTheme.dimensions.composerPillHeight))
            .widthIn(min = maxOf(HbTheme.dimensions.composerUsageMinWidth, HbTheme.dimensions.touchTarget))
            .hbFocusOutline(isFocused, shape)
            .background(background, shape)
            .drawBehind {
                val stroke = strokeWidth.toPx()
                val inset = Offset(stroke / 2f, stroke / 2f)
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(track, 0f, FULL_TURN, false, inset, arcSize, style = Stroke(stroke))
                if (value > 0) {
                    drawArc(
                        foreground,
                        -QUARTER_TURN,
                        FULL_TURN * value / 100f,
                        false,
                        inset,
                        arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
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

private const val FULL_TURN = 360f
private const val QUARTER_TURN = 90f
