package io.aequicor.heartbeat.ds.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
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
 * Fixed-size context and quota disclosure shared across all hosts through Foundation.
 * [contextPercent] fills the inner ring and colors the percentage independently of [quotaPercent],
 * which follows the pill perimeter clockwise from its top center. [quotaLabel] names that window.
 * Unknown measurements stay neutral. [isLoading] shows a sweep and an ellipsis; [isCompacting]
 * replaces the entire inner content with a centered sweep while the quota perimeter keeps working.
 * The visual pill matches other composer controls: 80x32 on desktop and 96x44 on touch hosts.
 * Its hit region covers the overlapping chip, with the same reserved space in every state.
 * Touch targets stay at least 48dp. Motion follows the theme's reduction setting.
 * Enter, Space and arrow keys open details; Escape and outside clicks dismiss and restore focus.
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
    quotaPercent: Int? = null,
    quotaLabel: String? = null,
    isLoading: Boolean = false,
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
            ComposerUsageMeter(
                contextPercent,
                quotaPercent,
                quotaLabel,
                isLoading,
                isCompacting,
                { changeExpanded(!isOpen) },
                trigger,
                enabled,
            )
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
    percent: Int?,
    quotaPercent: Int?,
    quotaLabel: String?,
    isLoading: Boolean,
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
    val dimensions = HbTheme.dimensions
    val motion = HbTheme.motion
    val background = usageBackground(enabled, isPressed, isHovered)
    val value = percent?.coerceIn(0, PERCENT_MAX)
    val accent = usageAccent(value, enabled)
    val quotaAccent = usageAccent(quotaPercent, enabled)
    val progress by animateFloatAsState(
        (value ?: 0) / PERCENT_MAX.toFloat(),
        usageSpec(motion.slowMillis, motion),
        label = "usage-progress",
    )
    val quotaProgress = animateFloatAsState(
        (quotaPercent ?: 0).coerceIn(0, PERCENT_MAX) / PERCENT_MAX.toFloat(),
        usageSpec(motion.slowMillis, motion),
        label = "quota-progress",
    )
    val shownPercent by animateIntAsState(value ?: 0, usageSpec(motion.slowMillis, motion), label = "usage-percent")
    val shape = RoundedCornerShape(percent = 50)
    val chipSpace = if (dimensions.isDesktop) dimensions.usageQuotaChipHeight else dimensions.usageQuotaChipHeight / 2
    Box(
        modifier.size(
            dimensions.composerUsageMinWidth,
            maxOf(dimensions.composerUsageHeight + chipSpace, dimensions.touchTarget),
        )
            .semantics {
                if (value != null && !isCompacting && !isLoading) {
                    progressBarRangeInfo = ProgressBarRangeInfo(value / PERCENT_MAX.toFloat(), 0f..1f)
                }
            }
            .clickable(interactions, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = if (dimensions.isDesktop) Alignment.Center else Alignment.BottomCenter,
    ) {
        Box(
            Modifier.size(dimensions.composerUsageMinWidth, dimensions.composerUsageHeight)
                .hbFocusOutline(isFocused, shape)
                .background(background, shape)
                .usageQuotaBorder(quotaProgress, quotaAccent, colors.outlineSubtle, dimensions.usageQuotaStrokeWidth),
            contentAlignment = Alignment.Center,
        ) {
            UsageMeterContent(value, isLoading, isCompacting, progress, shownPercent, accent)
            if (!isLoading && !isCompacting) UsageQuotaChip(quotaLabel, quotaPercent, background, quotaAccent)
        }
    }
}

@Composable
@ReadOnlyComposable
private fun usageBackground(enabled: Boolean, isPressed: Boolean, isHovered: Boolean): Color {
    val colors = HbTheme.colors
    val base = HbTheme.surfaces.composerPill
    return when {
        enabled && isPressed -> colors.pressedOverlay.compositeOver(base)
        enabled && isHovered -> colors.interactionHoverOverlay.compositeOver(base)
        else -> base
    }
}

@Composable
private fun usageAccent(percent: Int?, enabled: Boolean): Color {
    val colors = HbTheme.colors
    val accent by animateColorAsState(
        if (enabled) colors.usageColor(percent) else colors.textSecondary,
        usageSpec(HbTheme.motion.normalMillis, HbTheme.motion),
        label = "usage-accent",
    )
    return accent
}

@Composable
private fun UsageMeterContent(
    value: Int?,
    isLoading: Boolean,
    isCompacting: Boolean,
    progress: Float,
    shownPercent: Int,
    accent: Color,
) {
    if (isCompacting) {
        UsageRing(0f, HbTheme.colors.textSecondary, true)
    } else {
        HbRow(gap = HbTheme.spacing.xs) {
            UsageRing(if (isLoading) 0f else progress, accent, isLoading)
            val text = when {
                isLoading -> "…"
                value == null -> "—"
                else -> "$shownPercent%"
            }
            HbText(text, style = HbTheme.typography.label, color = accent, maxLines = 1)
        }
    }
}

@Composable
private fun BoxScope.UsageQuotaChip(label: String?, percent: Int?, background: Color, accent: Color) {
    val dimensions = HbTheme.dimensions
    val shape = RoundedCornerShape(percent = 50)
    Box(
        Modifier.align(Alignment.TopCenter).offset(y = -dimensions.usageQuotaChipHeight / 2)
            .height(dimensions.usageQuotaChipHeight)
            .background(background, shape)
            .border(dimensions.borderWidth, if (percent == null) HbTheme.colors.outlineSubtle else accent, shape)
            .padding(horizontal = HbTheme.spacing.s),
        contentAlignment = Alignment.Center,
    ) {
        HbText(
            label?.takeIf { percent != null } ?: "—",
            style = HbTheme.typography.caption,
            color = accent,
            maxLines = 1,
        )
    }
}

internal fun <T> usageSpec(durationMillis: Int, motion: HbMotion): AnimationSpec<T> =
    if (motion.isReducedMotion) snap() else tween(durationMillis, easing = FastOutSlowInEasing)

private const val PERCENT_MAX = 100
