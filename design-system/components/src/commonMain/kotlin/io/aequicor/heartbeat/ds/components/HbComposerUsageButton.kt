package io.aequicor.heartbeat.ds.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.testTag
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

/** The independently focusable portions of the usage disclosure. */
public enum class HbUsageSection { Context, Limits }

/**
 * Two adjacent buttons open the matching detail section. Missing values stay neutral; when both
 * measurements are absent one button displays one dash. [hasContextData] and [hasQuotaData] retain
 * partial observations such as tokens without capacity or credits without a percentage.
 * The Foundation implementation is shared by all kits. Each touch segment is at least 48dp.
 * Escape restores the initiating segment's focus. Compaction affects only the context ring.
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
    section: HbUsageSection = HbUsageSection.Context,
    onSectionChange: (HbUsageSection) -> Unit = {},
    quotaAccessibleLabel: String = label,
    hasContextData: Boolean = contextPercent != null,
    hasQuotaData: Boolean = quotaPercent != null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val contextFocus = remember { FocusRequester() }
    val quotaFocus = remember { FocusRequester() }
    var origin by remember { mutableStateOf(HbUsageSection.Context) }
    var isPreviouslyOpen by remember { mutableStateOf(false) }
    val isOpen = isExpanded && enabled
    val isEmpty = !hasContextData && !hasQuotaData && !isCompacting && !isLoading
    SideEffect {
        if (!isOpen && isPreviouslyOpen && enabled) {
            if (origin == HbUsageSection.Limits && !isEmpty) quotaFocus.requestFocus() else contextFocus.requestFocus()
        }
        isPreviouslyOpen = isOpen
    }
    val open: (HbUsageSection) -> Unit = { target ->
        origin = target
        onSectionChange(target)
        log.i { "composer usage section=$target expanded=${!isOpen || target != section}" }
        onExpandedChange(!isOpen || target != section)
    }
    Box(modifier) {
        UsageIndicator(
            accessibleLabel, quotaAccessibleLabel, enabled, isEmpty, contextFocus, quotaFocus,
            contextPercent, quotaPercent, quotaLabel, isCompacting, isLoading, open,
        )
        if (isOpen) {
            ComposerUsagePopup(
                label,
                { onExpandedChange(false) },
                onAnchorClick = { fraction -> open(sectionAt(fraction, isEmpty)) },
                content = content,
            )
        }
    }
}

private fun sectionAt(fraction: Float, empty: Boolean): HbUsageSection =
    if (empty || fraction < CONTEXT_WEIGHT / TOTAL_WEIGHT) HbUsageSection.Context else HbUsageSection.Limits

@Composable
private fun UsageIndicator(
    accessibleLabel: String,
    quotaAccessibleLabel: String,
    enabled: Boolean,
    isEmpty: Boolean,
    contextFocus: FocusRequester,
    quotaFocus: FocusRequester,
    contextPercent: Int?,
    quotaPercent: Int?,
    quotaLabel: String?,
    isCompacting: Boolean,
    isLoading: Boolean,
    open: (HbUsageSection) -> Unit,
) {
    val dimensions = HbTheme.dimensions
    val height = maxOf(dimensions.composerUsageHeight, dimensions.touchTarget)
    val width = if (isEmpty) {
        dimensions.composerUsageMinWidth * CONTEXT_WEIGHT / TOTAL_WEIGHT
    } else {
        dimensions.composerUsageMinWidth
    }
    Row(
        Modifier.size(width, height)
            .testTag("usage-meter")
            .background(HbTheme.surfaces.composerPill, HbTheme.shapes.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UsageSegment(
            accessibleLabel,
            enabled,
            contextFocus,
            { open(HbUsageSection.Context) },
            Modifier.width(
                if (isEmpty) {
                    width
                } else {
                    (dimensions.composerUsageMinWidth - dimensions.borderWidth) * CONTEXT_WEIGHT / TOTAL_WEIGHT
                },
            ).height(height).testTag("usage-context"),
            contextPercent.takeUnless { isCompacting || isLoading || isEmpty },
        ) {
            if (isEmpty) {
                HbText("—", style = HbTheme.typography.caption, color = HbTheme.colors.textSecondary)
            } else {
                ContextMeter(contextPercent, isCompacting, isLoading, enabled)
            }
        }
        if (!isEmpty) {
            Box(
                Modifier.width(dimensions.borderWidth).height(dimensions.iconSmallSize)
                    .background(HbTheme.colors.outlineSubtle),
            )
            UsageSegment(
                quotaAccessibleLabel,
                enabled,
                quotaFocus,
                { open(HbUsageSection.Limits) },
                Modifier.width(
                    (dimensions.composerUsageMinWidth - dimensions.borderWidth) * QUOTA_WEIGHT / TOTAL_WEIGHT,
                ).height(height).testTag("usage-limits"),
                quotaPercent,
            ) {
                QuotaMeter(quotaPercent, quotaLabel, enabled)
            }
        }
    }
}

@Composable
private fun UsageSegment(
    label: String,
    enabled: Boolean,
    focus: FocusRequester,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    percent: Int? = null,
    content: @Composable () -> Unit,
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
    HbTooltip(label) {
        Box(
            modifier.focusRequester(focus)
                .hbFocusOutline(isFocused, HbTheme.shapes.small)
                .background(background, HbTheme.shapes.small)
                .semantics {
                    contentDescription = label
                    if (percent != null) {
                        progressBarRangeInfo = ProgressBarRangeInfo(percent.coerceIn(0, PERCENT_MAX) / 100f, 0f..1f)
                    }
                }
                .onPreviewKeyEvent {
                    if (enabled && it.type == KeyEventType.KeyDown &&
                        it.key in listOf(Key.DirectionDown, Key.DirectionUp)
                    ) {
                        onClick()
                        true
                    } else {
                        false
                    }
                }
                .clickable(interactions, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

@Composable
private fun ContextMeter(percent: Int?, compacting: Boolean, loading: Boolean, enabled: Boolean) {
    val value = percent?.coerceIn(0, PERCENT_MAX)
    val motion = HbTheme.motion
    val accent by animateColorAsState(
        if (enabled) HbTheme.colors.usageColor(value) else HbTheme.colors.textSecondary,
        usageSpec(motion.normalMillis, motion),
        label = "context-accent",
    )
    val progress by animateFloatAsState(
        (value ?: 0) / 100f,
        usageSpec(motion.slowMillis, motion),
        label = "context-progress",
    )
    val isSpinning = compacting || loading
    val ringProgress = progress.takeUnless { isSpinning || value == null } ?: 0f
    val ringColor = contextRingColor(value, accent, compacting, enabled)
    val text = contextText(value, loading)
    val textColor = if (enabled) HbTheme.colors.textPrimary else HbTheme.colors.textSecondary
    HbRow(gap = HbTheme.spacing.xs) {
        UsageRing(
            ringProgress,
            ringColor,
            isSpinning,
        )
        if (!compacting) {
            HbText(
                text,
                style = HbTheme.typography.caption,
                color = textColor,
                maxLines = 1,
            )
        }
    }
}

@Composable
@ReadOnlyComposable
private fun contextRingColor(value: Int?, accent: Color, compacting: Boolean, enabled: Boolean): Color = when {
    !enabled -> HbTheme.colors.textSecondary
    compacting -> HbTheme.colors.brand
    value == null -> HbTheme.colors.textSecondary
    else -> accent
}

private fun contextText(value: Int?, loading: Boolean): String = if (loading) "…" else value?.let { "$it%" } ?: "—"

@Composable
private fun QuotaMeter(percent: Int?, label: String?, enabled: Boolean) {
    if (percent == null) {
        HbText("—", style = HbTheme.typography.caption, color = HbTheme.colors.textSecondary)
        return
    }
    val motion = HbTheme.motion
    val progress by animateFloatAsState(
        percent.coerceIn(0, PERCENT_MAX) / 100f,
        usageSpec(motion.slowMillis, motion),
        label = "quota-progress",
    )
    Column(
        Modifier.width(HbTheme.dimensions.composerActionSize),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HbText(
            label ?: "—",
            style = HbTheme.typography.caption,
            color = HbTheme.colors.textSecondary,
            maxLines = 1,
        )
        Box(
            Modifier.fillMaxWidth().height(HbTheme.dimensions.usageBarHeight)
                .background(HbTheme.colors.outlineSubtle, RoundedCornerShape(HbTheme.dimensions.controlCornerRadius)),
        ) {
            Box(
                Modifier.fillMaxWidth(progress).height(HbTheme.dimensions.usageBarHeight)
                    .background(
                        if (enabled) HbTheme.colors.usageColor(percent) else HbTheme.colors.textSecondary,
                        RoundedCornerShape(HbTheme.dimensions.controlCornerRadius),
                    ),
            )
        }
    }
}

internal fun <T> usageSpec(durationMillis: Int, motion: HbMotion): AnimationSpec<T> =
    if (motion.isReducedMotion) snap() else tween(durationMillis, easing = FastOutSlowInEasing)

private const val PERCENT_MAX = 100
private const val CONTEXT_WEIGHT = 62f
private const val QUOTA_WEIGHT = 50f
private const val TOTAL_WEIGHT = CONTEXT_WEIGHT + QUOTA_WEIGHT
